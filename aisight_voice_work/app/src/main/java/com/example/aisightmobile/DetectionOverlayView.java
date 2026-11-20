package com.example.aisightmobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Boxes over the camera preview, coloured by how dangerous the class is, with its name and distance. */
public final class DetectionOverlayView extends View {
    private static final long STALE_MS = 1500;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private final int high, medium, low, wall, ink;

    private List<Detection> detections = new ArrayList<>();
    private int frameW, frameH;
    private long updatedAt;

    public DetectionOverlayView(Context context) { this(context, null); }

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        high = ContextCompat.getColor(context, R.color.danger);
        medium = ContextCompat.getColor(context, R.color.warn);
        low = ContextCompat.getColor(context, R.color.accent);
        wall = ContextCompat.getColor(context, R.color.cream);
        ink = ContextCompat.getColor(context, R.color.on_accent);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f * density);
        fill.setStyle(Paint.Style.FILL);
        text.setTextSize(13f * density);
        text.setFakeBoldText(true);
        text.setColor(ink);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // the app speaks; TalkBack shouldn't read boxes
    }

    void setResult(List<Detection> shown, int w, int h) {
        detections = new ArrayList<>(shown);
        frameW = w;
        frameH = h;
        updatedAt = SystemClock.elapsedRealtime();
        invalidate();
    }

    /** Frames stopped coming (camera stalled / paused): don't leave old boxes hanging on the screen. */
    void clearIfStale(long nowMs) {
        if (!detections.isEmpty() && nowMs - updatedAt > STALE_MS) {
            detections = new ArrayList<>();
            invalidate();
        }
    }

    private int colorFor(Detection d) {
        if (Lexicon.WALL.equals(d.label)) return wall;
        switch (Lexicon.tier(d.label)) {
            case "high": return high;
            case "medium": return medium;
            default: return low;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frameW <= 0 || frameH <= 0) return;
        // the preview is FILL_CENTER: scaled to cover the view, centred, cropped
        float scale = Math.max(getWidth() / (float) frameW, getHeight() / (float) frameH);
        float dx = (getWidth() - frameW * scale) / 2f;
        float dy = (getHeight() - frameH * scale) / 2f;
        float pad = 6f * density;

        for (int i = detections.size() - 1; i >= 0; i--) {          // best first in the list -> drawn last (on top)
            Detection d = detections.get(i);
            int color = colorFor(d);
            rect.set(d.left * scale + dx, d.top * scale + dy, d.right * scale + dx, d.bottom * scale + dy);

            fill.setColor(color);
            fill.setAlpha(34);
            canvas.drawRoundRect(rect, 10f * density, 10f * density, fill);
            stroke.setColor(color);
            canvas.drawRoundRect(rect, 10f * density, 10f * density, stroke);

            String label = Lexicon.spokenLabel(d.label, false);
            if (d.hasDistance()) label += String.format(Locale.US, "  %.1f m", d.meters);
            float w = text.measureText(label) + pad * 2;
            float h = text.getTextSize() + pad * 1.4f;
            float left = Math.max(0f, Math.min(rect.left, getWidth() - w));
            float top = rect.top - h >= 0 ? rect.top - h : rect.top;      // above the box, or inside if there's no room
            labelBg.setColor(color);
            canvas.drawRoundRect(left, top, left + w, top + h, 8f * density, 8f * density, labelBg);
            canvas.drawText(label, left + pad, top + h - pad * 0.9f, text);
        }
    }
}
