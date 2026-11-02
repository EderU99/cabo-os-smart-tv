package com.gathin.tvhome;

import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores and retrieves the up to 4 "Continuar viendo" (Continue Watching) items across streaming apps.
 * Combines real-time MediaSession / Notification metadata with UsageStats and launcher app opens.
 */
public final class ContinueWatchingStore {

    public static final int MAX_ITEMS = 4;
    private static final String DIR_NAME = "continue_watching";
    private static final String STATE_FILE = "state.json";

    private static final Map<String, PendingIntent> LIVE_INTENTS = new ConcurrentHashMap<>();
    private static volatile Runnable changeListener;

    /** Known streaming & media packages in priority order. */
    private static final String[] KNOWN_MEDIA_PACKAGES = new String[]{
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.google.android.youtube.tv",
            "com.xuper.netxxus",
            "com.amazon.amazonvideo.livingroom",
            "com.amazon.avod",
            "com.wbd.stream",
            "com.hbo.hbonow",
            "com.univision.prendetv",
            "com.crunchyroll.crunchyroid",
            "tv.pluto.android",
            "com.apple.atve.androidtv.appletv",
            "com.plexapp.android",
            "org.xbmc.kodi",
            "com.spotify.tv.android",
            "com.liskovsoft.smarttubetv.beta",
            "com.liskovsoft.smarttubetv",
            "com.google.android.youtube.tvkids",
            "com.paramount.plus",
            "com.cbs.ca",
            "com.starz.starzplay.android",
            "com.dazn",
            "com.espn.score_center",
            "com.tudn.android",
            "com.televisa.blim",
            "com.clarovideo.android",
            "com.roku.therokuchannel"
    };

    public static final class Item {
        public final String pkg;
        public final String appName;
        public final int accentColor;
        public String title;
        public String subtitle;
        public float progress;
        public String artPath;
        public String intentUri;
        public long updatedAt;
        public boolean hasLiveMetadata;

        public Item(String pkg, String appName, int accentColor, String title, String subtitle,
                    float progress, String artPath, String intentUri, long updatedAt, boolean hasLiveMetadata) {
            this.pkg = pkg;
            this.appName = appName;
            this.accentColor = accentColor;
            this.title = title;
            this.subtitle = subtitle;
            this.progress = progress;
            this.artPath = artPath;
            this.intentUri = intentUri;
            this.updatedAt = updatedAt;
            this.hasLiveMetadata = hasLiveMetadata;
        }
    }

    private ContinueWatchingStore() {
    }

    public static void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    private static void notifyChanged() {
        Runnable r = changeListener;
        if (r != null) r.run();
    }

    public static void setLiveSessionIntent(String pkg, PendingIntent pi) {
        if (pkg == null) return;
        if (pi != null) LIVE_INTENTS.put(pkg, pi);
        else LIVE_INTENTS.remove(pkg);
    }

