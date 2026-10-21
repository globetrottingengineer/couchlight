package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather line in the header (config.xml weather). The TV has no GPS, so its place comes from its internet
 * connection (ipapi.co, kept for a day), and the weather for that place from Open-Meteo (no account, kept for
 * half an hour). The last answer is stored, so the line shows at once after a restart and while offline.
 */
final class Weather {
    static final String TAG = "TvHome.Weather";
    static final long WEATHER_TTL_MS = 30L * 60 * 1000;
    static final long PLACE_TTL_MS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 8000;

    /** What the header shows. */
    static final class Now {
        final int temp, high, low, code;
        final boolean day;
        final String place;

        Now(int temp, int high, int low, int code, boolean day, String place) {
            this.temp = temp;
            this.high = high;
            this.low = low;
            this.code = code;
            this.day = day;
            this.place = place;
        }
    }

    interface Listener {
        void onWeather(Now now);
    }

    private final SharedPreferences prefs;
    private final Listener listener;
    private final boolean fahrenheit;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TvHome.Weather");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private boolean running;
    private volatile boolean closed;

    Weather(Context ctx, Listener listener) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
        this.listener = listener;
        // The countries that still use Fahrenheit day to day.
        String c = Locale.getDefault().getCountry();
        this.fahrenheit = c.equals("US") || c.equals("LR") || c.equals("MM") || c.equals("BS") || c.equals("KY");
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }

    boolean fahrenheit() {
        return fahrenheit;
    }

    /** The stored weather, or null before the first answer. */
    Now cached() {
        if (!prefs.contains("at")) return null;
        return new Now(prefs.getInt("temp", 0), prefs.getInt("high", 0), prefs.getInt("low", 0),
                prefs.getInt("code", 0), prefs.getBoolean("day", true), prefs.getString("place", ""));
    }

    /** Fetches new weather when the stored one is older than half an hour; cheap to call every minute. */
    void refresh() {
        if (closed || running) return;
        if (System.currentTimeMillis() - prefs.getLong("at", 0) < WEATHER_TTL_MS
                && prefs.getBoolean("fahrenheit", false) == fahrenheit) return;
        running = true;
        worker.execute(() -> {
            Now now = null;
            try {
                now = fetch();
            } catch (Exception e) {
                Log.w(TAG, "weather failed: " + e);
            }
            final Now result = now;
            main.post(() -> {
                running = false;
                if (closed || result == null) return;
                listener.onWeather(result);
            });
        });
    }

    private Now fetch() throws Exception {
        double lat, lon;
        String place;
        if (System.currentTimeMillis() - prefs.getLong("placeAt", 0) < PLACE_TTL_MS && prefs.contains("lat")) {
            lat = Double.longBitsToDouble(prefs.getLong("lat", 0));
            lon = Double.longBitsToDouble(prefs.getLong("lon", 0));
            place = prefs.getString("place", "");
        } else {
            JSONObject ip = new JSONObject(get("https://ipapi.co/json/"));
            if (!ip.has("latitude")) throw new IOException("no location: " + ip.optString("reason"));
            lat = ip.getDouble("latitude");
            lon = ip.getDouble("longitude");
            place = ip.optString("city", "");
            prefs.edit().putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon))
                    .putString("place", place).putLong("placeAt", System.currentTimeMillis()).apply();
        }
        JSONObject w = new JSONObject(get(forecastUrl(lat, lon, fahrenheit)));
        JSONObject cur = w.getJSONObject("current");
        JSONObject daily = w.getJSONObject("daily");
        Now now = new Now((int) Math.round(cur.getDouble("temperature_2m")),
                (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1, place);
        prefs.edit().putInt("temp", now.temp).putInt("high", now.high).putInt("low", now.low).putInt("code", now.code)
                .putBoolean("day", now.day).putBoolean("fahrenheit", fahrenheit)
                .putLong("at", System.currentTimeMillis()).apply();
        return now;
    }

    static String forecastUrl(double lat, double lon, boolean fahrenheit) {
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                + "&forecast_days=1&timezone=auto%s", lat, lon, fahrenheit ? "&temperature_unit=fahrenheit" : "");
    }

    /** WMO weather code (Open-Meteo's weather_code) to the icon and words the header shows. */
    static int kind(int code) {
        if (code == 0) return CLEAR;
        if (code == 1 || code == 2) return PARTLY;
        if (code == 3) return CLOUDY;
        if (code == 45 || code == 48) return FOG;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code >= 95) return THUNDER;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return RAIN;
        return CLOUDY;
    }

    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, RAIN = 4, SNOW = 5, THUNDER = 6;

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "CleanYourTV-launcher");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 256 * 1024) throw new IOException("too large");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
