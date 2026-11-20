package com.example.aisightmobile;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.Locale;

/**
 * Screen + wiring only. The real work lives in: CameraController / CameraHealth (camera), AnalysisPipeline (AI),
 * AnnouncementPlanner (what to say), SpeechOutput, WakeWordListener + VoiceCommandParser (voice), AlarmController,
 * FallDetector, VideoRecorder, BatteryMonitor, SettingsSheet.
 */
public final class MainActivity extends ComponentActivity {

    // ---- the pieces ---------------------------------------------------------------------------------------------
    private AppSettings settings;
    private final AnnouncementPlanner planner = new AnnouncementPlanner();
    private SpeechOutput speech;
    private AlarmController alarm;
    private FallDetector fall;
    private BatteryMonitor battery;
    private VideoRecorder recorder;
    private CameraHealth health;
    private AnalysisPipeline pipeline;
    private CameraController camera;
    private WakeWordListener voice;
    private SettingsSheet settingsSheet;

    // ---- views --------------------------------------------------------------------------------------------------
    private FrameLayout root, previewCard, settingsLayer, helpLayer, wearableLayer;
    private LinearLayout mainColumn, permissionLayer, alarmBanner, loadingPanel, btnPrimary, settingsBody;
    private PreviewView preview;
    private DetectionOverlayView overlay;
    private TextView chipCamera, chipAi, chipVoice, bannerCamera, alarmText, navChip, loadingText;
    private TextView nowLabel, nowText, nowMeta, btnPrimaryText, btnRecordText, btnAllow, btnOpenSettings, permText;
    private TextView wearableText, helpText;
    private ImageView btnPrimaryIcon;

