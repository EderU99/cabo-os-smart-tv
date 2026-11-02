package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Lightweight vector-style canvas view for the weather pill icon.
 * Draws sun, cloud, rain, or storm icons based on WMO weather code and day/night state.
 */
public final class WeatherIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Path bolt = new Path();
    private int weatherCode = 1;
    private boolean isDay = true;

    public WeatherIconView(Context context) {
        super(context);
    }

    public WeatherIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setWeather(int code, boolean day) {
        if (this.weatherCode != code || this.isDay != day) {
            this.weatherCode = code;
            this.isDay = day;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        boolean clear = (weatherCode == 0);
        boolean partlyCloudy = (weatherCode == 1 || weatherCode == 2);
        boolean rainy = (weatherCode >= 51 && weatherCode <= 67) || (weatherCode >= 80 && weatherCode <= 82);
        boolean stormy = (weatherCode >= 95);

        if (clear) {
            drawSunOrMoon(canvas, w * 0.5f, h * 0.5f, w * 0.28f);
            return;
        }

        if (partlyCloudy) {
            drawSunOrMoon(canvas, w * 0.38f, h * 0.38f, w * 0.22f);
        }

        // Cloud body
        float cy = (rainy || stormy) ? h * 0.44f : h * 0.54f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF5F8FF);

        // Base rounded bar of the cloud
        oval.set(w * 0.22f, cy, w * 0.90f, cy + h * 0.28f);
        canvas.drawRoundRect(oval, h * 0.14f, h * 0.14f, paint);

        // Left puff
        oval.set(w * 0.30f, cy - h * 0.18f, w * 0.64f, cy + h * 0.18f);
        canvas.drawOval(oval, paint);

        // Right puff
        oval.set(w * 0.52f, cy - h * 0.11f, w * 0.82f, cy + h * 0.20f);
        canvas.drawOval(oval, paint);

        if (rainy) {
            paint.setColor(0xFF6EC1FF);
            paint.setStrokeWidth(w * 0.065f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float dropTop = cy + h * 0.33f;
            float dropBot = cy + h * 0.48f;
            canvas.drawLine(w * 0.38f, dropTop, w * 0.33f, dropBot, paint);
            canvas.drawLine(w * 0.56f, dropTop, w * 0.51f, dropBot, paint);
            canvas.drawLine(w * 0.74f, dropTop, w * 0.69f, dropBot, paint);
        } else if (stormy) {
            paint.setColor(0xFFFFD15C);
            paint.setStyle(Paint.Style.FILL);
            bolt.reset();
            bolt.moveTo(w * 0.56f, cy + h * 0.24f);
            bolt.lineTo(w * 0.44f, cy + h * 0.42f);
            bolt.lineTo(w * 0.54f, cy + h * 0.42f);
            bolt.lineTo(w * 0.48f, cy + h * 0.56f);
            bolt.lineTo(w * 0.66f, cy + h * 0.36f);
            bolt.lineTo(w * 0.55f, cy + h * 0.36f);
            bolt.close();
            canvas.drawPath(bolt, paint);
        }
    }

    private void drawSunOrMoon(Canvas canvas, float cx, float cy, float r) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isDay ? 0xFFFFCC4D : 0xFFFFE599);
        canvas.drawCircle(cx, cy, r, paint);

        if (isDay) {
            paint.setStrokeWidth(Math.max(2f, r * 0.24f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xF0FFD05C);
            float inner = r * 1.32f;
            float outer = r * 1.68f;
            for (int a = 0; a < 360; a += 45) {
                double rad = Math.toRadians(a);
                float x1 = cx + (float) Math.cos(rad) * inner;
                float y1 = cy + (float) Math.sin(rad) * inner;
                float x2 = cx + (float) Math.cos(rad) * outer;
                float y2 = cy + (float) Math.sin(rad) * outer;
                canvas.drawLine(x1, y1, x2, y2, paint);
            }
        }
    }
}
