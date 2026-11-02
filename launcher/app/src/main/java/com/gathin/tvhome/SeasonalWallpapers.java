package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Automatic seasonal calendar (12-day holiday lead-in + extended Nov 25 – Jan 1 Christmas season)
 * powered by the Pixabay API (Full HD 1080p horizontal photographs) with built-in offline fallbacks.
 */
final class SeasonalWallpapers {
    private static final String TAG = "CaboOS.Pixabay";
    private static final String PIXABAY_API_KEY = "58167095-280ad57e29f8a761d2e6a64b5";
    private static final long SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L; // Refresh every 6 hours
    private static final int MAX_PIXABAY_IMAGES = 10;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile boolean syncing = false;

    static final class SeasonSpec {
        final String id;
        final String fallbackAssetFolder;
        final String query;
        final String category;
        final boolean editorsChoice;
        final String festivalName;
        final String greeting;
        final String accentHex;

        SeasonSpec(String id, String fallbackAssetFolder, String query, String category,
                   boolean editorsChoice, String festivalName, String greeting, String accentHex) {
            this.id = id;
            this.fallbackAssetFolder = fallbackAssetFolder;
            this.query = query;
            this.category = category;
            this.editorsChoice = editorsChoice;
            this.festivalName = festivalName;
            this.greeting = greeting;
            this.accentHex = accentHex;
        }
    }

    /**
     * Resolves the active season for {@code today}:
     * - Special family rule: Nov 25 through Jan 1 is Navidad / Año Nuevo.
     * - All other holidays activate 12 days before the celebration date.
     */
    static SeasonSpec resolveSeason(LocalDate today) {
        int m = today.getMonthValue();
        int d = today.getDayOfMonth();

        // 1. Año Nuevo (Dec 31 – Jan 1)
        if ((m == 12 && d == 31) || (m == 1 && d == 1)) {
            return new SeasonSpec(
                    "anonuevo", "navidad",
                    "new year fireworks night celebration lights", "backgrounds", false,
                    "anonuevo", "¡FELIZ AÑO NUEVO!", "#FFE082");
        }

        // 2. Navidad Familiar Extendida (Nov 25 – Dec 30)
        if ((m == 11 && d >= 25) || (m == 12 && d <= 30)) {
            return new SeasonSpec(
                    "navidad", "navidad",
                    "christmas tree snow lights winter cozy ornaments", "backgrounds", false,
                    "navidad", "FELIZ NAVIDAD", "#FFD76E");
        }

        // 3. Día de Reyes (Jan 2 – Jan 6)
        if (m == 1 && d >= 2 && d <= 6) {
            return new SeasonSpec(
                    "reyes", "navidad",
                    "winter night stars lights snow magic", "backgrounds", false,
                    "reyes", "FELIZ DÍA DE REYES", "#FFD54F");
        }

        // 4. San Valentín — 14 de febrero (desde 12 días antes: 2 al 14 de febrero)
        if (m == 2 && d >= 2 && d <= 14) {
            return new SeasonSpec(
                    "sanvalentin", "daily",
                    "romantic sunset roses flowers warm lights nature", "nature", false,
                    "sanvalentin", "FELIZ DÍA DEL AMOR Y LA AMISTAD", "#FF8A9E");
        }

        // 5. Primavera — 21 de marzo (desde 12 días antes: 9 al 22 de marzo)
        if (m == 3 && d >= 9 && d <= 22) {
            return new SeasonSpec(
                    "primavera", "daily",
                    "spring cherry blossom flowers mountain landscape", "nature", true,
                    "primavera", "BIENVENIDA LA PRIMAVERA", "#98F5B8");
        }

        // 6. Día del Niño — 30 de abril (desde 12 días antes: 18 al 30 de abril)
        if (m == 4 && d >= 18 && d <= 30) {
            return new SeasonSpec(
                    "diadelnino", "daily",
                    "colorful hot air balloons sky landscape nature", "travel", false,
                    "diadelnino", "FELIZ DÍA DEL NIÑO", "#7CE8FF");
        }

        // 7. Día de las Madres — 10 de mayo (del 1 al 10 de mayo, tras Día del Niño)
        if (m == 5 && d >= 1 && d <= 10) {
            return new SeasonSpec(
                    "madres", "daily",
                    "pink roses flowers spring garden sunlight", "nature", false,
                    "madres", "FELIZ DÍA DE LAS MADRES", "#FFB2C9");
        }

        // 8. Día del Padre — 3er domingo de junio (12 días antes: del 5 al 21 de junio)
        if (m == 6 && d >= 5 && d <= 21) {
            return new SeasonSpec(
                    "padres", "daily",
                    "majestic mountain peaks forest sunrise lake", "nature", true,
                    "padres", "FELIZ DÍA DEL PADRE", "#82CFFF");
        }

        // 9. Fiestas Patrias México — 16 de septiembre (desde 12 días antes: 4 al 16 de septiembre)
        if (m == 9 && d >= 4 && d <= 16) {
            return new SeasonSpec(
                    "mexico", "daily",
                    "mexico architecture guanajuato landscape colorful", "places", false,
                    "mexico", "¡VIVA MÉXICO!", "#69F0AE");
        }

        // 10. Día de Muertos & Halloween — 2 de noviembre (desde 12 días antes: 21 de octubre al 3 de noviembre)
        if ((m == 10 && d >= 21) || (m == 11 && d <= 3)) {
            return new SeasonSpec(
                    "muertos", "muertos",
                    "marigold candles autumn lanterns night flowers", "backgrounds", false,
                    "diademuertos", "FELIZ DÍA DE MUERTOS", "#FFBA42");
        }

        // Resto del año: Paisajes fotográficos 1080p Editor's Choice de Pixabay
        return new SeasonSpec(
                "daily", "daily",
                "mountains sunset lake nature forest landscape", "nature", true,
                "", "", "");
    }