    // ---- state --------------------------------------------------------------------------------------------------
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean hasCamera, hasMic, started, welcomed, firstStartSpoken;
    private boolean awaitingShutdown;
    private long shutdownDeadline, lastUnknownAt, lastMicAlertAt, ticks;
    private boolean micFaulted, micFaultAnnounced, filipinoVoiceMissing;
    private AnalysisPipeline.ModelState yoloState = AnalysisPipeline.ModelState.LOADING;
    private AnalysisPipeline.ModelState depthState = AnalysisPipeline.ModelState.LOADING;
    private String modelDetail = "";
    private WakeWordListener.Status voiceStatus = WakeWordListener.Status.LOADING;
    private boolean lastDepthOk = true;

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                getSharedPreferences("aisight_flags", MODE_PRIVATE).edit().putBoolean("asked", true).apply();
                refreshPermissions();
            });

    // ==============================================================================================================
    // lifecycle
    // ==============================================================================================================
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        settings = new AppSettings(this);
        bindViews();
        applyInsets();
        roundPreviewCard();

        speech = new SpeechOutput(this, settings, speechListener);
        alarm = new AlarmController(this, (active, why) -> runOnUiThread(() -> showAlarmBanner(active, why)));
        fall = new FallDetector(this, this::onFallDetected);
        battery = new BatteryMonitor(this);
        recorder = new VideoRecorder(this, recorderListener);
        health = new CameraHealth(healthListener);
        pipeline = new AnalysisPipeline(this, settings, planner, pipelineListener);
        camera = new CameraController(this, this, preview, health, recorder, pipeline);
        voice = new WakeWordListener(this, voiceCallback);
        settingsSheet = new SettingsSheet(this, settingsBody, settings, settingsHost);

        wireClicks();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (settingsLayer.getVisibility() == View.VISIBLE) closeSheet(settingsLayer);
                else if (helpLayer.getVisibility() == View.VISIBLE) closeSheet(helpLayer);
                else if (wearableLayer.getVisibility() == View.VISIBLE) setWearable(false, true);
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });

        helpText.setText(HELP);
        if (settings.wearable) setWearable(true, false);
        pipeline.loadModelsAsync();
        refreshAll();
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        long now = SystemClock.elapsedRealtime();
        health.onVisibility(true, now);
        if (settings.fallDetection) fall.start(); else fall.stop();
        refreshPermissions();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override protected void onStop() {
        started = false;
        ui.removeCallbacks(tick);
        health.onVisibility(false, SystemClock.elapsedRealtime());
        recorder.stop();
        camera.unbind();
        voice.stop();
        fall.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        pipeline.close();
        camera.release();
        speech.shutdown();
        alarm.stop();
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            health.tick(now);
            overlay.clearIfStale(now);
            if (++ticks % 60 == 0) {
                String warning = battery.warning(settings.filipino);
                if (warning != null) speech.sayPriority(warning);
            }
            btnRecordText.setText(recorder.isBusy() ? "Stop recording" : "Record 10s");
            refreshChips();
            ui.postDelayed(this, 1000);
        }
    };

    // ==============================================================================================================
    // setup
    // ==============================================================================================================
    private void bindViews() {
        root = findViewById(R.id.root);
        mainColumn = findViewById(R.id.main_column);
        previewCard = findViewById(R.id.preview_card);
        preview = findViewById(R.id.preview);
        overlay = findViewById(R.id.overlay);
        chipCamera = findViewById(R.id.chip_camera);
        chipAi = findViewById(R.id.chip_ai);
        chipVoice = findViewById(R.id.chip_voice);
        bannerCamera = findViewById(R.id.banner_camera);
        alarmBanner = findViewById(R.id.alarm_banner);
        alarmText = findViewById(R.id.alarm_text);
        navChip = findViewById(R.id.nav_chip);
        loadingPanel = findViewById(R.id.loading_panel);
        loadingText = findViewById(R.id.loading_text);
        nowLabel = findViewById(R.id.now_label);
        nowText = findViewById(R.id.now_text);
        nowMeta = findViewById(R.id.now_meta);
        btnPrimary = findViewById(R.id.btn_primary);
        btnPrimaryIcon = findViewById(R.id.btn_primary_icon);
        btnPrimaryText = findViewById(R.id.btn_primary_text);
        btnRecordText = findViewById(R.id.btn_record_text);
        settingsLayer = findViewById(R.id.settings_layer);
        settingsBody = findViewById(R.id.settings_body);
        helpLayer = findViewById(R.id.help_layer);
        helpText = findViewById(R.id.help_text);
        wearableLayer = findViewById(R.id.wearable_layer);
        wearableText = findViewById(R.id.wearable_text);
        permissionLayer = findViewById(R.id.permission_layer);
        permText = findViewById(R.id.perm_text);
        btnAllow = findViewById(R.id.btn_allow);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Edge-to-edge: draw behind the system bars, then pad the content by their size. */
    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets b = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            mainColumn.setPadding(b.left, b.top, b.right, b.bottom);
            permissionLayer.setPadding(dp(28) + b.left, dp(28) + b.top, dp(28) + b.right, dp(28) + b.bottom);
            for (int panelId : new int[]{R.id.settings_panel, R.id.help_panel}) {
                View panel = findViewById(panelId);
                ((ViewGroup.MarginLayoutParams) panel.getLayoutParams()).topMargin = dp(56) + b.top;
                panel.setPadding(0, 0, 0, b.bottom);
                panel.requestLayout();
            }
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void roundPreviewCard() {
        previewCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
            }
        });
        previewCard.setClipToOutline(true);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);   // needed for the rounded clip
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
    }

    private void wireClicks() {
        btnPrimary.setOnClickListener(v -> {
            if (settings.armed) setArmed(false, true); else setArmed(true, true);
        });
        findViewById(R.id.btn_read).setOnClickListener(v -> readText());
        findViewById(R.id.btn_record).setOnClickListener(v -> {
            if (recorder.isBusy()) { recorder.stop(); } else { startRecording(10, false); }
        });
        findViewById(R.id.btn_wearable).setOnClickListener(v -> setWearable(true, true));
        findViewById(R.id.btn_settings).setOnClickListener(v -> openSettings());
        findViewById(R.id.btn_silence).setOnClickListener(v -> silenceAlarm());
        findViewById(R.id.btn_settings_close).setOnClickListener(v -> closeSheet(settingsLayer));
        findViewById(R.id.settings_scrim).setOnClickListener(v -> closeSheet(settingsLayer));
        findViewById(R.id.btn_help_close).setOnClickListener(v -> closeSheet(helpLayer));
        findViewById(R.id.help_scrim).setOnClickListener(v -> closeSheet(helpLayer));
        btnAllow.setOnClickListener(v -> permissionLauncher.launch(
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}));
        btnOpenSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
        loadingPanel.setOnClickListener(v -> {
            if (yoloState == AnalysisPipeline.ModelState.FAILED) pipeline.loadModelsAsync();
        });
        wearableLayer.setOnLongClickListener(v -> { setWearable(false, true); return true; });
        chipCamera.setOnClickListener(v -> toast(cameraDetail()));
        chipAi.setOnClickListener(v -> toast(aiDetail()));
        chipVoice.setOnClickListener(v -> toast(voiceDetail()));
    }

    // ==============================================================================================================
    // permissions
    // ==============================================================================================================
    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPermissions() {
        hasCamera = granted(Manifest.permission.CAMERA);
        hasMic = granted(Manifest.permission.RECORD_AUDIO);
        boolean asked = getSharedPreferences("aisight_flags", MODE_PRIVATE).getBoolean("asked", false);
        boolean blocked = !hasCamera && asked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);

        permissionLayer.setVisibility(hasCamera ? View.GONE : View.VISIBLE);
        btnOpenSettings.setVisibility(blocked ? View.VISIBLE : View.GONE);
        if (blocked) {
            permText.setText("Camera access is turned off for AISight.\n\nOpen the phone settings, tap Permissions, and allow Camera (and Microphone for voice commands).");
        }
        if (started && hasCamera) camera.bind();
        if (started && hasMic) voice.start();
        if (hasCamera) maybeWelcome();
        refreshAll();
    }

    private void maybeWelcome() {
        if (welcomed || !speech.isReady() || !hasCamera) return;
        welcomed = true;
        if (settings.armed) {
            speech.sayPriority("AISight is online.");
            firstStartSpoken = true;
        } else {
            speech.sayPriority("Application is now active. Say start, turn on, begin, or launch, to begin.");
        }
    }

    // ==============================================================================================================
    // the listeners
    // ==============================================================================================================
    private final SpeechOutput.Listener speechListener = new SpeechOutput.Listener() {
        @Override public void onReady(boolean ok) { runOnUiThread(() -> { maybeWelcome(); refreshAll(); }); }
        @Override public void onLanguageMissing(boolean filipino) {
            runOnUiThread(() -> {
                filipinoVoiceMissing = filipino;
                toast(filipino ? "No Filipino voice on this phone: using English. See Settings."
                        : "No English voice found on this phone.");
            });
        }
        @Override public void onVoiceError(String message) {
            runOnUiThread(() -> toast(message));
        }
    };

    private final CameraHealth.Listener healthListener = new CameraHealth.Listener() {
        @Override public void onFaultAnnounced(String reason) {
            alarm.start(5000, "Camera problem");
            speech.sayPriority(settings.filipino ? "Babala. Nawala ang camera." : "Warning. Camera disconnected.");
        }
        @Override public void onRestoredAnnounced() {
            speech.sayPriority(settings.filipino ? "Bumalik na ang camera." : "Camera restored.");
        }
        @Override public void onRebindRequested(int attempt) { if (started && hasCamera) camera.bind(); }
        @Override public void onStateChanged(CameraHealth.State state, String detail) {
            if (state == CameraHealth.State.FAULT) {
                bannerCamera.setText("Camera problem: " + detail + ".\nReconnecting...");
                bannerCamera.setVisibility(View.VISIBLE);
            } else {
                bannerCamera.setVisibility(View.GONE);
            }
            refreshChips();
        }
    };

    private final AnalysisPipeline.Listener pipelineListener = new AnalysisPipeline.Listener() {
        @Override public void onResult(AnalysisPipeline.Result r) { runOnUiThread(() -> handleResult(r)); }

        @Override public void onOcrText(String text) {
            runOnUiThread(() -> {
                if (text.isEmpty()) {
                    nowLabel.setText("TEXT");
                    nowText.setText("No text found");
                    speech.sayPriority(settings.filipino ? "Walang nakitang teksto." : "No text found.");
                } else {
                    nowLabel.setText("TEXT");
                    nowText.setText(text);
                    String spoken = text.length() > 700 ? text.substring(0, 700) : text;
                    speech.sayPriority((settings.filipino ? "Nakasulat: " : "Text reads: ") + spoken);
                }
            });
        }

        @Override public void onModelStatus(AnalysisPipeline.ModelState yolo, AnalysisPipeline.ModelState depth, String detail) {
            runOnUiThread(() -> {
                yoloState = yolo;
                depthState = depth;
                modelDetail = detail;
                refreshAll();
            });
        }
    };

    private final WakeWordListener.Callback voiceCallback = new WakeWordListener.Callback() {
        @Override public void onHeard(String text) { runOnUiThread(() -> handleHeard(text)); }
        @Override public void onStatus(WakeWordListener.Status status, String detail) {
            runOnUiThread(() -> { voiceStatus = status; refreshChips(); });
        }
        @Override public void onMicFault(String reason) {
            runOnUiThread(() -> {
                micFaulted = true;
                long now = SystemClock.elapsedRealtime();
                if (now - lastMicAlertAt > 60_000 || lastMicAlertAt == 0) {     // at most once a minute, like the camera
                    lastMicAlertAt = now;
                    micFaultAnnounced = true;
                    alarm.start(5000, "Microphone problem");
                    speech.sayPriority(settings.filipino ? "Babala. Nawala ang mikropono." : "Warning. Microphone disconnected.");
                }
                refreshChips();
            });
        }
        @Override public void onMicRestored() {
            runOnUiThread(() -> {
                micFaulted = false;
                if (micFaultAnnounced) {
                    micFaultAnnounced = false;
                    speech.sayPriority(settings.filipino ? "Bumalik na ang mikropono." : "Microphone restored.");
                }
                refreshChips();
            });
        }
    };

    private final VideoRecorder.Listener recorderListener = new VideoRecorder.Listener() {
        @Override public void onStarted(int seconds) { btnRecordText.setText("Stop recording"); }
        @Override public void onSaved(String where) {
            speech.sayPriority("Recording saved.");
            toast("Saved in " + where);
        }
        @Override public void onFailed(String why) {
            speech.sayPriority("Recording failed.");
            toast("Recording failed: " + why);
        }
    };

    private final SettingsSheet.Host settingsHost = new SettingsSheet.Host() {
        @Override public void onSettingChanged(boolean speechChanged) {
            if (speechChanged) speech.configure();
            settingsSheet.setNotices(failureNotice(), filipinoVoiceMissing,
                    speech == null ? null : speech.getVoiceStatus());
            settingsSheet.rebuild();
            planner.unmarkSpoken();
            refreshAll();
        }
        @Override public void onOpenHelp() { closeSheet(settingsLayer); openSheet(helpLayer); }
        @Override public void onTestAlarm() { alarm.start(3000, "Alarm test"); }
        @Override public void onRetryModels() { pipeline.loadModelsAsync(); closeSheet(settingsLayer); }
        @Override public void onInstallVoice() {
            try {
                startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
            } catch (RuntimeException ex) {
                toast("Open your phone's Text-to-speech settings to install a Filipino voice.");
            }
        }
        @Override public void onPreviewVoice() {
            speech.sayPriority(settings.filipino
                    ? "Ito ang pagsubok ng boses ng AISight."
                    : "This is the AISight voice preview.");
        }
        @Override public void onWearableToggled(boolean on) { closeSheet(settingsLayer); setWearable(on, true); }
    };

    // ==============================================================================================================
    // frames -> screen + speech
    // ==============================================================================================================
    private void handleResult(AnalysisPipeline.Result r) {
        lastDepthOk = r.depthOk;
        overlay.setResult(r.plan.shown, r.frameW, r.frameH);

        boolean nav = "navigation".equals(settings.phraseMode);
        if (nav && r.plan.advice != null && !r.plan.spoken.isEmpty()) {
            navChip.setText(Lexicon.term(r.plan.advice, settings.filipino).toUpperCase(Locale.getDefault()));
            navChip.setVisibility(View.VISIBLE);
        } else {
            navChip.setVisibility(View.GONE);
        }

        if (r.plan.speak) {
            if (speech.say(r.plan.message)) {
                nowLabel.setText("LAST ANNOUNCEMENT");
                nowText.setText(r.plan.message);
            } else {
                planner.unmarkSpoken();           // the voice was busy: offer it again next frame instead of losing it
            }
        } else if (!settings.armed) {
            nowLabel.setText("WHAT I SEE (SILENT)");
            nowText.setText(r.plan.message.isEmpty() ? "Nothing detected" : r.plan.message);
        }
    }

    // ==============================================================================================================
    // voice commands
    // ==============================================================================================================
    private void handleHeard(String heard) {
        String after = VoiceCommandParser.afterWake(heard);
        if (after == null) return;
        long now = SystemClock.elapsedRealtime();
        if (awaitingShutdown && now > shutdownDeadline) awaitingShutdown = false;
        execute(VoiceCommandParser.parse(after, awaitingShutdown));
    }

    private void say(String text) { speech.sayPriority(text); }

    private void execute(VoiceCommandParser.Command c) {
        boolean fil = settings.filipino;
        switch (c.type) {
            case WAKE_ONLY: say("Yes?"); break;
            case START: setArmed(true, true); break;
            case QUIET: setArmed(false, true); break;
            case ALARM_STOP: silenceAlarm(); say("Alarm silenced."); break;
            case RECORD: startRecording(c.n, c.flag); break;
            case RECORD_STOP:
                if (recorder.isBusy()) { recorder.stop(); say("Stopping the recording."); } else say("Not recording.");
                break;
            case READ_TEXT: readText(); break;
            case PAUSE_CLASS:
                planner.pauseClass(c.arg, SystemClock.elapsedRealtime() + c.n * 60_000L);
                say("Pausing " + Lexicon.spokenLabel(c.arg, false) + " for " + c.n + (c.n == 1 ? " minute." : " minutes."));
                break;
            case RESUME_CLASS:
                say(planner.resumeClass(c.arg) ? Lexicon.spokenLabel(c.arg, false) + " announcements resumed."
                        : Lexicon.spokenLabel(c.arg, false) + " was not paused.");
                break;
            case BATTERY: say(battery.phrase(fil)); break;
            case LANGUAGE:
                settings.filipino = "fil".equals(c.arg);
                settings.save();
                speech.configure();
                say(settings.filipino ? "Switching to Filipino. Papalitan sa Tagalog." : "Switching to English.");
                refreshAll();
                break;
            case MODE:
                settings.phraseMode = c.arg;
                settings.save();
                planner.unmarkSpoken();
                say("navigation".equals(c.arg) ? "Navigation mode." : "advanced".equals(c.arg) ? "Advanced mode." : "Default mode.");
                refreshAll();
                break;
            case GENDER:
                settings.gender = c.arg;
                settings.save();
                speech.configure();
                say("Switching to " + c.arg + " voice.");
                break;
            case SPEED:
                settings.speed = c.arg;
                settings.save();
                speech.configure();
                say("Speed set to " + c.arg + ".");
                break;
            case VOLUME:
                settings.volume = c.arg;
                settings.save();
                say("Volume " + c.arg + ".");
                break;
            case MAX_OBJECTS:
                settings.maxSpeak = c.n;
                settings.save();
                say("Announcing " + (c.n == 1 ? "one object" : c.n + " objects") + " at a time.");
                refreshAll();
                break;
            case REPEAT: {
                String last = planner.lastAnnouncement();
                say(last.isEmpty() ? "Nothing to repeat yet." : last);
                break;
            }
            case HELP: say("Showing the voice commands."); openSheet(helpLayer); break;
            case WEARABLE: setWearable("on".equals(c.arg), true); break;
            case SHUTDOWN:
                awaitingShutdown = true;
                shutdownDeadline = SystemClock.elapsedRealtime() + 15_000;
                say("Are you sure you want to close the app? Say yes, or no.");
                break;
            case YES:
                awaitingShutdown = false;
                say("Closing AISight. Goodbye.");
                ui.postDelayed(this::finishAndRemoveTask, 2200);
                break;
            case NO: awaitingShutdown = false; say("Okay, staying open."); break;
            case UNKNOWN:
            default: {
                long now = SystemClock.elapsedRealtime();
                if (awaitingShutdown) say("Please say yes, or no.");
                else if (now - lastUnknownAt > 10_000) { lastUnknownAt = now; say("Sorry, I didn't catch that."); }
                break;
            }
        }
    }

    // ==============================================================================================================
    // actions
    // ==============================================================================================================
    private void setArmed(boolean on, boolean speak) {
        settings.armed = on;
        planner.unmarkSpoken();
        if (speak) {
            if (on) {
                if (!firstStartSpoken) {
                    firstStartSpoken = true;
                    say("AISight is now activated. Always say eyesight first, then a command, any time.");
                } else say("Resuming.");
            } else {
                say("Going quiet. Say start to resume.");
            }
        }
        refreshAll();
    }

    private void readText() {
        if (!hasCamera) return;
        pipeline.requestOcr();
        say(settings.filipino ? "Binabasa ang teksto." : "Reading text now.");
    }

    private void startRecording(int seconds, boolean capped) {
        if (!recorder.isSupported()) { say("Recording isn't supported on this phone while the AI is running."); return; }
        if (recorder.isBusy()) { say("Already recording."); return; }
        String words = seconds == 60 ? "one minute" : seconds + " seconds";
        if (recorder.start(seconds)) say((capped ? "The limit is one minute. " : "") + "Recording " + words + ".");
    }

    private final Runnable startFallAlarm = () -> alarm.start(30_000, "Fall detected");

    private void onFallDetected() {
        runOnUiThread(() -> {
            showAlarmBanner(true, "Fall detected");
            speech.sayPriority(settings.filipino
                    ? "Nahulog ka ba? Sabihin ang alarm stop para patahimikin."
                    : "Fall detected. Are you okay? Say alarm stop to silence this.");
            ui.removeCallbacks(startFallAlarm);
            ui.postDelayed(startFallAlarm, 4500);     // the spoken question first, THEN the alarm (like the Pi build)
        });
    }

    private void silenceAlarm() {
        ui.removeCallbacks(startFallAlarm);
        alarm.stop();
        showAlarmBanner(false, "");
    }

    private void showAlarmBanner(boolean show, String why) {
        alarmBanner.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) alarmText.setText("ALARM: " + why);
    }

    // ---- wearable mode ------------------------------------------------------------------------------------------
    private void setWearable(boolean on, boolean speak) {
        settings.wearable = on;
        settings.save();
        wearableLayer.setVisibility(on ? View.VISIBLE : View.GONE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = on ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), root);
        if (on) controller.hide(WindowInsetsCompat.Type.systemBars()); else controller.show(WindowInsetsCompat.Type.systemBars());
        wearableText.setText("AISight is running\nPress and hold to show the screen");
        if (speak) {
            say(on ? "Wearable mode. The screen is off. Say show screen to bring it back."
                    : "Screen on.");
        }
    }

    /** Volume buttons as a physical backup for voice, in wearable mode. */
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (settings.wearable && settings.volumeKeys
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.getRepeatCount() == 0) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    if (alarm.isActive()) { silenceAlarm(); say("Alarm silenced."); }
                    else setArmed(!settings.armed, true);
                } else {
                    String last = planner.lastAnnouncement();
                    say(last.isEmpty() ? "Nothing to repeat yet." : last);
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (settings.wearable && settings.volumeKeys
                && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true;
        return super.onKeyUp(keyCode, event);
    }

    // ---- sheets -------------------------------------------------------------------------------------------------
    private void openSettings() {
        settingsSheet.setNotices(failureNotice(), filipinoVoiceMissing, speech == null ? null : speech.getVoiceStatus());
        settingsSheet.rebuild();
        openSheet(settingsLayer);
    }

    private void openSheet(View layer) {
        layer.setAlpha(0f);
        layer.setVisibility(View.VISIBLE);
        layer.animate().alpha(1f).setDuration(160).start();
    }

    private void closeSheet(View layer) {
        layer.animate().alpha(0f).setDuration(140).withEndAction(() -> layer.setVisibility(View.GONE)).start();
    }

    // ==============================================================================================================
    // status
    // ==============================================================================================================
    private String failureNotice() {
        if (yoloState == AnalysisPipeline.ModelState.FAILED) return "The AI couldn't load. " + modelDetail;
        return null;
    }

    private void refreshAll() {
        refreshChips();
        boolean armed = settings.armed;
        btnPrimaryText.setText(armed ? "PAUSE ANNOUNCEMENTS" : "START ANNOUNCING");
        btnPrimaryIcon.setImageResource(armed ? R.drawable.ic_pause : R.drawable.ic_play);
        int ink = ContextCompat.getColor(this, armed ? R.color.accent : R.color.on_accent);
        btnPrimaryIcon.setColorFilter(ink);
        btnPrimaryText.setTextColor(ink);
        btnPrimary.setBackgroundResource(armed ? R.drawable.bg_primary_button_on : R.drawable.bg_primary_button);

        String mode = "navigation".equals(settings.phraseMode) ? "Navigation"
                : "advanced".equals(settings.phraseMode) ? "Detailed" : "Simple";
        nowMeta.setText(mode + "  ·  " + (settings.filipino ? "Filipino" : "English") + "  ·  "
                + settings.maxSpeak + (settings.maxSpeak == 1 ? " object" : " objects"));

        if (yoloState == AnalysisPipeline.ModelState.READY) {
            loadingPanel.setVisibility(View.GONE);
        } else {
            loadingPanel.setVisibility(View.VISIBLE);
            loadingText.setText(yoloState == AnalysisPipeline.ModelState.FAILED
                    ? "The AI couldn't load\n" + modelDetail + "\nTap to try again"
                    : "Getting the AI online...\nThe first launch takes a little longer");
        }
    }

    private void setChip(TextView chip, String text, int colorRes, String description) {
        chip.setText(text);
        chip.setContentDescription(description);
        Drawable[] d = chip.getCompoundDrawablesRelative();
        if (d[0] != null) d[0].mutate().setTint(ContextCompat.getColor(this, colorRes));
    }

    private String cameraDetail() {
        if (!hasCamera) return "Camera permission is off.";
        switch (health.state()) {
            case HEALTHY: return "Camera is working.";
            case FAULT: return "Camera problem. Reconnecting automatically.";
            default: return "Camera is starting.";
        }
    }

    private String aiDetail() {
        if (yoloState == AnalysisPipeline.ModelState.FAILED) return "AI failed to load: " + modelDetail;
        if (yoloState == AnalysisPipeline.ModelState.LOADING) return "AI is loading.";
        if (depthState == AnalysisPipeline.ModelState.FAILED) return "Objects work, but distances are unavailable. " + modelDetail;
        if (pipeline.averageMillis() > 1500) return "This phone is busy: each frame takes " + Math.round(pipeline.averageMillis()) + " ms.";
        return "AI is working (" + Math.round(pipeline.averageMillis()) + " ms per frame).";
    }

    private String voiceDetail() {
        if (!hasMic) return "Microphone permission is off, so voice commands are off.";
        if (micFaulted) return "Microphone problem. Retrying.";
        if (voiceStatus == WakeWordListener.Status.FAILED) return "Voice recognition couldn't start.";
        if (voiceStatus == WakeWordListener.Status.LOADING) return "Voice recognition is loading.";
        return "Listening. Say “eyesight”, then a command.";
    }

    private void refreshChips() {
        // camera
        if (!hasCamera) setChip(chipCamera, "Camera off", R.color.danger, cameraDetail());
        else switch (health.state()) {
            case HEALTHY: setChip(chipCamera, "Camera online", R.color.ok, cameraDetail()); break;
            case FAULT: setChip(chipCamera, "Camera lost", R.color.danger, cameraDetail()); break;
            default: setChip(chipCamera, "Camera starting", R.color.warn, cameraDetail());
        }
        // AI
        if (yoloState == AnalysisPipeline.ModelState.FAILED) setChip(chipAi, "AI failed", R.color.danger, aiDetail());
        else if (yoloState == AnalysisPipeline.ModelState.LOADING) setChip(chipAi, "AI loading", R.color.warn, aiDetail());
        else if (depthState == AnalysisPipeline.ModelState.FAILED || !lastDepthOk && depthState == AnalysisPipeline.ModelState.READY)
            setChip(chipAi, "No distance", R.color.warn, aiDetail());
        else if (pipeline.averageMillis() > 1500) setChip(chipAi, "AI slow", R.color.warn, aiDetail());
        else setChip(chipAi, "AI online", R.color.ok, aiDetail());
        // voice
        if (!hasMic) setChip(chipVoice, "Voice off", R.color.danger, voiceDetail());
        else if (micFaulted) setChip(chipVoice, "Mic lost", R.color.danger, voiceDetail());
        else if (voiceStatus == WakeWordListener.Status.FAILED) setChip(chipVoice, "Voice off", R.color.danger, voiceDetail());
        else if (voiceStatus == WakeWordListener.Status.LOADING) setChip(chipVoice, "Voice loading", R.color.warn, voiceDetail());
        else setChip(chipVoice, "Voice on", R.color.ok, voiceDetail());
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    // ==============================================================================================================
    private static final String HELP =
            "Say “eyesight” first, then:\n\n"
            + "START AND STOP\n"
            + "•  start, begin, launch, turn on\n      begin announcing\n"
            + "•  stop, pause, quiet\n      go quiet\n"
            + "•  pause person five minutes\n      silence one kind of thing (up to 5 minutes)\n"
            + "•  resume person\n      bring it back\n\n"
            + "LOOK\n"
            + "•  read text\n      read what the camera sees\n"
            + "•  record ten seconds\n      also five, thirty, sixty seconds, or one minute\n"
            + "•  stop recording\n"
            + "•  repeat   or   say again\n      hear the last announcement\n\n"
            + "STYLE\n"
            + "•  default  /  advance  /  navigation mode\n"
            + "•  object detection one, two or three\n"
            + "•  switch to tagalog  /  switch to english\n"
            + "•  male  /  female\n"
            + "•  fast  /  slow  /  normal\n"
            + "•  volume high  /  normal  /  low\n\n"
            + "SAFETY\n"
            + "•  alarm stop\n      silence the alarm\n"
            + "•  battery\n      phone battery level\n\n"
            + "SCREEN\n"
            + "•  wearable mode\n      black screen, keeps working\n"
            + "•  show screen\n      bring it back\n"
            + "•  shut down\n      close AISight (asks you to confirm)\n\n"
            + "In wearable mode the volume buttons also work:\n"
            + "volume down = pause or resume, volume up = repeat.";
}
