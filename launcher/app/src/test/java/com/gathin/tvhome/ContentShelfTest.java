package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContentShelfTest {

    @Test
    public void v4TokenGoesInAHeaderNotTheUrl() {
        String token = "eyJhbGciOiJIUzI1NiJ9.abc.def";
        assertTrue(ContentShelf.isBearer(token));
        String url = ContentShelf.discoverUrl("tv", "8", "DE", "de-DE", token, null);
        assertFalse(url.contains("api_key"));
        assertFalse(url.contains(token));
    }

    @Test
    public void v3KeyGoesInTheQuery() {
        String key = "0123456789abcdef0123456789abcdef";
        assertFalse(ContentShelf.isBearer(key));
        assertTrue(ContentShelf.discoverUrl("movie", "8", "US", "en-US", key, null).endsWith("&api_key=" + key));
    }

    @Test
    public void providerAlternativesAreEncoded() {
        String url = ContentShelf.discoverUrl("tv", "9|119", "DE", "de-DE", "k", "2024-10-21");
        assertTrue(url.startsWith("https://api.themoviedb.org/3/discover/tv?"));
        assertTrue(url.contains("&with_watch_providers=9%7C119"));
        assertTrue(url.contains("&watch_region=DE"));
        assertTrue(url.contains("&first_air_date.gte=2024-10-21"));
        assertTrue(ContentShelf.discoverUrl("movie", "8", "DE", "de-DE", "k", "2025-04-21").contains("&primary_release_date.gte=2025-04-21"));
    }

    @Test
    public void dockStreamingAppsHaveProviders() {
        assertEquals("8", ContentShelf.PROVIDERS.get("com.netflix.ninja"));
        assertEquals("9|119", ContentShelf.PROVIDERS.get("com.amazon.amazonvideo.livingroom"));
        assertEquals("350", ContentShelf.PROVIDERS.get("com.apple.atve.androidtv.appletv"));
        assertFalse(ContentShelf.PROVIDERS.containsKey("com.google.android.youtube.tv"));
    }

    @Test
    public void genreNamesKeepTheFirstKnownOnes() {
        assertEquals("Drama, Comedy", ContentShelf.genreNames(java.util.Arrays.asList(18, 999999, 35, 80), 2));
        assertEquals("Sci-Fi & Fantasy", ContentShelf.genreNames(java.util.Arrays.asList(10765), 2));
        assertEquals(null, ContentShelf.genreNames(java.util.Arrays.asList(999999), 2));
    }

    @Test
    public void sampleSizeKeepsTvWidth() {
        assertEquals(1, ContentShelf.sampleSize(1280, 1280));
        assertEquals(1, ContentShelf.sampleSize(1920, 1280));
        assertEquals(2, ContentShelf.sampleSize(2560, 1280));
        assertEquals(2, ContentShelf.sampleSize(3840, 1280));
        assertEquals(1, ContentShelf.sampleSize(500, 1280));
    }
}
