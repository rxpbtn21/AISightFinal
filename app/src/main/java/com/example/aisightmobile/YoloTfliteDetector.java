package com.example.aisightmobile;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the custom AISight YOLO26n model (float32 TFLite). The tensor decoding below is unchanged from the original
 * (it is tied to how scripts/export_models.py exports the model); only the image preprocessing was rewritten.
 */
final class YoloTfliteDetector implements AutoCloseable {
    private static final String MODEL_ASSET = "aisight.tflite";
    private static final int MAX_RESULTS = 20;
    private static final float NMS_IOU = 0.45f;
    private static final int CLASS_COUNT = Lexicon.MODEL_LABELS.length;

    private final Interpreter interpreter;
    private final int inputWidth, inputHeight;
    private final boolean inputNhwc;
    private final int[] outputShape;
    private final ByteBuffer inputBuffer;
    private final float[][][] outputBuffer;
    private final Letterbox letterbox;

    YoloTfliteDetector(@NonNull Context context) throws IOException {
        File modelFile = ModelStore.fileFor(context, MODEL_ASSET);
        try (FileInputStream stream = new FileInputStream(modelFile);
             FileChannel channel = stream.getChannel()) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            interpreter = new Interpreter(mapped, new Interpreter.Options().setNumThreads(4));
        } catch (RuntimeException ex) {
            throw new IOException("Could not load AISight TFLite model: " + ex.getMessage(), ex);
        }

        int[] inputShape = interpreter.getInputTensor(0).shape();
        if (inputShape.length != 4) {
            interpreter.close();
            throw new IOException("Expected a rank-4 image model input; got " + Arrays.toString(inputShape));
        }
        inputNhwc = inputShape[3] == 3;
        if (inputNhwc) {
            inputHeight = inputShape[1];
            inputWidth = inputShape[2];
        } else if (inputShape[1] == 3) {
            inputHeight = inputShape[2];
            inputWidth = inputShape[3];
        } else {
            interpreter.close();
            throw new IOException("Could not identify RGB input layout: " + Arrays.toString(inputShape));
        }
        outputShape = interpreter.getOutputTensor(0).shape();
        if (inputWidth <= 0 || inputHeight <= 0 || outputShape.length != 3 || outputShape[0] != 1
                || Math.min(outputShape[1], outputShape[2]) < CLASS_COUNT + 4) {
            interpreter.close();
            throw new IOException("Unsupported model tensor layout. Input=" + Arrays.toString(inputShape)
                    + ", output=" + Arrays.toString(outputShape));
        }
        inputBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4).order(ByteOrder.nativeOrder());
        outputBuffer = new float[outputShape[0]][outputShape[1]][outputShape[2]];
        letterbox = new Letterbox(inputWidth, inputHeight);
    }

    /** @param bitmap the UPRIGHT camera frame. Returned boxes are in that frame's pixels. */
    synchronized List<Detection> detect(@NonNull Bitmap bitmap, float threshold) {
        int[] pixels = letterbox.render(bitmap);
        float scaleX = letterbox.scaleX(), scaleY = letterbox.scaleY();
        int padX = letterbox.padX, padY = letterbox.padY;

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

        Map<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, outputBuffer);
        interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);
        float[][][] output = outputBuffer;

        boolean channelFirst = outputShape[1] <= 128 && outputShape[2] > outputShape[1];
        int features = channelFirst ? outputShape[1] : outputShape[2];
        int candidates = channelFirst ? outputShape[2] : outputShape[1];
        int classOffset;
        boolean hasObjectness;
        if (features == CLASS_COUNT + 5) {
            classOffset = 5;
            hasObjectness = true;
        } else if (features >= CLASS_COUNT + 4) {
            classOffset = 4;
            hasObjectness = false;
        } else {
            throw new IllegalStateException("Unexpected YOLO output: " + Arrays.toString(outputShape)
                    + " for " + CLASS_COUNT + " classes");
        }

        List<Detection> proposals = new ArrayList<>();
        for (int n = 0; n < candidates; n++) {
            float best = 0f;
            int bestClass = -1;
            float objectness = hasObjectness ? value(output, channelFirst, 4, n) : 1f;
            for (int c = 0; c < CLASS_COUNT; c++) {
                float score = value(output, channelFirst, classOffset + c, n) * objectness;
                if (score > best) {
                    best = score;
                    bestClass = c;
                }
            }
            if (bestClass < 0 || best < threshold) continue;

            float cx = value(output, channelFirst, 0, n);
            float cy = value(output, channelFirst, 1, n);
            float bw = value(output, channelFirst, 2, n);
            float bh = value(output, channelFirst, 3, n);
            // Some exporters emit normalized coordinates; others emit model-space pixels.
            if (Math.max(Math.max(Math.abs(cx), Math.abs(cy)), Math.max(Math.abs(bw), Math.abs(bh))) <= 1.5f) {
                cx *= inputWidth; cy *= inputHeight; bw *= inputWidth; bh *= inputHeight;
            }
            float left = ((cx - bw / 2f) - padX) / scaleX;
            float top = ((cy - bh / 2f) - padY) / scaleY;
            float right = ((cx + bw / 2f) - padX) / scaleX;
            float bottom = ((cy + bh / 2f) - padY) / scaleY;

            left = clamp(left, 0f, bitmap.getWidth());
            right = clamp(right, 0f, bitmap.getWidth());
            top = clamp(top, 0f, bitmap.getHeight());
            bottom = clamp(bottom, 0f, bitmap.getHeight());
            if (right - left < 2f || bottom - top < 2f) continue;
            proposals.add(new Detection(Lexicon.MODEL_LABELS[bestClass], best, bestClass, left, top, right, bottom));
        }
        return nonMaxSuppression(proposals);
    }

    private static List<Detection> nonMaxSuppression(List<Detection> proposals) {
        Collections.sort(proposals, (a, b) -> Float.compare(b.confidence, a.confidence));
        List<Detection> kept = new ArrayList<>();
        for (Detection candidate : proposals) {
            boolean suppressed = false;
            for (Detection existing : kept) {
                if (existing.classId == candidate.classId && iou(existing, candidate) > NMS_IOU) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) kept.add(candidate);
            if (kept.size() >= MAX_RESULTS) break;
        }
        return kept;
    }

    private static float iou(Detection a, Detection b) {
        float ix = Math.max(0f, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        float iy = Math.max(0f, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        float inter = ix * iy;
        float union = a.area() + b.area() - inter;
        return union <= 0f ? 0f : inter / union;
    }

    private static float value(float[][][] output, boolean channelFirst, int feature, int candidate) {
        return channelFirst ? output[0][feature][candidate] : output[0][candidate][feature];
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override public synchronized void close() {
        interpreter.close();
        letterbox.release();
    }
}
