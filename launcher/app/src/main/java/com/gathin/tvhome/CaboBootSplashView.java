package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.res.ResourcesCompat;

import java.lang.reflect.Method;

/**
 * Full-screen CaboOS boot splash shown when the TV boots up after the Google TV logo
 * (waits for bootanimation to finish and the window to be visible before starting the timer)
 * and when the TV wakes from standby (screen power on).
 */
public final class CaboBootSplashView extends View {
    private static boolean shownSinceBoot = false;
    private static final long SETTLE_DELAY_MS = 450L;
    private static final long FILL_DURATION_MS = 2800L;
    private static final long FADE_DURATION_MS = 650L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Typeface fontBold;
    private Typeface fontLight;
    private RadialGradient glowGradient;
    private int lastW, lastH;
    private long visibleSinceMs = 0L;
    private boolean active = false;

    public CaboBootSplashView(Context context) {
        super(context);
        init(context);
    }

    public CaboBootSplashView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        try {
            fontBold = ResourcesCompat.getFont(context, R.font.outfit_medium);
            fontLight = ResourcesCompat.getFont(context, R.font.outfit_light);
        } catch (Exception ignored) {
            fontBold = Typeface.create("sans-serif-medium", Typeface.BOLD);
            fontLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
        if (shownSinceBoot) {
            setVisibility(GONE);
        } else {
            shownSinceBoot = true;
            active = true;
            visibleSinceMs = 0L;
            setVisibility(VISIBLE);
        }
    }

    /** Replays the CaboOS splash screen (e.g. when turning the TV back on from standby). */
    public void replay() {
        active = true;
        visibleSinceMs = 0L;
        setAlpha(1f);
        setVisibility(VISIBLE);
        bringToFront();
        invalidate();
    }

    private static boolean isBootAnimRunning() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            String exit = (String) get.invoke(null, "service.bootanim.exit", "1");
            if ("0".equals(exit)) return true;
            String svc = (String) get.invoke(null, "init.svc.bootanim", "stopped");
            return "running".equalsIgnoreCase(svc);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (active && hasWindowFocus) {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!active) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        long now = SystemClock.uptimeMillis();
        // Wait until the Google TV boot animation has completely exited and our window is visible on screen
        if (!hasWindowFocus() || isBootAnimRunning()) {
            visibleSinceMs = 0L;
        } else if (visibleSinceMs == 0L) {
            visibleSinceMs = now;
        }

        long rawElapsed = visibleSinceMs == 0L ? 0L : Math.max(0L, now - visibleSinceMs);
        long animElapsed = Math.max(0L, rawElapsed - SETTLE_DELAY_MS);

        if (animElapsed >= FILL_DURATION_MS + FADE_DURATION_MS) {
            active = false;
            setVisibility(GONE);
            return;
        }

        float masterAlpha = 1f;
        if (animElapsed > FILL_DURATION_MS) {
            float t = (animElapsed - FILL_DURATION_MS) / (float) FADE_DURATION_MS;
            masterAlpha = Math.max(0f, 1f - t * t);
        }
        int saveCount = canvas.saveLayerAlpha(0, 0, w, h, Math.round(masterAlpha * 255));

        // 1. Deep obsidian background
        canvas.drawColor(0xFF06080E);

        // 2. Radial ambient glow
        if (glowGradient == null || lastW != w || lastH != h) {
            lastW = w;
            lastH = h;
            glowGradient = new RadialGradient(
                    w * 0.5f, h * 0.46f, h * 0.42f,
                    new int[]{0x26468CFF, 0x12468CFF, 0x0006080E},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(glowGradient);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        float scale = h / 1080f;
        float cx = w * 0.5f;

        // 3. Dark frosted-glass emblem box with golden border
        float boxSize = 150f * scale;
        float boxLeft = cx - boxSize * 0.5f;
        float boxTop = 290f * scale;
        rect.set(boxLeft, boxTop, boxLeft + boxSize, boxTop + boxSize);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xDC181C28);
        canvas.drawRoundRect(rect, 42f * scale, 42f * scale, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * scale);
        paint.setColor(0xA0FFD064);
        canvas.drawRoundRect(rect, 42f * scale, 42f * scale, paint);

        // 4. Golden paw print inside the emblem
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xF5FFD064);
        rect.set(cx - 28f * scale, (290f + 78f) * scale, cx + 28f * scale, (290f + 122f) * scale);
        canvas.drawOval(rect, paint);
        rect.set(cx - 48f * scale, (290f + 46f) * scale, cx - 26f * scale, (290f + 74f) * scale);
        canvas.drawOval(rect, paint);
        rect.set(cx - 22f * scale, (290f + 32f) * scale, cx - 2f * scale, (290f + 62f) * scale);
        canvas.drawOval(rect, paint);
        rect.set(cx + 4f * scale, (290f + 32f) * scale, cx + 24f * scale, (290f + 62f) * scale);
        canvas.drawOval(rect, paint);
        rect.set(cx + 28f * scale, (290f + 46f) * scale, cx + 50f * scale, (290f + 74f) * scale);
        canvas.drawOval(rect, paint);

        // 5. "Cabo OS" title
        float titleSize = 112f * scale;
        textPaint.setTextSize(titleSize);
        textPaint.setTypeface(fontBold);
        String caboStr = "Cabo ";
        float caboW = textPaint.measureText(caboStr);

        textPaint.setTypeface(fontLight);
        String osStr = "OS";
        float osW = textPaint.measureText(osStr);

        float totalW = caboW + osW;
        float startX = cx - totalW * 0.5f;
        float baselineY = 562f * scale;

        textPaint.setTypeface(fontBold);
        textPaint.setColor(0xFFFFFFFF);
        canvas.drawText(caboStr, startX, baselineY, textPaint);

        textPaint.setTypeface(fontLight);
        textPaint.setColor(0xFFFFCC5C);
        canvas.drawText(osStr, startX + caboW, baselineY, textPaint);

        // 6. Subtitle "S M A R T   T V   E D I T I O N"
        textPaint.setTypeface(fontBold);
        textPaint.setTextSize(21f * scale);
        textPaint.setColor(0xA6FFFFFF);
        String subStr = "S M A R T   T V   E D I T I O N";
        float subW = textPaint.measureText(subStr);
        canvas.drawText(subStr, cx - subW * 0.5f, 624f * scale, textPaint);

        // 7. Animated golden progress bar
        float barW = 300f * scale;
        float barH = 8f * scale;
        float barLeft = cx - barW * 0.5f;
        float barTop = 672f * scale;
        rect.set(barLeft, barTop, barLeft + barW, barTop + barH);
        paint.setColor(0x24FFFFFF);
        canvas.drawRoundRect(rect, barH * 0.5f, barH * 0.5f, paint);

        float progress = Math.min(1f, animElapsed / (float) FILL_DURATION_MS);
        float eased = 1f - (1f - progress) * (1f - progress);
        rect.set(barLeft, barTop, barLeft + Math.max(barH * 2f, barW * eased), barTop + barH);
        paint.setColor(0xF2FFCC5C);
        canvas.drawRoundRect(rect, barH * 0.5f, barH * 0.5f, paint);

        canvas.restoreToCount(saveCount);
        postInvalidateOnAnimation();
    }
}
