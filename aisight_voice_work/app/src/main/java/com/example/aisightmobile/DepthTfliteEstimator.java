package com.example.aisightmobile;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * Runs yolo26n-depth (float32 TFLite): a metric depth map in meters, lower = closer.
 * Tensor layout handling and the frame<->map coordinate mapping are unchanged from the original; the preprocessing now
 * reuses its buffers, and a NaN pixel can no longer poison the temporal smoothing forever.
 */
final class DepthTfliteEstimator implements AutoCloseable {
    private static final String MODEL_ASSET = "depth.tflite";
    private static final float SMOOTHING_ALPHA = 0.55f;

    private final Interpreter interpreter;
    private final int inputWidth, inputHeight;
    private final boolean inputNhwc;
    private final int outputWidth, outputHeight;
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;
    private final Letterbox letterbox;
    private final float[] raw;
    private final float[] smoothed;
    private boolean smoothedValid;

    DepthTfliteEstimator(@NonNull Context context) throws IOException {
        File modelFile = ModelStore.fileFor(context, MODEL_ASSET);
        try (FileInputStream stream = new FileInputStream(modelFile);
             FileChannel channel = stream.getChannel()) {
            ByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            interpreter = new Interpreter(mapped, new Interpreter.Options().setNumThreads(3));
        } catch (RuntimeException ex) {
            throw new IOException("Could not load YOLO26 depth model: " + message(ex), ex);
        }

        Tensor input = interpreter.getInputTensor(0);
        int[] inShape = input.shape();
        if (input.dataType() != DataType.FLOAT32 || inShape.length != 4) {
            interpreter.close();
            throw new IOException("Depth model must have rank-4 FLOAT32 input; got " + Arrays.toString(inShape)
                    + " / " + input.dataType());
        }
        inputNhwc = inShape[3] == 3;
        if (inputNhwc) {
            inputHeight = inShape[1];
            inputWidth = inShape[2];
        } else if (inShape[1] == 3) {
            inputHeight = inShape[2];
            inputWidth = inShape[3];
        } else {
            interpreter.close();
            throw new IOException("Unrecognized depth-model input layout: " + Arrays.toString(inShape));
        }

        Tensor output = interpreter.getOutputTensor(0);
        int[] outShape = output.shape();
        if (output.dataType() != DataType.FLOAT32) {
            interpreter.close();
            throw new IOException("Depth output must be FLOAT32; got " + output.dataType());
        }
        if (outShape.length == 4 && outShape[0] == 1) {
            if (outShape[1] == 1 && outShape[2] > 4 && outShape[3] > 4) {
                outputHeight = outShape[2];
                outputWidth = outShape[3];
            } else if (outShape[3] == 1 && outShape[1] > 4 && outShape[2] > 4) {
                outputHeight = outShape[1];
                outputWidth = outShape[2];
            } else {
                interpreter.close();
                throw new IOException("Cannot infer depth output channel layout: " + Arrays.toString(outShape));
            }
        } else if (outShape.length == 3 && outShape[0] == 1) {
            // A dense depth map is often returned as [1, height, width].
            outputHeight = outShape[1];
            outputWidth = outShape[2];
        } else {
            interpreter.close();
            throw new IOException("Unsupported depth output layout: " + Arrays.toString(outShape));
        }
        int elements = 1;
        for (int dim : outShape) elements *= dim;
        if (outputWidth <= 0 || outputHeight <= 0 || elements < outputWidth * outputHeight) {
            interpreter.close();
            throw new IOException("Invalid depth output dimensions: " + Arrays.toString(outShape));
        }
        inputBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4).order(ByteOrder.nativeOrder());
        outputBuffer = ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder());
        letterbox = new Letterbox(inputWidth, inputHeight);
        raw = new float[elements];
        smoothed = new float[elements];
    }

    /**
     * The returned map shares its array with this estimator and is only valid until the next estimate() call.
     * (The pipeline uses it on the same thread right away, so no defensive copy is made on every frame.)
     */
    synchronized DepthMap estimate(@NonNull Bitmap frame) throws IOException {
        int[] pixels = letterbox.render(frame);

        inputBuffer.clear();
        if (inputNhwc) {
            for (int px : pixels) {
                inputBuffer.putFloat(((px >> 16) & 0xff) / 255.0f);
                inputBuffer.putFloat(((px >> 8) & 0xff) / 255.0f);
                inputBuffer.putFloat((px & 0xff) / 255.0f);
            }
        } else {
            for (int shift : new int[]{16, 8, 0}) {
                for (int px : pixels) inputBuffer.putFloat(((px >> shift) & 0xff) / 255.0f);
            }
        }
        inputBuffer.rewind();
        outputBuffer.clear();
        try {
            interpreter.run(inputBuffer, outputBuffer);
        } catch (RuntimeException ex) {
            throw new IOException("Depth inference failed: " + message(ex), ex);
        }
        outputBuffer.rewind();
        FloatBuffer view = outputBuffer.asFloatBuffer();
        view.get(raw, 0, Math.min(raw.length, view.remaining()));

        if (!smoothedValid) {
            System.arraycopy(raw, 0, smoothed, 0, raw.length);
            smoothedValid = true;
        } else {
            for (int i = 0; i < raw.length; i++) {
                float r = raw[i];
                if (Float.isNaN(r) || Float.isInfinite(r)) continue;          // keep the last good value
                float s = smoothed[i];
                smoothed[i] = (Float.isNaN(s) || Float.isInfinite(s))
                        ? r : SMOOTHING_ALPHA * s + (1.0f - SMOOTHING_ALPHA) * r;
            }
        }
        return new DepthMap(smoothed, outputWidth, outputHeight, inputWidth, inputHeight,
                frame.getWidth(), frame.getHeight(), letterbox.scale, letterbox.padX, letterbox.padY);
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    @Override public synchronized void close() {
        interpreter.close();
        letterbox.release();
    }

    // ------------------------------------------------------------------------------------------------------------
    static final class WallResult {
        final boolean rawWall;
        final float meanMeters;
        WallResult(boolean rawWall, float meanMeters) { this.rawWall = rawWall; this.meanMeters = meanMeters; }
    }

    static final class DepthMap {
        final float[] values;
        final int mapWidth, mapHeight, inputWidth, inputHeight, frameWidth, frameHeight;
        final float scale;
        final int padX, padY;

        DepthMap(float[] values, int mapWidth, int mapHeight, int inputWidth, int inputHeight,
                 int frameWidth, int frameHeight, float scale, int padX, int padY) {
            this.values = values;
            this.mapWidth = mapWidth; this.mapHeight = mapHeight;
            this.inputWidth = inputWidth; this.inputHeight = inputHeight;
            this.frameWidth = frameWidth; this.frameHeight = frameHeight;
            this.scale = scale; this.padX = padX; this.padY = padY;
        }

        boolean matches(int w, int h) { return frameWidth == w && frameHeight == h; }

        /** Median distance (meters) in the middle two thirds of the box: edges would mostly measure the background. */
        float sampleMedian(float left, float top, float right, float bottom) {
            int x1 = clamp((int) left, 0, frameWidth - 1);
            int y1 = clamp((int) top, 0, frameHeight - 1);
            int x2 = clamp((int) Math.ceil(right), x1 + 1, frameWidth);
            int y2 = clamp((int) Math.ceil(bottom), y1 + 1, frameHeight);
            int dx = Math.max(0, (x2 - x1) / 6);
            int dy = Math.max(0, (y2 - y1) / 6);
            x1 += dx; x2 -= dx; y1 += dy; y2 -= dy;
            int mx1 = mapX(x1), mx2 = Math.max(mx1 + 1, mapX(x2));
            int my1 = mapY(y1), my2 = Math.max(my1 + 1, mapY(y2));
            mx1 = clamp(mx1, 0, mapWidth - 1); mx2 = clamp(mx2, mx1 + 1, mapWidth);
            my1 = clamp(my1, 0, mapHeight - 1); my2 = clamp(my2, my1 + 1, mapHeight);
            int step = Math.max(1, Math.max(mx2 - mx1, my2 - my1) / 24);
            float[] samples = new float[Math.max(1, ((mx2 - mx1 + step - 1) / step) * ((my2 - my1 + step - 1) / step))];
            int count = 0;
            for (int y = my1; y < my2; y += step) {
                for (int x = mx1; x < mx2; x += step) {
                    float v = get(y, x);
                    if (isFinite(v) && v > 0.02f && v < 1000.0f) {
                        if (count == samples.length) samples = Arrays.copyOf(samples, samples.length * 2);
                        samples[count++] = v;
                    }
                }
            }
            if (count == 0) return Float.POSITIVE_INFINITY;
            Arrays.sort(samples, 0, count);
            return samples[count / 2];
        }

        /** Is most of the middle of the view very close? (a wall, a door, a parked truck...) */
        WallResult detectWall() {
            int mx1 = clamp(mapX(Math.round(frameWidth * Tuning.WALL_X0)), 0, mapWidth - 1);
            int mx2 = clamp(mapX(Math.round(frameWidth * Tuning.WALL_X1)), mx1 + 1, mapWidth);
            int my1 = clamp(mapY(Math.round(frameHeight * Tuning.WALL_Y0)), 0, mapHeight - 1);
            int my2 = clamp(mapY(Math.round(frameHeight * Tuning.WALL_Y1)), my1 + 1, mapHeight);
            int step = Math.max(1, Math.max(mx2 - mx1, my2 - my1) / 32);
            int valid = 0, close = 0;
            double sum = 0;
            for (int y = my1; y < my2; y += step) {
                for (int x = mx1; x < mx2; x += step) {
                    float v = get(y, x);
                    if (!isFinite(v) || v <= 0.02f || v >= 1000.0f) continue;
                    valid++;
                    sum += v;
                    if (v <= Tuning.WALL_THRESHOLD_M) close++;
                }
            }
            if (valid == 0) return new WallResult(false, Float.POSITIVE_INFINITY);
            return new WallResult(close / (float) valid >= Tuning.WALL_COVERAGE, (float) (sum / valid));
        }

        private int mapX(int frameX) {
            float modelX = frameX * scale + padX;
            return Math.round(modelX * mapWidth / (float) inputWidth);
        }

        private int mapY(int frameY) {
            float modelY = frameY * scale + padY;
            return Math.round(modelY * mapHeight / (float) inputHeight);
        }

        /** Row-major in every layout we accept ([1,H,W], [1,H,W,1], [1,1,H,W]). */
        private float get(int y, int x) { return values[y * mapWidth + x]; }

        private static boolean isFinite(float v) { return !Float.isNaN(v) && !Float.isInfinite(v); }
        private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    }
}
