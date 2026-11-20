package com.example.aisightmobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.SystemClock;

import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything heavy: frame -> YOLO -> depth -> planner. Runs on ONE worker thread; while it is busy new frames are
 * simply dropped (never queued), so the camera callback never waits for it.
 */
final class AnalysisPipeline implements CameraController.FrameSink {
    enum ModelState { LOADING, READY, FAILED }

    static final class Result {
        final AnnouncementPlanner.Plan plan;
        final int frameW, frameH;
        final boolean depthOk;
        final long millis;
        Result(AnnouncementPlanner.Plan plan, int frameW, int frameH, boolean depthOk, long millis) {
            this.plan = plan; this.frameW = frameW; this.frameH = frameH; this.depthOk = depthOk; this.millis = millis;
        }
    }

    interface Listener {
        void onResult(Result result);                       // worker thread
        void onOcrText(String text);                        // any thread; "" = nothing found
        void onModelStatus(ModelState yolo, ModelState depth, String detail);
    }

    private final Context context;
    private final AppSettings settings;
    private final AnnouncementPlanner planner;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "aisight-analysis"));
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> new Thread(r, "aisight-loader"));
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean ocrRequested = new AtomicBoolean();

    private volatile YoloTfliteDetector yolo;
    private volatile DepthTfliteEstimator depth;
    private volatile ModelState yoloState = ModelState.LOADING, depthState = ModelState.LOADING;
    private volatile String detail = "";
    private volatile long lastAcceptedAt;
    private volatile float avgMillis;
    private DepthTfliteEstimator.DepthMap latestDepth;      // worker thread only
    private int frameCount, consecutiveFailures;
    private TextRecognizer recognizer;

    AnalysisPipeline(Context context, AppSettings settings, AnnouncementPlanner planner, Listener listener) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.planner = planner;
        this.listener = listener;
    }

    float averageMillis() { return avgMillis; }
    boolean detectorReady() { return yolo != null; }

    // ---- model loading (off the main thread; first launch copies ~30 MB) ---------------------------------------
    void loadModelsAsync() {
        loader.execute(() -> {
            if (yolo == null) {
                yoloState = ModelState.LOADING;
                publish();
                try {
                    yolo = new YoloTfliteDetector(context);
                    yoloState = ModelState.READY;
                } catch (IOException | RuntimeException ex) {
                    yoloState = ModelState.FAILED;
                    detail = "Detector: " + message(ex);
                }
                publish();
            }
            if (depth == null) {
                depthState = ModelState.LOADING;
                publish();
                try {
                    depth = new DepthTfliteEstimator(context);
                    depthState = ModelState.READY;
                } catch (IOException | RuntimeException ex) {
                    depthState = ModelState.FAILED;           // not fatal: objects are still announced, without distances
                    if (yoloState == ModelState.READY) detail = "Distance: " + message(ex);
                }
                publish();
            }
        });
    }

    private void publish() { listener.onModelStatus(yoloState, depthState, detail); }

    private static String message(Throwable t) { return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }

    void requestOcr() { ocrRequested.set(true); }

    // ---- frames -------------------------------------------------------------------------------------------------
    /** Camera thread. Always closes the proxy. */
    @Override public void onFrame(ImageProxy proxy, long now) {
        try {
            boolean ocr = ocrRequested.get();
            if (!ocr && yolo == null) return;
            long interval = settings.wearable ? 700 : 250;
            if (!ocr && now - lastAcceptedAt < interval) return;
            if (!ocr && busy.get()) return;

            Bitmap frame = toUpright(proxy);
            lastAcceptedAt = now;

            if (ocr && ocrRequested.compareAndSet(true, false)) {
                runOcr(frame);
                return;
            }
            if (!busy.compareAndSet(false, true)) {
                frame.recycle();
                return;
            }
            worker.execute(() -> {
                try {
                    process(frame);
                } finally {
                    busy.set(false);
                    frame.recycle();
                }
            });
        } catch (RuntimeException ex) {
            // one bad frame must never take the camera callback down with it
        } finally {
            proxy.close();
        }
    }

    /** CameraX converts YUV->RGB natively; the old code did it pixel by pixel in Java (hundreds of ms per frame). */
    private static Bitmap toUpright(ImageProxy proxy) {
        Bitmap raw = proxy.toBitmap();
        int degrees = proxy.getImageInfo().getRotationDegrees();
        if (degrees == 0) return raw;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap upright = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
        if (upright != raw) raw.recycle();
        return upright;
    }

    private void process(Bitmap frame) {
        YoloTfliteDetector detector = yolo;
        if (detector == null) return;
        long started = SystemClock.elapsedRealtime();
        int w = frame.getWidth(), h = frame.getHeight();
        try {
            List<Detection> raw = detector.detect(frame, settings.confidence);

            DepthTfliteEstimator estimator = depth;
            boolean depthOk = false;
            boolean wallRaw = false;
            float wallMeters = Float.NaN;
            if (estimator != null) {
                frameCount++;
                int every = avgMillis > 1500 ? 4 : avgMillis > 700 ? 3 : 2;     // slow phone: refresh depth less often
                if (latestDepth == null || !latestDepth.matches(w, h) || frameCount % every == 0) {
                    try {
                        latestDepth = estimator.estimate(frame);
                    } catch (IOException ex) {
                        latestDepth = null;
                    }
                }
                if (latestDepth != null) {
                    depthOk = true;
                    for (Detection d : raw) d.meters = latestDepth.sampleMedian(d.left, d.top, d.right, d.bottom);
                    DepthTfliteEstimator.WallResult wall = latestDepth.detectWall();
                    wallRaw = wall.rawWall;
                    wallMeters = wall.meanMeters;
                }
            }

            AnnouncementPlanner.Plan plan = planner.plan(raw, w, h, wallRaw, wallMeters,
                    SystemClock.elapsedRealtime(), settings.planner(), true);
            long ms = SystemClock.elapsedRealtime() - started;
            avgMillis = avgMillis == 0 ? ms : 0.8f * avgMillis + 0.2f * ms;
            consecutiveFailures = 0;
            listener.onResult(new Result(plan, w, h, depthOk, ms));
        } catch (RuntimeException ex) {
            if (++consecutiveFailures >= 5) {
                yoloState = ModelState.FAILED;
                detail = "Detector stopped: " + message(ex);
                publish();
            }
        }
    }

    // ---- reading text -------------------------------------------------------------------------------------------
    private void runOcr(Bitmap frame) {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(InputImage.fromBitmap(frame, 0))
                .addOnSuccessListener(text -> {
                    frame.recycle();
                    listener.onOcrText(text.getText() == null ? "" : text.getText().trim());
                })
                .addOnFailureListener(error -> {
                    frame.recycle();
                    listener.onOcrText("");
                });
    }

    void close() {
        worker.shutdownNow();
        loader.shutdownNow();
        YoloTfliteDetector y = yolo;
        DepthTfliteEstimator d = depth;
        yolo = null;
        depth = null;
        if (y != null) y.close();
        if (d != null) d.close();
        if (recognizer != null) recognizer.close();
    }
}
