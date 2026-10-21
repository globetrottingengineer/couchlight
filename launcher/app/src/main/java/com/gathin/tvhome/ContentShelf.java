package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the cinematic shelf shows behind a focused dock app, in this order:
 * - what the app is playing or has paused, from Android's media sessions (needs {@link MediaListener} enabled);
 * - what is trending on that streaming service in the owner's country, from TMDB (needs a key in config).
 * Google TV's own rows come from TvProvider's preview programs, which only firmware apps may read
 * (ACCESS_ALL_EPG_DATA is signature|privileged), so a sideloaded launcher has to bring its own.
 *
 * Everything off the main thread runs on one worker; results come back through {@link Listener} on the
 * main thread. Trending lists are kept on disk for {@link #TRENDING_TTL_MS}, images in the cache folder.
 */
final class ContentShelf {
    static final String TAG = "TvHome.Content";
    static final long TRENDING_TTL_MS = 6L * 60 * 60 * 1000;
    /** Titles per app: enough to rotate through for a while, few enough to download on a TV. */
    static final int MAX_TRENDING = 8;
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    /** Decoded backdrops at TV width in RGB_565, about 1.3 MB each. */
    private static final int IMAGE_CACHE_BYTES = 24 * 1024 * 1024;

    static final int NOW_PLAYING = 0, PAUSED = 1, TRENDING = 2;

    static final class Item {
        final int kind;
        final String title;
        final String subtitle;
        /** http(s) or content URI of the image; null when the item carries its bitmap. */
        final String imageUrl;
        /** A bitmap handed over by a media session, already in memory. */
        final Bitmap bitmap;

        /** TMDB id and "tv" or "movie" of a trending title, to find its page in the app; 0 and null otherwise. */
        final int tmdbId;
        final String media;
        /** A trending title's facts for the shelf's info line: first year, up to two genres, TMDB rating (0: none). */
        final String year;
        final String genres;
        final double rating;

        Item(int kind, String title, String subtitle, String imageUrl, Bitmap bitmap) {
            this(kind, title, subtitle, imageUrl, bitmap, 0, null, null, null, 0);
        }

        Item(int kind, String title, String subtitle, String imageUrl, Bitmap bitmap, int tmdbId, String media,
             String year, String genres, double rating) {
            this.kind = kind;
            this.title = title;
            this.subtitle = subtitle;
            this.imageUrl = imageUrl;
            this.bitmap = bitmap;
            this.tmdbId = tmdbId;
            this.media = media;
            this.year = year;
            this.genres = genres;
            this.rating = rating;
        }
    }

    interface LinkCallback {
        /** The intent that opens the title in the app, or null when none was found. */
        void onLink(Intent intent);
    }

    interface Listener {
        /** New content or a downloaded image for this package; ask {@link #itemsFor} again. */
        void onContentChanged(String pkg);
    }

    /**
     * TMDB watch provider ids by TV app package. Values are TMDB's "with_watch_providers" syntax, so
     * Prime Video can name both of its ids (9 for Prime Video, 119 for the Amazon Prime Video catalogue).
     */
    static final Map<String, String> PROVIDERS;

    static {
        Map<String, String> p = new HashMap<>();
        p.put("com.netflix.ninja", "8");
        p.put("com.amazon.amazonvideo.livingroom", "9|119");
        p.put("com.apple.atve.androidtv.appletv", "350");
        p.put("com.disney.disneyplus", "337");
        p.put("com.wbd.stream", "1899");
        p.put("com.hbo.hbonow", "1899");
        p.put("com.cbs.ott", "531");
        p.put("com.hulu.livingroomplus", "15");
        p.put("com.peacocktv.peacockandroid", "386");
        p.put("com.crunchyroll.crunchyroid", "283");
        p.put("in.startv.hotstar", "122");
        p.put("com.mubi", "11");
        p.put("com.sky.skyticket.android.tv", "30");
        p.put("de.rtli.tvnow", "298");
        // Free public broadcasters: TMDB lists them as monetization "free", hence the types in discoverUrl.
        p.put("com.zdf.android.mediathek", "537");
        p.put("de.swr.avp.ard.tv", "219");
        p.put("tv.arte.plus7", "234");
        PROVIDERS = Collections.unmodifiableMap(p);
    }

    private final Context ctx;
    private final String key;
    private final String region;
    private final String language;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TvHome.Content");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Map<String, List<Item>> trending = new HashMap<>();
    private final Map<String, Long> fetchedAt = new HashMap<>();
    /** Packages and image URLs with a job on the worker, so a focus storm does not queue duplicates. */
    private final Set<String> pending = new HashSet<>();
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(IMAGE_CACHE_BYTES) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private Listener listener;
    private volatile boolean closed;

    ContentShelf(Context ctx, String tmdbKey, String region) {
        this.ctx = ctx.getApplicationContext();
        this.key = tmdbKey == null ? "" : tmdbKey.trim();
        Locale locale = Locale.getDefault();
        String r = region == null || region.trim().isEmpty() ? locale.getCountry() : region.trim();
        this.region = r.isEmpty() ? "US" : r.toUpperCase(Locale.ROOT);
        this.language = locale.getLanguage().isEmpty() ? "en-US" : locale.getLanguage() + "-" + this.region;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void close() {
        closed = true;
        listener = null;
        worker.shutdownNow();
        images.evictAll();
    }

    /** True when this app can have trending content: TMDB knows its service and a key is set. */
    boolean hasTrending(String pkg) {
        return !key.isEmpty() && PROVIDERS.containsKey(pkg);
    }

    /**
     * Items for this app right now: its media session first, then trending titles. Returns what is known
     * at once and starts a refresh in the background when the trending list is missing or old.
     */
    List<Item> itemsFor(String pkg) {
        List<Item> out = new ArrayList<>(nowPlaying(pkg));
        if (hasTrending(pkg)) {
            List<Item> t = trending.get(pkg);
            Long at = fetchedAt.get(pkg);
            if (t == null || at == null || System.currentTimeMillis() - at > TRENDING_TTL_MS) refreshTrending(pkg);
            if (t != null) out.addAll(t);
        }
        return out;
    }

    /** The item's image when it is in memory; otherwise null, and a download that calls back for pkg. */
    Bitmap imageFor(String pkg, Item item) {
        if (item.bitmap != null) return item.bitmap;
        if (item.imageUrl == null) return null;
        Bitmap b = images.get(item.imageUrl);
        if (b != null) return b;
        loadImage(pkg, item.imageUrl);
        return null;
    }

    /** Starts downloading the images of these items, so rotating to the next title does not wait. */
    void prefetch(String pkg, List<Item> items, int from, int count) {
        for (int i = 0; i < count && !items.isEmpty(); i++) {
            Item it = items.get((from + i) % items.size());
            if (it.bitmap == null && it.imageUrl != null && images.get(it.imageUrl) == null) loadImage(pkg, it.imageUrl);
        }
    }

    // ---- media sessions -------------------------------------------------------------------------

    private List<Item> nowPlaying(String pkg) {
        MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return Collections.emptyList();
        List<MediaController> sessions;
        try {
            sessions = msm.getActiveSessions(new ComponentName(ctx, MediaListener.class));
        } catch (SecurityException e) {
            return Collections.emptyList();   // the notification listener is not enabled: no now playing
        }
        List<Item> out = new ArrayList<>();
        for (MediaController c : sessions) {
            if (!pkg.equals(c.getPackageName())) continue;
            PlaybackState state = c.getPlaybackState();
            int s = state == null ? PlaybackState.STATE_NONE : state.getState();
            boolean playing = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING;
            if (!playing && s != PlaybackState.STATE_PAUSED) continue;
            MediaMetadata md = c.getMetadata();
            if (md == null) continue;
            String title = first(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) continue;
            String subtitle = first(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM);
            Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            String uri = art != null ? null : first(md, MediaMetadata.METADATA_KEY_ART_URI,
                    MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            out.add(new Item(playing ? NOW_PLAYING : PAUSED, title, subtitle, uri, art));
        }
        return out;
    }

    private static String first(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    // ---- TMDB trending --------------------------------------------------------------------------

    private void refreshTrending(String pkg) {
        if (closed || !pending.add("t:" + pkg)) return;
        String providers = PROVIDERS.get(pkg);
        worker.execute(() -> {
            List<Item> items = readCached(pkg);
            boolean fresh = items != null;
            if (!fresh) {
                try {
                    items = fetchTrending(providers);
                    writeCached(pkg, items);
                } catch (Exception e) {
                    Log.w(TAG, "trending for " + pkg + " failed: " + e);
                    items = readStale(pkg);
                }
            }
            final List<Item> result = items == null ? Collections.emptyList() : items;
            main.post(() -> {
                pending.remove("t:" + pkg);
                if (closed) return;
                trending.put(pkg, result);
                // A failed fetch waits a full TTL too: no retry storm on a TV that is offline.
                fetchedAt.put(pkg, System.currentTimeMillis());
                Listener l = listener;
                if (l != null) l.onContentChanged(pkg);
            });
        });
    }

    private List<Item> fetchTrending(String providers) throws Exception {
        List<JSONObject> rows = new ArrayList<>();
        // Popularity alone puts the whole back catalogue first (a decade-old procedural); only recent shows
        // and films make the row read like what is on now.
        LocalDate today = LocalDate.now();
        for (String media : new String[]{"tv", "movie"}) {
            String since = (media.equals("tv") ? today.minusYears(2) : today.minusMonths(18)).toString();
            JSONArray results = new JSONObject(get(discoverUrl(media, providers, region, language, key, since), key))
                    .optJSONArray("results");
            if (results == null) continue;
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r != null && !r.optString("backdrop_path").isEmpty() && !r.isNull("backdrop_path")) {
                    rows.add(r.put("_media", media));
                }
            }
        }
        // Shows and films in one list by TMDB popularity, as a "trending" row would mix them.
        Collections.sort(rows, (a, b) -> Double.compare(b.optDouble("popularity"), a.optDouble("popularity")));
        List<Item> out = new ArrayList<>();
        Set<String> titles = new HashSet<>();
        for (JSONObject r : rows) {
            String title = r.optString("name", r.optString("title", "")).trim();
            if (title.isEmpty() || !titles.add(title)) continue;
            String overview = r.optString("overview", "").trim();
            String date = r.optString("first_air_date", r.optString("release_date", ""));
            JSONArray ids = r.optJSONArray("genre_ids");
            List<Integer> genreIds = new ArrayList<>();
            for (int i = 0; ids != null && i < ids.length(); i++) genreIds.add(ids.optInt(i));
            double rating = r.optInt("vote_count") >= 20 ? r.optDouble("vote_average", 0) : 0;   // a few votes say little
            out.add(new Item(TRENDING, title, overview.isEmpty() ? null : overview,
                    imageUrl(r.optString("backdrop_path")), null, r.optInt("id"), r.optString("_media"),
                    date.length() >= 4 ? date.substring(0, 4) : null, genreNames(genreIds, 2), rating));
            if (out.size() >= MAX_TRENDING) break;
        }
        return out;
    }

    /** TMDB's genre ids for films and series (the two lists share most ids) to their English names. */
    private static final Map<Integer, String> GENRES = new HashMap<>();

    static {
        Object[] g = {28, "Action", 12, "Adventure", 16, "Animation", 35, "Comedy", 80, "Crime", 99, "Documentary",
                18, "Drama", 10751, "Family", 14, "Fantasy", 36, "History", 27, "Horror", 10402, "Music",
                9648, "Mystery", 10749, "Romance", 878, "Science Fiction", 10770, "TV Movie", 53, "Thriller",
                10752, "War", 37, "Western", 10759, "Action & Adventure", 10762, "Kids", 10763, "News",
                10764, "Reality", 10765, "Sci-Fi & Fantasy", 10766, "Soap", 10767, "Talk", 10768, "War & Politics"};
        for (int i = 0; i < g.length; i += 2) GENRES.put((Integer) g[i], (String) g[i + 1]);
    }

    /** The first max known genre names, "Drama, Comedy"; null when none is known. */
    static String genreNames(List<Integer> ids, int max) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Integer id : ids) {
            String name = GENRES.get(id);
            if (name == null || n >= max) continue;
            if (n++ > 0) b.append(", ");
            b.append(name);
        }
        return n == 0 ? null : b.toString();
    }

    /** since: earliest first air date (tv) or release date (movie), yyyy-MM-dd; null for no limit. */
    static String discoverUrl(String media, String providers, String region, String language, String key, String since) {
        StringBuilder u = new StringBuilder("https://api.themoviedb.org/3/discover/").append(media)
                .append("?sort_by=popularity.desc&include_adult=false&page=1");
        if (since != null) u.append(media.equals("tv") ? "&first_air_date.gte=" : "&primary_release_date.gte=").append(since);
        u.append("&watch_region=").append(enc(region))
                .append("&with_watch_providers=").append(enc(providers))
                .append("&with_watch_monetization_types=").append(enc("flatrate|free|ads"))
                .append("&language=").append(enc(language));
        if (!isBearer(key)) u.append("&api_key=").append(enc(key));
        return u.toString();
    }

    /** A v4 read access token is a JWT, which always starts like this; a v3 key is 32 hex digits. */
    static boolean isBearer(String key) {
        return key != null && key.startsWith("eyJ");
    }

    static String imageUrl(String backdropPath) {
        return "https://image.tmdb.org/t/p/w1280" + backdropPath;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            return s;
        }
    }

    private static String get(String url, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            if (isBearer(key)) c.setRequestProperty("Authorization", "Bearer " + key);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            return new String(readAll(c.getInputStream(), MAX_JSON_BYTES), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private File cacheFile(String pkg) {
        File dir = new File(ctx.getFilesDir(), "content");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, pkg + "." + region + ".v3.json");   // v3: items carry their TMDB id, year, genres, rating
    }

    /** The cached list while it is younger than the TTL, else null. */
    private List<Item> readCached(String pkg) {
        File f = cacheFile(pkg);
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > TRENDING_TTL_MS) return null;
        return readStale(pkg);
    }

    /** The cached list whatever its age (offline fallback), or null. */
    private List<Item> readStale(String pkg) {
        File f = cacheFile(pkg);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONArray a = new JSONArray(new String(readAll(in, MAX_JSON_BYTES), StandardCharsets.UTF_8));
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Item(TRENDING, o.getString("t"), o.isNull("s") ? null : o.optString("s", null),
                        o.getString("u"), null, o.optInt("i"), o.isNull("m") ? null : o.optString("m", null),
                        o.isNull("y") ? null : o.optString("y", null), o.isNull("g") ? null : o.optString("g", null),
                        o.optDouble("r", 0)));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCached(String pkg, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                a.put(new JSONObject().put("t", it.title).put("s", it.subtitle == null ? JSONObject.NULL : it.subtitle)
                        .put("u", it.imageUrl).put("i", it.tmdbId).put("m", it.media == null ? JSONObject.NULL : it.media)
                        .put("y", it.year == null ? JSONObject.NULL : it.year)
                        .put("g", it.genres == null ? JSONObject.NULL : it.genres).put("r", it.rating));
            }
            try (FileOutputStream out = new FileOutputStream(cacheFile(pkg))) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "could not cache trending for " + pkg + ": " + e);
        }
    }

    // ---- opening a title in its app --------------------------------------------------------------

    private static final String JUSTWATCH = "https://apis.justwatch.com/graphql";
    private static final String JUSTWATCH_QUERY = "query Titles($country: Country!, $language: Language!, $first: Int!,"
            + " $filter: TitleFilter) { popularTitles(country: $country, first: $first, filter: $filter) { edges { node {"
            + " objectType content(country: $country, language: $language) { title externalIds { tmdbId } }"
            + " offers(country: $country, platform: WEB) { monetizationType standardWebURL } } } } }";
    /** Searches the app itself where it takes a search link, for titles JustWatch could not place. */
    static final Map<String, String> SEARCH_LINKS;

    static {
        Map<String, String> s = new HashMap<>();
        s.put("com.amazon.amazonvideo.livingroom", "https://app.primevideo.com/search?phrase=");
        s.put("com.google.android.youtube.tv", "https://www.youtube.com/results?search_query=");
        SEARCH_LINKS = Collections.unmodifiableMap(s);
    }

    /** Resolved links per app and title, so a second OK on the same card opens it at once. */
    private final Map<String, Intent> links = new HashMap<>();

    /**
     * Finds the link that opens this title's own page in this app, the way Google TV's cards do. TMDB knows no
     * Netflix, Apple or Amazon ids, so the title is looked up on JustWatch (by its TMDB id, else its name) and the
     * first of its streaming offers whose web link the app itself answers is taken: tv.apple.com/…/umc.cmc.… for
     * Apple TV, netflix.com/title/… for Netflix, watch.amazon.de/detail?… for Prime Video. Failing that, the app's
     * own search for the title where it has a search link; else null, and the caller opens the app.
     */
    void resolveTitle(String pkg, Item item, LinkCallback cb) {
        String cacheKey = pkg + '\n' + item.title;
        if (links.containsKey(cacheKey)) {
            cb.onLink(links.get(cacheKey));
            return;
        }
        if (closed) return;
        PackageManager pm = ctx.getPackageManager();
        worker.execute(() -> {
            Intent found = null;
            try {
                found = titleLink(pkg, item, pm);
            } catch (Exception e) {
                Log.w(TAG, "title link for " + item.title + " failed: " + e);
            }
            if (found == null && SEARCH_LINKS.containsKey(pkg)) {
                found = viewIn(pkg, SEARCH_LINKS.get(pkg) + enc(item.title), pm);
            }
            final Intent result = found;
            main.post(() -> {
                if (closed) return;
                links.put(cacheKey, result);
                cb.onLink(result);
            });
        });
    }

    private Intent titleLink(String pkg, Item item, PackageManager pm) throws Exception {
        JSONObject vars = new JSONObject().put("country", region).put("language", Locale.getDefault().getLanguage())
                .put("first", 5).put("filter", new JSONObject().put("searchQuery", item.title));
        JSONObject body = new JSONObject().put("query", JUSTWATCH_QUERY).put("variables", vars);
        JSONArray edges = new JSONObject(post(JUSTWATCH, body.toString())).getJSONObject("data")
                .getJSONObject("popularTitles").getJSONArray("edges");
        JSONObject match = null;
        for (int i = 0; i < edges.length() && match == null; i++) {
            JSONObject node = edges.getJSONObject(i).getJSONObject("node");
            JSONObject c = node.optJSONObject("content");
            String id = c == null ? null : c.optJSONObject("externalIds") == null ? null
                    : c.getJSONObject("externalIds").optString("tmdbId", null);
            if (item.tmdbId > 0 && String.valueOf(item.tmdbId).equals(id)) match = node;
        }
        for (int i = 0; i < edges.length() && match == null; i++) {
            JSONObject node = edges.getJSONObject(i).getJSONObject("node");
            JSONObject c = node.optJSONObject("content");
            if (item.tmdbId <= 0 && c != null && item.title.equalsIgnoreCase(c.optString("title"))) match = node;
        }
        if (match == null) return null;
        JSONArray offers = match.optJSONArray("offers");
        if (offers == null) return null;
        // Included with a subscription or free first, then rent or buy: the owner most likely means to watch it.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < offers.length(); i++) {
                JSONObject o = offers.getJSONObject(i);
                String type = o.optString("monetizationType");
                boolean included = type.equals("FLATRATE") || type.equals("FREE") || type.equals("ADS");
                if (included != (pass == 0)) continue;
                Intent in = viewIn(pkg, o.optString("standardWebURL", ""), pm);
                if (in != null) return in;
            }
        }
        return null;
    }

    /** ACTION_VIEW of this link, restricted to the app, when the app answers it; else null. */
    private static Intent viewIn(String pkg, String url, PackageManager pm) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null;
        Intent in = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return in.resolveActivity(pm) != null ? in : null;
    }

    private static String post(String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            return new String(readAll(c.getInputStream(), MAX_JSON_BYTES), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    // ---- images ---------------------------------------------------------------------------------

    private void loadImage(String pkg, String url) {
        if (closed || !pending.add("i:" + url)) return;
        worker.execute(() -> {
            Bitmap b = null;
            try {
                b = decode(imageBytes(url));
            } catch (Exception e) {
                Log.w(TAG, "image failed: " + e);
            }
            final Bitmap result = b;
            main.post(() -> {
                pending.remove("i:" + url);
                if (closed || result == null) return;
                images.put(url, result);
                Listener l = listener;
                if (l != null) l.onContentChanged(pkg);
            });
        });
    }

    private byte[] imageBytes(String url) throws IOException {
        if (url.startsWith("content:") || url.startsWith("android.resource:") || url.startsWith("file:")) {
            try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(url))) {
                if (in == null) throw new IOException("no stream");
                return readAll(in, MAX_IMAGE_BYTES);
            }
        }
        File dir = new File(ctx.getCacheDir(), "content");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, sha1(url) + ".img");
        if (f.isFile() && f.length() > 0) {
            try (FileInputStream in = new FileInputStream(f)) {
                return readAll(in, MAX_IMAGE_BYTES);
            }
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        byte[] bytes;
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            bytes = readAll(c.getInputStream(), MAX_IMAGE_BYTES);
        } finally {
            c.disconnect();
        }
        if (url.startsWith("https://image.tmdb.org/")) {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            }
        }
        return bytes;
    }

    /** Decodes at no more than about 1280 px wide, in RGB_565: a backdrop needs no alpha. */
    static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleSize(o.outWidth, 1280);
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    /** The largest power of two that keeps the width at or above target. */
    static int sampleSize(int width, int target) {
        int s = 1;
        while (width / (s * 2) >= target) s *= 2;
        return s;
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > max) throw new IOException("too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
