package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches and caches regional weather in Celsius using IP geolocation + Open-Meteo.
 * Runs all network work on a single background thread and updates the frosted glass pill on the UI thread.
 */
final class WeatherUpdater {
    private static final String PREFS = "weather_cache";
    private static final long REFRESH_MS = 20 * 60 * 1000L; // 20 minutes
    private static final long RETRY_MS = 60 * 1000L;        // 1 minute on failure

    private final Context ctx;
    private final View pill;
    private final WeatherIconView iconView;
    private final TextView tempView;
    private final TextView condView;
    private final TextView detailsView;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private long lastSuccessMs = 0L;
    private boolean fetching = false;

    WeatherUpdater(Context ctx, View pill, WeatherIconView iconView,
                   TextView tempView, TextView condView, TextView detailsView) {
        this.ctx = ctx.getApplicationContext();
        this.pill = pill;
        this.iconView = iconView;
        this.tempView = tempView;
        this.condView = condView;
        this.detailsView = detailsView;
        loadCached();
    }

    private void loadCached() {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int temp = sp.getInt("temp", 27);
        int max = sp.getInt("max", 28);
        int min = sp.getInt("min", 17);
        int hum = sp.getInt("hum", 50);
        int code = sp.getInt("code", 1);
        boolean isDay = sp.getBoolean("is_day", true);
        String city = sp.getString("city", "Monterrey");
        lastSuccessMs = sp.getLong("ts", 0L);
        applyToViews(temp, max, min, hum, code, isDay, city);
    }

    void updateIfNeeded() {
        if (fetching) return;
        long now = System.currentTimeMillis();
        if (lastSuccessMs > 0 && (now - lastSuccessMs) < REFRESH_MS) return;
        fetching = true;
        executor.execute(this::fetchInBackground);
    }

    private void fetchInBackground() {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        double lat = Double.longBitsToDouble(sp.getLong("lat", Double.doubleToLongBits(25.7524)));
        double lon = Double.longBitsToDouble(sp.getLong("lon", Double.doubleToLongBits(-100.4092)));
        String city = sp.getString("city", "Monterrey");

        // 1. Detect region/city automatically via IP geolocation
        try {
            String geoJson = httpGet("https://ipwho.is/");
            if (geoJson != null) {
                JSONObject geo = new JSONObject(geoJson);
                if (geo.optBoolean("success", true) && geo.has("latitude") && geo.has("longitude")) {
                    lat = geo.getDouble("latitude");
                    lon = geo.getDouble("longitude");
                    String detectedCity = geo.optString("city", "").trim();
                    if (!detectedCity.isEmpty()) city = detectedCity;
                }
            }
        } catch (Exception ignored) {
            try {
                String geoJson2 = httpGet("https://ipapi.co/json/");
                if (geoJson2 != null) {
                    JSONObject geo = new JSONObject(geoJson2);
                    if (geo.has("latitude") && geo.has("longitude")) {
                        lat = geo.getDouble("latitude");
                        lon = geo.getDouble("longitude");
                        String detectedCity = geo.optString("city", "").trim();
                        if (!detectedCity.isEmpty()) city = detectedCity;
                    }
                }
            } catch (Exception ignored2) {
            }
        }

        // 2. Fetch live Celsius weather from Open-Meteo
        try {
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,relative_humidity_2m,weather_code,is_day"
                            + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                    lat, lon);
            String weatherJson = httpGet(url);
            if (weatherJson != null) {
                JSONObject root = new JSONObject(weatherJson);
                JSONObject current = root.getJSONObject("current");
                int temp = (int) Math.round(current.getDouble("temperature_2m"));
                int hum = current.optInt("relative_humidity_2m", 50);
                int code = current.optInt("weather_code", 0);
                boolean isDay = current.optInt("is_day", 1) == 1;

                int max = temp;
                int min = temp;
                JSONObject daily = root.optJSONObject("daily");
                if (daily != null) {
                    JSONArray maxArr = daily.optJSONArray("temperature_2m_max");
                    JSONArray minArr = daily.optJSONArray("temperature_2m_min");
                    if (maxArr != null && maxArr.length() > 0) max = (int) Math.round(maxArr.getDouble(0));
                    if (minArr != null && minArr.length() > 0) min = (int) Math.round(minArr.getDouble(0));
                }

                long now = System.currentTimeMillis();
                sp.edit()
                        .putLong("lat", Double.doubleToLongBits(lat))
                        .putLong("lon", Double.doubleToLongBits(lon))
                        .putString("city", city)
                        .putInt("temp", temp)
                        .putInt("max", max)
                        .putInt("min", min)
                        .putInt("hum", hum)
                        .putInt("code", code)
                        .putBoolean("is_day", isDay)
                        .putLong("ts", now)
                        .apply();

                final int fTemp = temp, fMax = max, fMin = min, fHum = hum, fCode = code;
                final boolean fDay = isDay;
                final String fCity = city;
                uiHandler.post(() -> {
                    lastSuccessMs = now;
                    fetching = false;
                    applyToViews(fTemp, fMax, fMin, fHum, fCode, fDay, fCity);
                });
                return;
            }
        } catch (Exception ignored) {
        }

        uiHandler.post(() -> {
            fetching = false;
            // Retry in 1 minute if network wasn't ready yet right after TV boot
            uiHandler.postDelayed(this::updateIfNeeded, RETRY_MS);
        });
    }

    private void applyToViews(int temp, int max, int min, int hum, int code, boolean isDay, String city) {
        if (pill == null) return;
        iconView.setWeather(code, isDay);
        tempView.setText(String.format(Locale.getDefault(), "%d°C", temp));
        String cond = weatherDescriptionEs(code, isDay);
        if (city != null && !city.isEmpty()) {
            condView.setText(String.format(Locale.getDefault(), "%s  ·  %s", cond, city.toUpperCase(Locale.getDefault())));
        } else {
            condView.setText(cond);
        }
        detailsView.setText(String.format(Locale.getDefault(),
                "MÁX %d°C   ·   MÍN %d°C   ·   HUMEDAD %d%%", max, min, hum));
    }

    private static String weatherDescriptionEs(int code, boolean isDay) {
        if (code == 0) return isDay ? "DESPEJADO" : "NOCHE DESPEJADA";
        if (code == 1) return "MAYORMENTE DESPEJADO";
        if (code == 2) return "PARCIALMENTE NUBLADO";
        if (code == 3) return "NUBLADO";
        if (code == 45 || code == 48) return "NEBLINA";
        if (code >= 51 && code <= 57) return "LLOVIZNA";
        if (code >= 61 && code <= 67) return "LLUVIA";
        if (code >= 71 && code <= 77) return "NIEVE";
        if (code >= 80 && code <= 82) return "CHUBASCOS";
        if (code >= 95) return "TORMENTA ELÉCTRICA";
        return "PARCIALMENTE NUBLADO";
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "CleanYourTV-Launcher/1.0");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
