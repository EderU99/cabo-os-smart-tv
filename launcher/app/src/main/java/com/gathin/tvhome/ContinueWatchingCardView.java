package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.core.content.res.ResourcesCompat;

/**
 * Custom panoramic frosted-glass card for the "Continuar viendo" row (Option 1).
 * Renders the episode/video thumbnail (or app banner backdrop), top-left streaming app badge,
 * top-right circular play icon, movie/video title, remaining time subtitle, and progress bar.
 */
public class ContinueWatchingCardView extends View {

    private final float dp;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final TextPaint badgeTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titleTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final RectF cardRect = new RectF();
    private final RectF innerRect = new RectF();
    private final RectF badgeRect = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF progRect = new RectF();
    private final Path clipPath = new Path();
    private final Path playTrianglePath = new Path();
    private final Matrix bmpMatrix = new Matrix();

    private ContinueWatchingStore.Item item;
    private Bitmap artBitmap;
    private Drawable appBackdrop;
    private int lastW, lastH;

    public ContinueWatchingCardView(Context context) {
        this(context, null);
    }

    public ContinueWatchingCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = context.getResources().getDisplayMetrics().density;
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);

        Typeface mediumTf = ResourcesCompat.getFont(context, R.font.outfit_medium);
        Typeface regularTf = ResourcesCompat.getFont(context, R.font.outfit_regular);

        badgeTextPaint.setColor(0xFFFFFFFF);
        badgeTextPaint.setTextSize(9.5f * dp);
        badgeTextPaint.setTypeface(mediumTf != null ? Typeface.create(mediumTf, Typeface.BOLD) : Typeface.DEFAULT_BOLD);
        badgeTextPaint.setLetterSpacing(0.06f);

        titleTextPaint.setColor(0xFFFFFFFF);
        titleTextPaint.setTextSize(13.5f * dp);
        titleTextPaint.setTypeface(mediumTf != null ? mediumTf : Typeface.DEFAULT_BOLD);

        subTextPaint.setColor(0xD6E8F0FF);
        subTextPaint.setTextSize(10.5f * dp);
        subTextPaint.setTypeface(regularTf != null ? regularTf : Typeface.DEFAULT);

        borderPaint.setStyle(Paint.Style.STROKE);
        playStrokePaint.setStyle(Paint.Style.STROKE);
        playStrokePaint.setStrokeWidth(1.3f * dp);
        playIconPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(0x48FFFFFF);

        setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().cancel();
            if (hasFocus) {
                v.animate()
                        .scaleX(1.055f)
                        .scaleY(1.055f)
                        .translationY(-3f * dp)
                        .translationZ(10f * dp)
                        .setDuration(210)
                        .setInterpolator(new OvershootInterpolator(1.1f))
                        .start();
            } else {
                v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .translationZ(0f)
                        .setDuration(160)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
            invalidate();
        });
    }

    public void bind(ContinueWatchingStore.Item newItem, Bitmap artwork, Drawable backdrop) {
        this.item = newItem;
        this.artBitmap = artwork;
        this.appBackdrop = backdrop;
        lastW = 0;
        lastH = 0;
        invalidate();
    }

    public ContinueWatchingStore.Item getItem() {
        return item;
    }

    private void rebuildShaders(int w, int h) {
        int accent = item != null ? item.accentColor : 0xFF38BDF8;
        int darkAccent = blendColor(0xFF0B1322, accent, 0.34f);
        bgPaint.setShader(new LinearGradient(
                0, 0, w, h,
                new int[]{darkAccent, 0xFF0A101D, blendColor(0xFF080D18, accent, 0.22f)},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));

        scrimPaint.setShader(new LinearGradient(
                0, h * 0.12f, 0, h,
                new int[]{0x14060A12, 0x94070B14, 0xEE060A12},
                new float[]{0f, 0.52f, 1f},
                Shader.TileMode.CLAMP));

        topSheenPaint.setShader(new LinearGradient(
                0, 0, 0, h * 0.42f,
                0x36FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
    }

    private static int blendColor(int base, int overlay, float ratio) {
        int r = (int) (Color.red(base) * (1f - ratio) + Color.red(overlay) * ratio);
        int g = (int) (Color.green(base) * (1f - ratio) + Color.green(overlay) * ratio);
        int b = (int) (Color.blue(base) * (1f - ratio) + Color.blue(overlay) * ratio);
        return Color.rgb(r, g, b);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (item == null) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (w != lastW || h != lastH) {
            lastW = w;
            lastH = h;
            rebuildShaders(w, h);
        }

        float inset = 2f * dp;
        float radius = 16f * dp;
        cardRect.set(inset, inset, w - inset, h - inset);

        clipPath.reset();
        clipPath.addRoundRect(cardRect, radius, radius, Path.Direction.CW);

        int save = canvas.save();
        canvas.clipPath(clipPath);

        // 1. Base frosted dark-accent gradient
        canvas.drawRect(cardRect, bgPaint);

        // 2. Episode/movie artwork OR app banner backdrop
        if (artBitmap != null && !artBitmap.isRecycled() && artBitmap.getWidth() > 0 && artBitmap.getHeight() > 0) {
            float bw = artBitmap.getWidth();
            float bh = artBitmap.getHeight();
            float scale = Math.max(cardRect.width() / bw, cardRect.height() / bh);
            float dx = cardRect.left + (cardRect.width() - bw * scale) * 0.5f;
            float dy = cardRect.top + (cardRect.height() - bh * scale) * 0.35f;
            bmpMatrix.reset();
            bmpMatrix.postScale(scale, scale);
            bmpMatrix.postTranslate(dx, dy);
            bitmapPaint.setAlpha(235);
            canvas.drawBitmap(artBitmap, bmpMatrix, bitmapPaint);
        } else if (appBackdrop != null) {
            int dw = appBackdrop.getIntrinsicWidth();
            int dh = appBackdrop.getIntrinsicHeight();
            if (dw > 0 && dh > 0) {
                Rect savedBounds = new Rect(appBackdrop.getBounds());
                // Banner aspect ratio vs square icon
                if (dw > dh * 1.25f) {
                    float scale = Math.max(cardRect.width() / (float) dw, cardRect.height() / (float) dh);
                    int rw = Math.round(dw * scale);
                    int rh = Math.round(dh * scale);
                    int left = Math.round(cardRect.centerX() - rw * 0.5f);
                    int top = Math.round(cardRect.centerY() - rh * 0.55f);
                    appBackdrop.setBounds(left, top, left + rw, top + rh);
                    appBackdrop.setAlpha(195);
                    appBackdrop.draw(canvas);
                    appBackdrop.setAlpha(255);
                } else {
                    int iconSize = Math.round(h * 0.44f);
                    int cx = Math.round(cardRect.centerX());
                    int cy = Math.round(cardRect.top + h * 0.36f);
                    appBackdrop.setBounds(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2);
                    appBackdrop.setAlpha(215);
                    appBackdrop.draw(canvas);
                    appBackdrop.setAlpha(255);
                }
                appBackdrop.setBounds(savedBounds);
            }
        }

        // 3. Dark bottom scrim + top glass reflection sheen
        canvas.drawRect(cardRect, scrimPaint);
        innerRect.set(cardRect.left, cardRect.top, cardRect.right, cardRect.top + cardRect.height() * 0.40f);
        canvas.drawRect(innerRect, topSheenPaint);

        // 4. Top-left streaming app badge pill
        String badgeText = item.appName != null ? item.appName : "TV";
        float textW = badgeTextPaint.measureText(badgeText);
        float badgePadX = 8.5f * dp;
        float badgeH = 17.5f * dp;
        float badgeLeft = cardRect.left + 11f * dp;
        float badgeTop = cardRect.top + 10f * dp;
        badgeRect.set(badgeLeft, badgeTop, badgeLeft + textW + badgePadX * 2f, badgeTop + badgeH);
        badgePaint.setColor((item.accentColor & 0x00FFFFFF) | 0xE8000000);
        canvas.drawRoundRect(badgeRect, 6f * dp, 6f * dp, badgePaint);

        Paint.FontMetrics bfm = badgeTextPaint.getFontMetrics();
        float badgeTextY = badgeRect.centerY() - (bfm.ascent + bfm.descent) * 0.5f;
        canvas.drawText(badgeText, badgeLeft + badgePadX, badgeTextY, badgeTextPaint);

        // 5. Top-right circular Play icon
        boolean focused = isFocused();
        float playR = 11.5f * dp;
        float playCx = cardRect.right - 11f * dp - playR;
        float playCy = badgeRect.centerY();
        playCirclePaint.setColor(focused ? (item.accentColor & 0x00FFFFFF) | 0xEE000000 : 0x800B111E);
        playStrokePaint.setColor(focused ? 0xFFFFFFFF : 0xB8FFFFFF);
        canvas.drawCircle(playCx, playCy, playR, playCirclePaint);
        canvas.drawCircle(playCx, playCy, playR, playStrokePaint);

        playTrianglePath.reset();
        float triSize = 4.6f * dp;
        playTrianglePath.moveTo(playCx - triSize * 0.65f, playCy - triSize);
        playTrianglePath.lineTo(playCx - triSize * 0.65f, playCy + triSize);
        playTrianglePath.lineTo(playCx + triSize * 1.05f, playCy);
        playTrianglePath.close();
        playIconPaint.setColor(0xFFFFFFFF);
        canvas.drawPath(playTrianglePath, playIconPaint);

        // 6. Bottom Progress Bar
        float padX = 12f * dp;
        float barH = 4.2f * dp;
        float barBottom = cardRect.bottom - 9f * dp;
        float barTop = barBottom - barH;
        trackRect.set(cardRect.left + padX, barTop, cardRect.right - padX, barBottom);
        canvas.drawRoundRect(trackRect, barH * 0.5f, barH * 0.5f, trackPaint);

        float progFraction = Math.max(0.08f, Math.min(0.96f, item.progress));
        float progRight = trackRect.left + trackRect.width() * progFraction;
        progRect.set(trackRect.left, barTop, progRight, barBottom);
        progressPaint.setColor(0xFF000000 | item.accentColor);
        canvas.drawRoundRect(progRect, barH * 0.5f, barH * 0.5f, progressPaint);

        // 7. Subtitle (above progress bar) and Title (above subtitle)
        float maxTextW = cardRect.width() - padX * 2f;
        String subStr = TextUtils.ellipsize(item.subtitle != null ? item.subtitle : "", subTextPaint, maxTextW, TextUtils.TruncateAt.END).toString();
        String titleStr = TextUtils.ellipsize(item.title != null ? item.title : "", titleTextPaint, maxTextW, TextUtils.TruncateAt.END).toString();

        float subBaseline = barTop - 6.5f * dp;
        canvas.drawText(subStr, cardRect.left + padX, subBaseline, subTextPaint);

        float titleBaseline = subBaseline - 14.5f * dp;
        canvas.drawText(titleStr, cardRect.left + padX, titleBaseline, titleTextPaint);

        canvas.restoreToCount(save);

        // 8. Frosted-glass / Diamond-focus outer border
        if (focused) {
            borderPaint.setStrokeWidth(2.4f * dp);
            borderPaint.setColor(0xFFFFFFFF);
        } else {
            borderPaint.setStrokeWidth(1.1f * dp);
            borderPaint.setColor(0x68FFFFFF);
        }
        canvas.drawRoundRect(cardRect, radius, radius, borderPaint);
    }
}
