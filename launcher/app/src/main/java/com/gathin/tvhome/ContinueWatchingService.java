package com.gathin.tvhome;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens to active MediaSessions and media notifications across streaming apps (Netflix, Disney+,
 * YouTube, Prime Video, Max, Xuper, etc.) to populate the "Continuar viendo" row with real titles,
 * remaining minutes, progress bars, and episode/video artwork.
 */
public class ContinueWatchingService extends NotificationListenerService {

    private MediaSessionManager sessionManager;
    private ComponentName componentName;
    private final Map<MediaController, MediaController.Callback> callbacks = new HashMap<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            this::attachControllers;

    @Override
    public void onCreate() {
        super.onCreate();
        componentName = new ComponentName(this, ContinueWatchingService.class);
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        tryRegisterSessionListener();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        tryRegisterSessionListener();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    inspectNotification(sbn);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        inspectNotification(sbn);
        try {
            if (sessionManager != null && componentName != null) {
                attachControllers(sessionManager.getActiveSessions(componentName));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (sessionManager != null) {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            }
        } catch (Throwable ignored) {
        }
        clearControllers();
    }

    private void tryRegisterSessionListener() {
        if (sessionManager == null || componentName == null) return;
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Throwable ignored) {
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, componentName);
            attachControllers(sessionManager.getActiveSessions(componentName));
        } catch (Throwable ignored) {
        }
    }

    private void clearControllers() {
        for (Map.Entry<MediaController, MediaController.Callback> e : callbacks.entrySet()) {
            try {
                e.getKey().unregisterCallback(e.getValue());
            } catch (Throwable ignored) {
            }
        }
        callbacks.clear();
    }

    private void attachControllers(List<MediaController> controllers) {
        clearControllers();
        if (controllers == null) return;
        for (final MediaController controller : controllers) {
            if (controller == null) continue;
            final String pkg = controller.getPackageName();
            if (!ContinueWatchingStore.isMediaPackage(this, pkg)) continue;

            ContinueWatchingStore.setLiveSessionIntent(pkg, controller.getSessionActivity());
            extractFromController(controller);

            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    extractFromController(controller);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    extractFromController(controller);
                }
            };
            try {
                controller.registerCallback(cb);
                callbacks.put(controller, cb);
            } catch (Throwable ignored) {
            }
        }
    }

    private void extractFromController(MediaController controller) {
        if (controller == null) return;
        try {
            String pkg = controller.getPackageName();
            if (!ContinueWatchingStore.isMediaPackage(this, pkg)) return;
            ContinueWatchingStore.setLiveSessionIntent(pkg, controller.getSessionActivity());

            MediaMetadata meta = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            if (meta == null) return;

            String title = firstNonEmpty(
                    meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            );
            if (title == null || title.isEmpty()) return;

            String sub = firstNonEmpty(
                    meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            );

            long duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = state != null ? state.getPosition() : -1L;
            float progress = 0f;
            if (duration > 0 && position >= 0 && position <= duration) {
                progress = Math.max(0.08f, Math.min(0.95f, (float) position / (float) duration));
                long remMin = Math.max(1L, (duration - position) / 60000L);
                String remText = "Faltan " + remMin + " min";
                sub = (sub != null && !sub.isEmpty()) ? (sub + " · " + remText) : remText;
            } else if (sub == null || sub.isEmpty()) {
                sub = "En pausa · Toca OK para reanudar";
            }

            Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);

            ContinueWatchingStore.recordMediaSession(getApplicationContext(), pkg, title, sub, progress, art);
        } catch (Throwable ignored) {
        }
    }

    private void inspectNotification(StatusBarNotification sbn) {
        if (sbn == null) return;
        try {
            String pkg = sbn.getPackageName();
            if (!ContinueWatchingStore.isMediaPackage(this, pkg)) return;
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null) return;

            if (n.contentIntent != null) {
                ContinueWatchingStore.setLiveSessionIntent(pkg, n.contentIntent);
            }

            Bundle ex = n.extras;
            CharSequence csTitle = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence csText = ex.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence csSub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT);
            String title = csTitle != null ? csTitle.toString().trim() : "";
            if (title.isEmpty()) return;

            String subtitle = csText != null ? csText.toString().trim()
                    : (csSub != null ? csSub.toString().trim() : "En reproducción · Toca OK");

            Bitmap largeBmp = null;
            Icon largeIcon = n.getLargeIcon();
            if (largeIcon != null) {
                Drawable d = largeIcon.loadDrawable(this);
                largeBmp = drawableToBitmap(d);
            }
            ContinueWatchingStore.recordMediaSession(getApplicationContext(), pkg, title, subtitle, 0f, largeBmp);
        } catch (Throwable ignored) {
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private static Bitmap drawableToBitmap(Drawable d) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable) {
            return ((BitmapDrawable) d).getBitmap();
        }
        int w = Math.max(1, d.getIntrinsicWidth());
        int h = Math.max(1, d.getIntrinsicHeight());
        if (w < 32 || h < 32) return null;
        Bitmap bmp = Bitmap.createBitmap(Math.min(w, 640), Math.min(h, 360), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, c.getWidth(), c.getHeight());
        d.draw(c);
        return bmp;
    }
}
