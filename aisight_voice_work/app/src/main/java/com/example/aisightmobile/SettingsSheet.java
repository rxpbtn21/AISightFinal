package com.example.aisightmobile;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/** Builds the settings screen rows in code (they are all the same few shapes: choice, switch, slider, button). */
final class SettingsSheet {
    interface Host {
        void onSettingChanged(boolean speechChanged);
        void onOpenHelp();
        void onTestAlarm();
        void onRetryModels();
        void onInstallVoice();
        void onPreviewVoice();
        void onWearableToggled(boolean on);
    }

    private interface OnSelect { void select(int index); }
    private interface OnToggle { void toggle(boolean on); }

    private final Context ctx;
    private final LinearLayout body;
    private final AppSettings s;
    private final Host host;
    private final float density;
    private String aiProblem = null;
    private boolean filipinoVoiceMissing = false;
    private String voiceStatus = null;

    SettingsSheet(Context ctx, LinearLayout body, AppSettings settings, Host host) {
        this.ctx = ctx;
        this.body = body;
        this.s = settings;
        this.host = host;
        this.density = ctx.getResources().getDisplayMetrics().density;
    }

    void setNotices(String aiProblemOrNull, boolean filipinoMissing, String currentVoiceStatus) {
        this.aiProblem = aiProblemOrNull;
        this.filipinoVoiceMissing = filipinoMissing;
        this.voiceStatus = currentVoiceStatus;
    }

    private int dp(float v) { return Math.round(v * density); }
    private int color(int res) { return ContextCompat.getColor(ctx, res); }

    /** Rebuild every row from the current settings (called each time the sheet opens). */
    void rebuild() {
        body.removeAllViews();

        if (aiProblem != null) {
            notice(aiProblem);
            button("Retry loading the AI models", host::onRetryModels);
        }

        section("Announcements");
        choice("Style", new String[]{"Simple", "Detailed", "Navigate"},
                indexOf(new String[]{"default", "advanced", "navigation"}, s.phraseMode), i -> {
                    s.phraseMode = new String[]{"default", "advanced", "navigation"}[i];
                    changed(false);
                });
        hint("Simple: object, how close, which side.   Detailed: object, side, meters.   "
                + "Navigate (experimental): adds where to step, only for things within reach.");
        choice("Things at once", new String[]{"1", "2", "3"}, s.maxSpeak - 1, i -> {
            s.maxSpeak = i + 1;
            changed(false);
        });
        choice("Language", new String[]{"English", "Filipino"}, s.filipino ? 1 : 0, i -> {
            s.filipino = i == 1;
            changed(true);
        });
        if (filipinoVoiceMissing && s.filipino && !"male".equals(s.gender)) {
            notice("Your phone has no Filipino voice installed, so English is used for now.");
            button("Install the Filipino voice", host::onInstallVoice);
        }

        section("Voice");
        choice("Voice", new String[]{"Female (phone)", "Male (AISight)"}, "male".equals(s.gender) ? 1 : 0, i -> {
            s.gender = i == 1 ? "male" : "female";
            changed(true);
        });
        hint("Male voice: built-in offline neural voices for English and Filipino. Female voice: an offline voice installed on the phone.");
        if (voiceStatus != null && !voiceStatus.isEmpty()) hint(voiceStatus);
        button("Preview selected voice", host::onPreviewVoice);
        choice("Speed", new String[]{"Slow", "Normal", "Fast"},
                indexOf(new String[]{"slow", "normal", "fast"}, s.speed), i -> {
                    s.speed = new String[]{"slow", "normal", "fast"}[i];
                    changed(true);
                });
        choice("Volume", new String[]{"Low", "Normal", "High"},
                indexOf(new String[]{"low", "normal", "high"}, s.volume), i -> {
                    s.volume = new String[]{"low", "normal", "high"}[i];
                    changed(true);
                });

        section("Detection");
        slider("How sure before it speaks", 25, 75, Math.round(s.confidence * 100), v -> {
            s.confidence = v / 100f;
            changed(false);
        });
        hint("Lower = notices more, but may be wrong more often.");

        section("Behaviour");
        toggle("Start announcing when the app opens", "Otherwise it stays silent until you say “eyesight start”.",
                s.autoStart, on -> { s.autoStart = on; changed(false); });
        toggle("Fall detection", "Sounds an alarm after a fall. Say “eyesight alarm stop” to silence it.",
                s.fallDetection, on -> { s.fallDetection = on; changed(false); });
        toggle("Wearable mode", "Black screen to save battery. Camera and voice keep working.",
                s.wearable, host::onWearableToggled);
        toggle("Volume buttons as shortcuts (wearable mode)", "Volume down: pause or resume. Volume up: repeat.",
                s.volumeKeys, on -> { s.volumeKeys = on; changed(false); });

        section("Help");
        button("What can I say? (voice commands)", host::onOpenHelp);
        button("Test the alarm", host::onTestAlarm);
    }

