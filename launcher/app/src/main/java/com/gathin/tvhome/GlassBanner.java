package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Converts opaque app banners into translucent frosted-glass ("fondo tipo cristal") banners
 * while preserving the original app logo/artwork intact.
 */
final class GlassBanner {

    static Drawable apply(Context ctx, Drawable src, String pkg) {
        if (src == null) return null;
        try {
            int w = 320;
            int h = 180;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            src.setBounds(0, 0, w, h);
            src.draw(c);

            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);

            // Sample corners/edges to detect whether background is predominantly white or black
            int whiteCorners = 0;
            int blackCorners = 0;
            int[] sampleIdx = {
                    10 * w + 10, 10 * w + (w - 11),
                    (h - 11) * w + 10, (h - 11) * w + (w - 11),
                    (h / 2) * w + 8, (h / 2) * w + (w - 9)
            };
            for (int idx : sampleIdx) {
                int p = pixels[idx];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int lum = (r + g + b) / 3;
                int sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                if (lum > 215 && sat < 32) whiteCorners++;
                else if (Math.max(r, Math.max(g, b)) < 26) blackCorners++;
            }

            boolean isWhiteBg = (whiteCorners >= 3);
            boolean isBlackBg = (!isWhiteBg && blackCorners >= 3);
            boolean invertDarkText = isWhiteBg && (pkg != null && (pkg.contains("youtube") || pkg.contains("vending")));

            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                int a = (p >>> 24) & 0xFF;
                if (a == 0) continue;
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                int sat = max - min;
                int lum = (r + g + b) / 3;

                if (isWhiteBg) {
                    if (lum > 220 && sat < 28) {
                        pixels[i] = 0x00000000;
                    } else if (invertDarkText && lum < 120 && sat < 35) {
                        int newA = Math.min(255, (int) ((238 - lum) * 1.8f));
                        pixels[i] = (newA << 24) | 0x00FFFFFF;
                    } else {
                        int newA = Math.min(255, Math.max((int) ((242 - lum) * 2.4f), sat * 3));
                        pixels[i] = (newA << 24) | (r << 16) | (g << 8) | b;
                    }
                } else if (isBlackBg) {
                    if (max < 22) {
                        pixels[i] = 0x00000000;
                    } else {
                        int newA = Math.min(255, (int) ((max - 14) * 2.3f));
                        pixels[i] = (newA << 24) | (r << 16) | (g << 8) | b;
                    }
                } else {
                    // Colored banner (Disney+, Downloader, Prime Video): keep bright white logos 100% opaque,
                    // make colored backdrop translucent glass
                    if (lum > 205 && sat < 45) {
                        pixels[i] = (255 << 24) | (r << 16) | (g << 8) | b;
                    } else {
                        pixels[i] = (145 << 24) | (r << 16) | (g << 8) | b;
                    }
                }
            }

            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return new BitmapDrawable(ctx.getResources(), bmp);
        } catch (Throwable t) {
            return src;
        }
    }
}
