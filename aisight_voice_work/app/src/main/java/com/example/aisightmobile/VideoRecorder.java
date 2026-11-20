package com.example.aisightmobile;

import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "eyesight record ten seconds": a silent video of what the camera sees, named by date and time.
 * Android 10+: saved in the gallery under Movies/AISight (no permission needed).
 * Older phones: the app's own Movies folder.
 */
final class VideoRecorder {
    interface Listener {
        void onStarted(int seconds);
        void onSaved(String where);
        void onFailed(String why);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VideoCapture<Recorder> videoCapture;
    private Recording active;
    private volatile boolean bound;

    VideoRecorder(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build();
        videoCapture = VideoCapture.withOutput(recorder);
    }

    VideoCapture<Recorder> useCase() { return videoCapture; }

    /** The camera controller says whether the video use case made it into the camera session. */
    void setBound(boolean isBound) {
        bound = isBound;
        if (!isBound) stop();
    }

    boolean isSupported() { return bound; }
    boolean isBusy() { return active != null; }

    boolean start(int seconds) {
        if (!bound || active != null) return false;
        String name = "AISight_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        try {
            PendingRecording pending;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AISight");
                MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                        context.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                        .setContentValues(values).build();
                pending = videoCapture.getOutput().prepareRecording(context, options);
            } else {
                File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "AISight");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                pending = videoCapture.getOutput().prepareRecording(context, new FileOutputOptions.Builder(new File(dir, name + ".mp4")).build());
            }
            final boolean legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
            active = pending.start(ContextCompat.getMainExecutor(context), event -> {
                if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) event;
                    active = null;
                    main.removeCallbacksAndMessages(null);
                    if (f.hasError() && f.getError() != VideoRecordEvent.Finalize.ERROR_NONE
                            && f.getError() != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) {
                        listener.onFailed("error " + f.getError());
                    } else {
                        listener.onSaved(legacy ? "the app's Movies folder" : "Movies/AISight in your gallery");
                    }
                }
            });
        } catch (RuntimeException ex) {
            active = null;
            listener.onFailed(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return false;
        }
        main.postDelayed(this::stop, seconds * 1000L);
        listener.onStarted(seconds);
        return true;
    }

    void stop() {
        main.removeCallbacksAndMessages(null);
        Recording r = active;
        if (r != null) r.stop();
    }
}
