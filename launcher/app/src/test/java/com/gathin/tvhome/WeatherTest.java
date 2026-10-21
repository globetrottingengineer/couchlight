package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WeatherTest {

    @Test
    public void wmoCodesMapToKinds() {
        assertEquals(Weather.CLEAR, Weather.kind(0));
        assertEquals(Weather.PARTLY, Weather.kind(2));
        assertEquals(Weather.CLOUDY, Weather.kind(3));
        assertEquals(Weather.FOG, Weather.kind(45));
        assertEquals(Weather.RAIN, Weather.kind(53));
        assertEquals(Weather.RAIN, Weather.kind(81));
        assertEquals(Weather.SNOW, Weather.kind(73));
        assertEquals(Weather.SNOW, Weather.kind(86));
        assertEquals(Weather.THUNDER, Weather.kind(95));
    }

    @Test
    public void forecastUrlRoundsTheLocationAndPicksTheUnit() {
        String c = Weather.forecastUrl(52.520008, 13.404954, false);
        assertTrue(c.contains("latitude=52.520&longitude=13.405"));
        assertFalse(c.contains("fahrenheit"));
        assertTrue(Weather.forecastUrl(40.7, -74.0, true).endsWith("&temperature_unit=fahrenheit"));
    }
}
