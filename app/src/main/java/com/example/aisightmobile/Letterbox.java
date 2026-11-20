package com.example.aisightmobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Draws a frame into a fixed-size model input with grey bars (same look the model was trained with).
 * Buffers are allocated once and reused: the old code created 3 bitmaps and a Paint on every frame.
 */
final class Letterbox {
    final int width, height;
    private final Bitmap target;
    private final Canvas canvas;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] pixels;
    private final Rect dst = new Rect();

    float scale;        // frame pixel -> model pixel
    int resizedW, resizedH, padX, padY;
    private int frameW, frameH;

    Letterbox(int width, int height) {
        this.width = width;
        this.height = height;
        this.target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        this.canvas = new Canvas(target);
        this.pixels = new int[width * height];
    }

    /** @return packed ARGB pixels of the letterboxed model input (a shared array: valid until the next call). */
    int[] render(Bitmap frame) {
        frameW = frame.getWidth();
        frameH = frame.getHeight();
        scale = Math.min(width / (float) frameW, height / (float) frameH);
        resizedW = Math.max(1, Math.min(width, Math.round(frameW * scale)));
        resizedH = Math.max(1, Math.min(height, Math.round(frameH * scale)));
        padX = (width - resizedW) / 2;
        padY = (height - resizedH) / 2;

        canvas.drawColor(Color.rgb(114, 114, 114));
        dst.set(padX, padY, padX + resizedW, padY + resizedH);
        canvas.drawBitmap(frame, null, dst, paint);
        target.getPixels(pixels, 0, width, 0, 0, width, height);
        return pixels;
    }

    float scaleX() { return resizedW / (float) frameW; }
    float scaleY() { return resizedH / (float) frameH; }

    void release() { target.recycle(); }
}