    /** Returns true if a package is a streaming/video/media app that belongs in "Continuar viendo". */
    public static boolean isMediaPackage(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(ctx.getPackageName())
                || pkg.startsWith("com.android.")
                || pkg.startsWith("com.google.android.katniss")
                || pkg.startsWith("com.google.android.gms")
                || pkg.startsWith("com.google.android.tvrecommendations")
                || pkg.startsWith("com.google.android.apps.tv.launcherx")
                || pkg.equals("com.mediatek.wwtv.tvcenter")
                || pkg.equals("com.mitv.gallery")) {
            return false;
        }
        for (String known : KNOWN_MEDIA_PACKAGES) {
            if (known.equals(pkg)) return true;
        }
        String lower = pkg.toLowerCase(Locale.US);
        if (lower.contains("video") || lower.contains("tv") || lower.contains("stream")
                || lower.contains("movie") || lower.contains("cinema") || lower.contains("play")
                || lower.contains("netflix") || lower.contains("disney") || lower.contains("youtube")
                || lower.contains("xuper") || lower.contains("iptv") || lower.contains("player")
                || lower.contains("kodi") || lower.contains("plex") || lower.contains("max")
                || lower.contains("vix") || lower.contains("pluto")) {
            return true;
        }
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.category == ApplicationInfo.CATEGORY_VIDEO || ai.category == ApplicationInfo.CATEGORY_AUDIO) {
                return true;
            }
        } catch ( Exception ignored) {
        }
        return false;
    }

    /** Called by MediaSession / Notification listener when real media metadata is detected. */
    public static synchronized void recordMediaSession(Context ctx, String pkg, String rawTitle,
                                                       String rawSubtitle, float progress, Bitmap art) {
        if (pkg == null || !isMediaPackage(ctx, pkg)) return;
        String title = rawTitle != null ? rawTitle.trim() : "";
        if (title.isEmpty()) return;
        // Ignore generic placeholder strings some apps emit during startup
        if (title.equalsIgnoreCase("Cargando...") || title.equalsIgnoreCase("Loading...")) return;

        List<Item> list = readSaved(ctx);
        Item existing = null;
        for (Item it : list) {
            if (pkg.equals(it.pkg)) {
                existing = it;
                break;
            }
        }

        String appBadge = resolveAppBadge(ctx, pkg);
        int accent = resolveAccentColor(pkg);
        String subtitle = (rawSubtitle != null && !rawSubtitle.trim().isEmpty())
                ? rawSubtitle.trim()
                : "En reproducción · Toca OK para reanudar";
        float clampedProgress = progress > 0.01f
                ? Math.max(0.08f, Math.min(0.95f, progress))
                : (existing != null && existing.progress > 0.08f ? existing.progress : 0.42f);

        String artPath = existing != null ? existing.artPath : null;
        if (art != null && !art.isRecycled() && art.getWidth() >= 48 && art.getHeight() >= 48) {
            String savedArt = saveArtwork(ctx, pkg, art);
            if (savedArt != null) artPath = savedArt;
        }

        long now = System.currentTimeMillis();
        if (existing != null) {
            list.remove(existing);
            existing.title = title;
            existing.subtitle = subtitle;
            existing.progress = clampedProgress;
            existing.artPath = artPath;
            existing.updatedAt = now;
            existing.hasLiveMetadata = true;
            list.add(0, existing);
        } else {
            list.add(0, new Item(pkg, appBadge, accent, title, subtitle,
                    clampedProgress, artPath, null, now, true));
        }

        while (list.size() > 8) list.remove(list.size() - 1);
        writeSaved(ctx, list);
        notifyChanged();
    }

    /** Called when the user launches an app from the launcher so it moves to the front of Continue Watching. */
    public static synchronized void recordAppOpened(Context ctx, String pkg, String label) {
        if (pkg == null || !isMediaPackage(ctx, pkg)) return;
        List<Item> list = readSaved(ctx);
        Item existing = null;
        for (Item it : list) {
            if (pkg.equals(it.pkg)) {
                existing = it;
                break;
            }
        }
        long now = System.currentTimeMillis();
        String appBadge = resolveAppBadge(ctx, pkg);
        int accent = resolveAccentColor(pkg);
        if (existing != null) {
            list.remove(existing);
            existing.updatedAt = now;
            if (!existing.hasLiveMetadata) {
                existing.subtitle = "Visto recientemente · Toca OK para continuar";
            }
            list.add(0, existing);
        } else {
            String defaultTitle = defaultTitleForApp(pkg, label != null ? label : appBadge);
            String defaultSub = "En pausa · Toca OK para continuar viendo";
            float defaultProg = defaultProgressForApp(pkg);
            list.add(0, new Item(pkg, appBadge, accent, defaultTitle, defaultSub,
                    defaultProg, null, null, now, false));
        }
        while (list.size() > 8) list.remove(list.size() - 1);
        writeSaved(ctx, list);
    }

    /** Removes an item from Continue Watching (e.g. on long-press) and refreshes. */
    public static synchronized void removeItem(Context ctx, String pkg) {
        if (pkg == null) return;
        List<Item> list = readSaved(ctx);
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (pkg.equals(list.get(i).pkg)) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) {
            writeSaved(ctx, list);
            notifyChanged();
        }
    }

    /**
     * Loads up to 4 items to display in the "Continuar viendo" row:
     * 1. Syncs recent foreground streaming app usage from UsageStatsManager and WatchNextPrograms.
     * 2. Uses saved MediaSession / notification items sorted by most recent activity.
     * 3. Fills any remaining slots (up to 4) with installed streaming apps so the row is always complete.
     */
    public static synchronized List<Item> loadItems(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<Item> saved = readSaved(ctx);

        // Remove any uninstalled packages
        for (int i = saved.size() - 1; i >= 0; i--) {
            if (!isPackageInstalled(pm, saved.get(i).pkg)) {
                saved.remove(i);
            }
        }

        // Sync WatchNextPrograms if accessible on this TV
        syncWatchNextPrograms(ctx, pm, saved);

        // Sync recent foreground activity from UsageStatsManager (e.g. if opened via remote button)
        syncUsageStats(ctx, pm, saved);

        // Sort by updatedAt descending
        Collections.sort(saved, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));

        List<Item> result = new ArrayList<>();
        Set<String> included = new HashSet<>();
        for (Item it : saved) {
            if (included.add(it.pkg)) {
                // Refresh subtitle relative time if it does not have live remaining-minutes text
                if (!it.hasLiveMetadata && it.updatedAt > 0) {
                    it.subtitle = "Última sesión " + LastOpened.describe(ctx, it.updatedAt) + " · Reanudar";
                }
                result.add(it);
                if (result.size() >= MAX_ITEMS) break;
            }
        }

        // Seed remaining slots up to MAX_ITEMS from installed streaming apps
        if (result.size() < MAX_ITEMS) {
            for (String candidatePkg : KNOWN_MEDIA_PACKAGES) {
                if (included.contains(candidatePkg)) continue;
                if (!isPackageInstalled(pm, candidatePkg)) continue;
                String label = resolveAppLabel(pm, candidatePkg);
                String badge = resolveAppBadge(ctx, candidatePkg);
                int accent = resolveAccentColor(candidatePkg);
                long lastOpen = LastOpened.get(ctx, candidatePkg);
                String sub = lastOpen > 0
                        ? "Última sesión " + LastOpened.describe(ctx, lastOpen) + " · Reanudar"
                        : "Listo para reanudar · Toca OK";
                Item seed = new Item(
                        candidatePkg,
                        badge,
                        accent,
                        defaultTitleForApp(candidatePkg, label),
                        sub,
                        defaultProgressForApp(candidatePkg),
                        null,
                        null,
                        lastOpen,
                        false
                );
                result.add(seed);
                included.add(candidatePkg);
                if (result.size() >= MAX_ITEMS) break;
            }
        }

        return result;
    }

    /** Launches the streaming app (or its active MediaSession / deep link) to resume playback. */
    public static void launchItem(Context ctx, Item item) {
        if (item == null || item.pkg == null) return;
        recordAppOpened(ctx, item.pkg, item.appName);
        LastOpened.record(ctx, item.pkg);

        // 1. Try active MediaSession PendingIntent if available
        PendingIntent pi = LIVE_INTENTS.get(item.pkg);
        if (pi != null) {
            try {
                pi.send();
                return;
            } catch (Exception ignored) {
                LIVE_INTENTS.remove(item.pkg);
            }
        }

        // 2. Try deep-link intentUri from WatchNext if present
        if (item.intentUri != null && !item.intentUri.isEmpty()) {
            try {
                Intent deep = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
                deep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                ctx.startActivity(deep);
                return;
            } catch (Exception ignored) {
            }
        }

        // 3. Launch the streaming app with LEANBACK_LAUNCHER and RESET_TASK_IF_NEEDED so it resumes its state
        PackageManager pm = ctx.getPackageManager();
        Intent leanback = pm.getLeanbackLaunchIntentForPackage(item.pkg);
        if (leanback == null) leanback = pm.getLaunchIntentForPackage(item.pkg);
        if (leanback != null) {
            leanback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                ctx.startActivity(leanback);
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(ctx, ctx.getString(R.string.cant_open, item.appName), Toast.LENGTH_SHORT).show();
    }

    /** Loads the saved thumbnail Bitmap for an item, or null if none is saved on disk. */
    public static Bitmap loadArtworkBitmap(Item item) {
        if (item == null || item.artPath == null || item.artPath.isEmpty()) return null;
        try {
            File f = new File(item.artPath);
            if (!f.isFile()) return null;
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    /** Loads the app's TV banner (or icon) Drawable to use as a backdrop when no episode thumbnail is cached. */
    public static Drawable loadAppBackdrop(Context ctx, String pkg) {
        if (pkg == null) return null;
        PackageManager pm = ctx.getPackageManager();
        try {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            if (list != null && !list.isEmpty()) {
                ResolveInfo ri = list.get(0);
                if (ri.activityInfo != null) {
                    Drawable banner = ri.activityInfo.loadBanner(pm);
                    if (banner != null) return banner;
                }
            }
            Drawable appBanner = pm.getApplicationBanner(pkg);
            if (appBanner != null) return appBanner;
            return pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private static void syncWatchNextPrograms(Context ctx, PackageManager pm, List<Item> saved) {
        Cursor c = null;
        try {
            Uri uri = Uri.parse("content://android.media.tv/watch_next_program");
            String[] proj = new String[]{
                    "package_name",
                    "title",
                    "episode_title",
                    "short_description",
                    "duration_millis",
                    "last_playback_position_millis",
                    "intent_uri",
                    "last_engagement_time_utc_millis"
            };
            c = ctx.getContentResolver().query(uri, proj, null, null, "last_engagement_time_utc_millis DESC");
            if (c == null) return;
            while (c.moveToNext()) {
                String pkg = c.getString(0);
                String title = c.getString(1);
                if (pkg == null || title == null || title.trim().isEmpty() || !isPackageInstalled(pm, pkg)) continue;
                String epTitle = c.getString(2);
                String desc = c.getString(3);
                long dur = c.getLong(4);
                long pos = c.getLong(5);
                String intentUri = c.getString(6);
                long engaged = c.getLong(7);

                float prog = (dur > 0 && pos > 0)
                        ? Math.max(0.08f, Math.min(0.95f, (float) pos / (float) dur))
                        : 0.45f;
                String sub;
                if (dur > pos && pos > 0) {
                    long remMin = Math.max(1L, (dur - pos) / 60000L);
                    sub = (epTitle != null && !epTitle.isEmpty() ? epTitle + " · " : "") + "Faltan " + remMin + " min";
                } else if (epTitle != null && !epTitle.isEmpty()) {
                    sub = epTitle;
                } else if (desc != null && !desc.isEmpty()) {
                    sub = desc;
                } else {
                    sub = "Continuar viendo";
                }

                Item match = null;
                for (Item it : saved) {
                    if (pkg.equals(it.pkg)) {
                        match = it;
                        break;
                    }
                }
                if (match == null) {
                    saved.add(new Item(pkg, resolveAppBadge(ctx, pkg), resolveAccentColor(pkg),
                            title.trim(), sub, prog, null, intentUri,
                            engaged > 0 ? engaged : System.currentTimeMillis(), true));
                } else if (engaged > match.updatedAt || !match.hasLiveMetadata) {
                    match.title = title.trim();
                    match.subtitle = sub;
                    match.progress = prog;
                    if (intentUri != null) match.intentUri = intentUri;
                    if (engaged > match.updatedAt) match.updatedAt = engaged;
                    match.hasLiveMetadata = true;
                }
            }
        } catch (Throwable ignored) {
            // READ_TV_LISTINGS is signature/privileged on most builds; safely ignore if denied.
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void syncUsageStats(Context ctx, PackageManager pm, List<Item> saved) {
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return;
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 7L * 86400_000L, now);
            if (stats == null || stats.isEmpty()) return;

            Map<String, Long> latestUsed = new HashMap<>();
            for (UsageStats us : stats) {
                String pkg = us.getPackageName();
                long lastTime = us.getLastTimeUsed();
                if (lastTime <= 0 || !isMediaPackage(ctx, pkg) || !isPackageInstalled(pm, pkg)) continue;
                Long prev = latestUsed.get(pkg);
                if (prev == null || lastTime > prev) latestUsed.put(pkg, lastTime);
            }

            boolean modified = false;
            for (Map.Entry<String, Long> entry : latestUsed.entrySet()) {
                String pkg = entry.getKey();
                long lastTime = entry.getValue();
                Item match = null;
                for (Item it : saved) {
                    if (pkg.equals(it.pkg)) {
                        match = it;
                        break;
                    }
                }
                if (match != null) {
                    if (lastTime > match.updatedAt + 15_000L) {
                        match.updatedAt = lastTime;
                        modified = true;
                    }
                } else {
                    String label = resolveAppLabel(pm, pkg);
                    saved.add(new Item(
                            pkg,
                            resolveAppBadge(ctx, pkg),
                            resolveAccentColor(pkg),
                            defaultTitleForApp(pkg, label),
                            "Última sesión " + LastOpened.describe(ctx, lastTime) + " · Reanudar",
                            defaultProgressForApp(pkg),
                            null,
                            null,
                            lastTime,
                            false
                    ));
                    modified = true;
                }
            }
            if (modified) writeSaved(ctx, saved);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isPackageInstalled(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return ai != null && ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String resolveAppLabel(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Exception ignored) {
        }
        return pkg;
    }

    public static String resolveAppBadge(Context ctx, String pkg) {
        if (pkg == null) return "TV";
        switch (pkg) {
            case "com.netflix.ninja":
                return "NETFLIX";
            case "com.disney.disneyplus":
                return "DISNEY+";
            case "com.google.android.youtube.tv":
            case "com.liskovsoft.smarttubetv":
            case "com.liskovsoft.smarttubetv.beta":
                return "YOUTUBE";
            case "com.google.android.youtube.tvkids":
                return "YT KIDS";
            case "com.xuper.netxxus":
                return "XUPER";
            case "com.amazon.amazonvideo.livingroom":
            case "com.amazon.avod":
                return "PRIME VIDEO";
            case "com.wbd.stream":
            case "com.hbo.hbonow":
                return "MAX";
            case "com.univision.prendetv":
                return "VIX";
            case "com.crunchyroll.crunchyroid":
                return "CRUNCHYROLL";
            case "tv.pluto.android":
                return "PLUTO TV";
            case "com.apple.atve.androidtv.appletv":
                return "APPLE TV+";
            case "com.plexapp.android":
                return "PLEX";
            case "org.xbmc.kodi":
                return "KODI";
            case "com.spotify.tv.android":
                return "SPOTIFY";
            default:
                String label = resolveAppLabel(ctx.getPackageManager(), pkg).toUpperCase(Locale.ROOT);
                return label.length() > 14 ? label.substring(0, 14) : label;
        }
    }

    public static int resolveAccentColor(String pkg) {
        if (pkg == null) return 0xFF38BDF8;
        switch (pkg) {
            case "com.netflix.ninja":
                return 0xFFE50914;
            case "com.disney.disneyplus":
                return 0xFF1D7BF6;
            case "com.google.android.youtube.tv":
            case "com.google.android.youtube.tvkids":
            case "com.liskovsoft.smarttubetv":
            case "com.liskovsoft.smarttubetv.beta":
                return 0xFFFF2626;
            case "com.xuper.netxxus":
                return 0xFFA855F7;
            case "com.amazon.amazonvideo.livingroom":
            case "com.amazon.avod":
                return 0xFF00A8E1;
            case "com.wbd.stream":
            case "com.hbo.hbonow":
                return 0xFF6366F1;
            case "com.univision.prendetv":
                return 0xFFFF6B00;
            case "com.crunchyroll.crunchyroid":
                return 0xFFF47521;
            case "tv.pluto.android":
                return 0xFFFACC15;
            case "com.spotify.tv.android":
                return 0xFF1DB954;
            default:
                return 0xFF38BDF8;
        }
    }

    private static String defaultTitleForApp(String pkg, String fallbackLabel) {
        if ("com.netflix.ninja".equals(pkg)) return "Reanudar en Netflix";
        if ("com.disney.disneyplus".equals(pkg)) return "Reanudar en Disney+";
        if ("com.google.android.youtube.tv".equals(pkg)) return "Continuar en YouTube";
        if ("com.xuper.netxxus".equals(pkg)) return "Continuar en Xuper";
        if ("com.amazon.amazonvideo.livingroom".equals(pkg) || "com.amazon.avod".equals(pkg)) return "Reanudar en Prime Video";
        if ("com.wbd.stream".equals(pkg)) return "Reanudar en Max";
        if ("com.univision.prendetv".equals(pkg)) return "Reanudar en ViX";
        return "Continuar en " + fallbackLabel;
    }

    private static float defaultProgressForApp(String pkg) {
        if ("com.netflix.ninja".equals(pkg)) return 0.64f;
        if ("com.disney.disneyplus".equals(pkg)) return 0.38f;
        if ("com.google.android.youtube.tv".equals(pkg)) return 0.52f;
        if ("com.xuper.netxxus".equals(pkg)) return 0.71f;
        return 0.45f;
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR_NAME);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String saveArtwork(Context ctx, String pkg, Bitmap bmp) {
        try {
            String safe = pkg.replaceAll("[^a-zA-Z0-9._-]", "_");
            File out = new File(dir(ctx), "art_" + safe + ".jpg");
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            Bitmap scaled = bmp;
            if (w > 640) {
                int nw = 640;
                int nh = Math.max(1, Math.round(h * (640f / w)));
                scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 86, fos);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Item> readSaved(Context ctx) {
        File f = new File(dir(ctx), STATE_FILE);
        List<Item> list = new ArrayList<>();
        if (!f.isFile()) return list;
        try (JsonReader r = new JsonReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            r.beginArray();
            while (r.hasNext()) {
                r.beginObject();
                String pkg = null, appName = null, title = null, subtitle = null, artPath = null, intentUri = null;
                int accent = 0xFF38BDF8;
                float progress = 0.4f;
                long updatedAt = 0L;
                boolean hasLive = false;
                while (r.hasNext()) {
                    String k = r.nextName();
                    switch (k) {
                        case "pkg": pkg = r.nextString(); break;
                        case "appName": appName = r.nextString(); break;
                        case "accent": accent = r.nextInt(); break;
                        case "title": title = r.nextString(); break;
                        case "subtitle": subtitle = r.nextString(); break;
                        case "progress": progress = (float) r.nextDouble(); break;
                        case "artPath": artPath = r.nextString(); break;
                        case "intentUri": intentUri = r.nextString(); break;
                        case "updatedAt": updatedAt = r.nextLong(); break;
                        case "hasLive": hasLive = r.nextBoolean(); break;
                        default: r.skipValue(); break;
                    }
                }
                r.endObject();
                if (pkg != null && title != null) {
                    if (appName == null) appName = resolveAppBadge(ctx, pkg);
                    list.add(new Item(pkg, appName, accent, title,
                            subtitle != null ? subtitle : "", progress, artPath, intentUri, updatedAt, hasLive));
                }
            }
            r.endArray();
        } catch (Exception ignored) {
        }
        return list;
    }

    private static void writeSaved(Context ctx, List<Item> list) {
        File f = new File(dir(ctx), STATE_FILE);
        try (JsonWriter w = new JsonWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            w.beginArray();
            for (Item it : list) {
                w.beginObject();
                w.name("pkg").value(it.pkg);
                w.name("appName").value(it.appName);
                w.name("accent").value(it.accentColor);
                w.name("title").value(it.title);
                w.name("subtitle").value(it.subtitle != null ? it.subtitle : "");
                w.name("progress").value(it.progress);
                if (it.artPath != null) w.name("artPath").value(it.artPath);
                if (it.intentUri != null) w.name("intentUri").value(it.intentUri);
                w.name("updatedAt").value(it.updatedAt);
                w.name("hasLive").value(it.hasLiveMetadata);
                w.endObject();
            }
            w.endArray();
        } catch (Exception ignored) {
        }
    }
}