    private void changed(boolean speechChanged) {
        s.save();
        host.onSettingChanged(speechChanged);
    }

    // ---- row builders -------------------------------------------------------------------------------------------
    private void section(String title) {
        TextView t = new TextView(ctx);
        t.setText(title.toUpperCase());
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color(R.color.accent));
        t.setPadding(0, dp(24), 0, dp(6));
        body.addView(t);
    }

    private void hint(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(color(R.color.text_dim));
        t.setPadding(0, dp(2), 0, dp(4));
        body.addView(t);
    }

    private void notice(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(color(R.color.on_accent));
        t.setBackgroundResource(R.drawable.bg_banner_warn);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        body.addView(t, lp);
    }

    private void choice(String label, String[] options, int selected, OnSelect cb) {
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextSize(15);
        l.setTextColor(color(R.color.text));
        l.setPadding(0, dp(12), 0, dp(6));
        body.addView(l);

        final LinearLayout track = new LinearLayout(ctx);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundResource(R.drawable.bg_seg_track);
        track.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView seg = new TextView(ctx);
            seg.setText(options[i]);
            seg.setTextSize(14);
            seg.setGravity(Gravity.CENTER);
            seg.setClickable(true);
            seg.setFocusable(true);
            seg.setContentDescription(label + ": " + options[i]);
            seg.setOnClickListener(v -> {
                styleSegments(track, index);
                cb.select(index);
            });
            track.addView(seg, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        styleSegments(track, selected);
        body.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void styleSegments(LinearLayout track, int selected) {
        for (int i = 0; i < track.getChildCount(); i++) {
            TextView seg = (TextView) track.getChildAt(i);
            boolean on = i == selected;
            seg.setSelected(on);
            if (on) {
                seg.setBackgroundResource(R.drawable.bg_seg_selected);
                seg.setTextColor(color(R.color.on_accent));
                seg.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                seg.setBackground(null);
                seg.setTextColor(color(R.color.text));
                seg.setTypeface(Typeface.DEFAULT);
            }
        }
    }

    private void toggle(String label, String sub, boolean checked, OnToggle cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(4));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(color(R.color.text));
        texts.addView(t);
        TextView st = new TextView(ctx);
        st.setText(sub);
        st.setTextSize(12);
        st.setTextColor(color(R.color.text_dim));
        texts.addView(st);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        sw.setContentDescription(label);
        sw.setOnCheckedChangeListener((b, on) -> cb.toggle(on));
        row.addView(sw);
        body.addView(row);
    }

    private void slider(String label, int min, int max, int value, OnSelect cb) {
        final TextView l = new TextView(ctx);
        l.setTextSize(15);
        l.setTextColor(color(R.color.text));
        l.setPadding(0, dp(12), 0, dp(2));
        l.setText(label + ": " + value + "%");
        body.addView(l);
        SeekBar bar = new SeekBar(ctx);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setContentDescription(label);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                l.setText(label + ": " + (progress + min) + "%");
                if (fromUser) cb.select(progress + min);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        body.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
    }

    private void button(String text, Runnable action) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(color(R.color.text));
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.bg_secondary_button);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener((View v) -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(10);
        body.addView(b, lp);
    }

    private static int indexOf(String[] options, String value) {
        for (int i = 0; i < options.length; i++) if (options[i].equals(value)) return i;
        return 0;
    }
}
