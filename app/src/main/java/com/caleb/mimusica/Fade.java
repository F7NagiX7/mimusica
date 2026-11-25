package com.caleb.mimusica;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Fondo con degradado vertical: el color de arriba se funde al color de abajo
 * en la fracción "extent" de la altura. Se dibuja con un solo shader, sin bitmaps.
 */
final class Fade extends Drawable {
    private final Paint paint = new Paint();
    private final int bottom;
    private final float extent;
    private int top;

    Fade(int top, int bottom, float extent) {
        this.top = top;
        this.bottom = bottom;
        this.extent = extent;
    }

    void setTop(int c) {
        if (c == top) return;
        top = c;
        rebuild();
        invalidateSelf();
    }

    private void rebuild() {
        Rect b = getBounds();
        if (b.height() <= 0) {
            paint.setShader(null);
            return;
        }
        paint.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * extent,
                top, bottom, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onBoundsChange(Rect b) {
        rebuild();
    }

    @Override
    public void draw(Canvas c) {
        c.drawRect(getBounds(), paint);
    }

    @Override
    public void setAlpha(int a) {
        paint.setAlpha(a);
    }

    @Override
    public void setColorFilter(ColorFilter f) {
        paint.setColorFilter(f);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
