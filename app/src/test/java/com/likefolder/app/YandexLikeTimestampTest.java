package com.likefolder.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class YandexLikeTimestampTest {
    @Test public void timezoneOffsetsDescribeTheSameLikeInstant() {
        assertEquals(1735787045000L, YandexApi.parseLikeTimestamp("2025-01-02T03:04:05Z"));
        assertEquals(1735787045000L, YandexApi.parseLikeTimestamp("2025-01-02T06:04:05+03:00"));
        assertEquals(1735787045000L, YandexApi.parseLikeTimestamp("2025-01-01T22:04:05-05:00"));
    }

    @Test public void fractionalSecondsAreRetainedToMilliseconds() {
        assertEquals(1735787045123L, YandexApi.parseLikeTimestamp("2025-01-02T03:04:05.123456Z"));
    }

    @Test public void missingOrAmbiguousDatesStayUnknown() {
        for (String value : new String[]{null, "", "null", "broken", "2025-01-02",
                "2025-01-02T03:04:05", "1735787045"}) {
            assertEquals(0, YandexApi.parseLikeTimestamp(value));
        }
    }
}