    static Festival seasonalFestival(LocalDate today) {
        SeasonSpec spec = resolveSeason(today);
        if (spec.festivalName.isEmpty()) return Festival.NONE;
        return Festival.parse(spec.festivalName, null, spec.greeting, spec.accentHex);
    }

    static List<File> loadForToday(Context ctx, LocalDate today) {
        SeasonSpec spec = resolveSeason(today);
        File seasonDir = new File(new File(ctx.getFilesDir(), "cabo_wallpapers"), spec.id);
        if (!seasonDir.exists()) seasonDir.mkdirs();

        // 1. Collect any already-cached Pixabay HD photos for this season
        List<File> pixabayFiles = new ArrayList<>();
        File[] existing = seasonDir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (f.isFile() && f.length() > 25_000 && f.getName().startsWith("pixabay_")) {
                    pixabayFiles.add(f);
                }
            }
        }

        // 2. Extract built-in fallback assets so Home always has instant 1080p images even offline
        List<File> fallbackFiles = extractAssetFallbacks(ctx, spec.fallbackAssetFolder, seasonDir);

        List<File> combined = new ArrayList<>(pixabayFiles);
        for (File fb : fallbackFiles) {
            // Keep built-in Día de Muertos & Navidad photos alongside Pixabay, or use daily assets until Pixabay downloads
            if (combined.size() < 4 || !"daily".equals(spec.id)) {
                combined.add(fb);
            }
        }
        if (combined.isEmpty()) combined.addAll(fallbackFiles);
        Collections.sort(combined, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return combined;
    }

    public interface OnPixabayUpdated {
        void onNewWallpapers(List<File> updatedFiles);
    }

    /**
     * Queries Pixabay in the background with TV-grade filters (1920x1080+, horizontal, photo, safesearch, category)
     * and downloads fresh wallpapers for the active season.
     */
    static void syncPixabayAsync(Context ctx, LocalDate today, OnPixabayUpdated callback) {
        if (syncing) return;
        final Context appCtx = ctx.getApplicationContext();
        final SeasonSpec spec = resolveSeason(today);
        final SharedPreferences prefs = appCtx.getSharedPreferences("cabo_pixabay_sync", Context.MODE_PRIVATE);
        final File seasonDir = new File(new File(appCtx.getFilesDir(), "cabo_wallpapers"), spec.id);
        if (!seasonDir.exists()) seasonDir.mkdirs();

        int cachedCount = 0;
        File[] current = seasonDir.listFiles();
        if (current != null) {
            for (File f : current) {
                if (f.isFile() && f.length() > 25_000 && f.getName().startsWith("pixabay_")) cachedCount++;
            }
        }

        long lastSync = prefs.getLong("last_sync_" + spec.id, 0L);
        if (cachedCount >= 6 && (System.currentTimeMillis() - lastSync) < SYNC_INTERVAL_MS) {
            return;
        }

        syncing = true;
        EXECUTOR.execute(() -> {
            try {
                List<String> urls = queryPixabayUrls(spec, spec.editorsChoice);
                if (urls.size() < 5 && spec.editorsChoice) {
                    urls = queryPixabayUrls(spec, false);
                }
                if (urls.isEmpty()) return;

                boolean downloadedAny = false;
                int index = 1;
                for (String imgUrl : urls) {
                    if (index > MAX_PIXABAY_IMAGES) break;
                    File target = new File(seasonDir, String.format("pixabay_%s_%02d.jpg", spec.id, index));
                    File tmp = new File(seasonDir, target.getName() + ".tmp");
                    if (!target.isFile() || target.length() < 25_000 || (System.currentTimeMillis() - lastSync) >= SYNC_INTERVAL_MS) {
                        if (downloadFile(imgUrl, tmp)) {
                            if (target.exists()) target.delete();
                            if (tmp.renameTo(target)) {
                                downloadedAny = true;
                            }
                        } else if (tmp.exists()) {
                            tmp.delete();
                        }
                    }
                    if (target.isFile() && target.length() > 25_000) {
                        index++;
                    }
                }

                if (downloadedAny) {
                    prefs.edit().putLong("last_sync_" + spec.id, System.currentTimeMillis()).apply();
                    List<File> updated = loadForToday(appCtx, today);
                    if (callback != null && !updated.isEmpty()) {
                        MAIN_HANDLER.post(() -> callback.onNewWallpapers(updated));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Pixabay sync error: " + e.getMessage());
            } finally {
                syncing = false;
            }
        });
    }

    private static List<String> queryPixabayUrls(SeasonSpec spec, boolean editorsChoice) {
        List<String> urls = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            Uri.Builder b = Uri.parse("https://pixabay.com/api/").buildUpon()
                    .appendQueryParameter("key", PIXABAY_API_KEY)
                    .appendQueryParameter("q", spec.query)
                    .appendQueryParameter("image_type", "photo")
                    .appendQueryParameter("orientation", "horizontal")
                    .appendQueryParameter("min_width", "1920")
                    .appendQueryParameter("min_height", "1080")
                    .appendQueryParameter("category", spec.category)
                    .appendQueryParameter("safesearch", "true")
                    .appendQueryParameter("order", "popular")
                    .appendQueryParameter("per_page", "20");
            if (editorsChoice) {
                b.appendQueryParameter("editors_choice", "true");
            }
            URL url = new URL(b.build().toString());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "CaboOS-SmartTV/1.0");
            if (conn.getResponseCode() != 200) return urls;

            String jsonStr;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                jsonStr = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONObject root = new JSONObject(jsonStr);
            JSONArray hits = root.optJSONArray("hits");
            if (hits == null) return urls;
            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.optJSONObject(i);
                if (hit == null) continue;
                String largeUrl = hit.optString("fullHDURL", "");
                if (largeUrl.isEmpty()) largeUrl = hit.optString("largeImageURL", "");
                if (!largeUrl.isEmpty()) {
                    urls.add(largeUrl);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return urls;
    }

    private static boolean downloadFile(String urlStr, File dest) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", "CaboOS-SmartTV/1.0");
            if (conn.getResponseCode() != 200) return false;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            }
            return dest.isFile() && dest.length() > 25_000;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static List<File> extractAssetFallbacks(Context ctx, String folder, File outDir) {
        AssetManager am = ctx.getAssets();
        List<File> result = new ArrayList<>();
        try {
            String assetSub = "wallpapers/" + folder;
            String[] names = am.list(assetSub);
            if (names != null) {
                for (String name : names) {
                    if (!name.endsWith(".jpg") && !name.endsWith(".png")) continue;
                    File target = new File(outDir, "builtin_" + name);
                    if (!target.isFile() || target.length() == 0) {
                        try (InputStream in = am.open(assetSub + "/" + name);
                             FileOutputStream out = new FileOutputStream(target)) {
                            byte[] buf = new byte[16384];
                            int r;
                            while ((r = in.read(buf)) > 0) {
                                out.write(buf, 0, r);
                            }
                        }
                    }
                    if (target.isFile() && target.length() > 0) {
                        result.add(target);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
