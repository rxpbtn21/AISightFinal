package com.example.aisightmobile;

import android.content.Context;
import android.os.SystemClock;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the CameraX session: Preview + ImageAnalysis (+ VideoCapture when the phone can do three at once).
 *
 * THE FIX for the camera-disconnected spam lives in how frames are handled here:
 *   the analyzer callback does exactly two things, stamp the heartbeat and hand the frame over. The handoff
 *   (AnalysisPipeline.offer) closes the frame immediately unless its worker is free, so this callback can't be starved
 *   by slow YOLO/depth work, and CameraHealth only ever sees "are frames arriving?".
 */
final class CameraController {
    /** Must ALWAYS close the proxy, and quickly. */
    interface FrameSink { void onFrame(ImageProxy proxy, long nowMs); }

    private final Context context;
    private final LifecycleOwner owner;
    private final PreviewView previewView;
    private final CameraHealth health;
    private final VideoRecorder recorder;
    private final FrameSink sink;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "aisight-camera"));

    private ProcessCameraProvider provider;
    private LiveData<CameraState> observedState;
    private final Observer<CameraState> stateObserver = this::onCameraState;
    private boolean requested;

    CameraController(Context context, LifecycleOwner owner, PreviewView previewView, CameraHealth health,
                     VideoRecorder recorder, FrameSink sink) {
        this.context = context.getApplicationContext();
        this.owner = owner;
        this.previewView = previewView;
        this.health = health;
        this.recorder = recorder;
        this.sink = sink;
    }

    /** (Re)binds the camera. Safe to call repeatedly; main thread only. */
    void bind() {
        requested = true;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            try {
                provider = future.get();
                bindUseCases();
            } catch (ExecutionException | InterruptedException | RuntimeException ex) {
                health.onCameraError("Camera could not start: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()),
                        SystemClock.elapsedRealtime());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    void unbind() {
        requested = false;
        recorder.setBound(false);
        detachStateObserver();
        if (provider != null) provider.unbindAll();
    }

    private void bindUseCases() {
        if (!requested || provider == null) return;
        detachStateObserver();
        provider.unbindAll();
        health.onBindStarted(SystemClock.elapsedRealtime());

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, proxy -> {
            long now = SystemClock.elapsedRealtime();
            health.onFrame(now);          // the heartbeat: first, and cheap
            sink.onFrame(proxy, now);
        });

        Camera camera;
        boolean withVideo = true;
        try {
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, recorder.useCase());
        } catch (IllegalArgumentException | IllegalStateException threeUseCases) {
            // many phones can't run preview + analysis + video together: keep the camera, lose recording
            withVideo = false;
            provider.unbindAll();
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
        }
        recorder.setBound(withVideo);

        observedState = camera.getCameraInfo().getCameraState();
        observedState.observe(owner, stateObserver);
    }

    private void detachStateObserver() {
        if (observedState != null) {
            observedState.removeObserver(stateObserver);
            observedState = null;
        }
    }

    /** CameraX tells us when another app has the camera, it's disabled, etc. Recoverable hiccups are ignored. */
    private void onCameraState(CameraState state) {
        CameraState.StateError error = state.getError();
        String message = null;
        if (error != null) {
            switch (error.getCode()) {
                case CameraState.ERROR_CAMERA_IN_USE: message = "Camera is in use by another app"; break;
                case CameraState.ERROR_MAX_CAMERAS_IN_USE: message = "Too many cameras are open"; break;
                case CameraState.ERROR_CAMERA_DISABLED: message = "Camera is disabled on this phone"; break;
                case CameraState.ERROR_CAMERA_FATAL_ERROR: message = "Camera hardware error"; break;
                case CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED: message = "Camera is blocked by Do Not Disturb"; break;
                default: break;           // recoverable: CameraX retries by itself; a real outage shows up as "no frames"
            }
        }
        health.onCameraError(message, SystemClock.elapsedRealtime());
    }

    void release() {
        unbind();
        analysisExecutor.shutdown();
    }
}
