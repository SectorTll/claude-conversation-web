package ee.doniss.claudeweb.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatTest {

    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");

    @Test
    void truncateAddsEllipsisOnlyWhenTooLong() {
        assertEquals("hello", Format.truncate("hello", 10));
        assertEquals("hel…", Format.truncate("hello world", 3));
        assertEquals("", Format.truncate(null, 5));
        assertEquals("", Format.truncate("   ", 5));
    }

    @Test
    void compactTokensScalesUnits() {
        assertEquals("999", Format.compactTokens(999));
        assertEquals("3.9k", Format.compactTokens(3_900));
        assertEquals("12k", Format.compactTokens(12_000));
        assertEquals("1.2M", Format.compactTokens(1_200_000));
    }

    @Test
    void usdFormatsEstimates() {
        assertEquals("$0.00", Format.usd(0));
        assertEquals("<$0.01", Format.usd(0.004));
        assertEquals("$0.42", Format.usd(0.42));
        assertEquals("$12.35", Format.usd(12.349));
    }

    @Test
    void relativeTimeBoundaries() {
        assertEquals("just now", Format.relativeTime(NOW.minusSeconds(30), NOW, ZoneOffset.UTC));
        assertEquals("5m ago", Format.relativeTime(NOW.minusSeconds(5 * 60), NOW, ZoneOffset.UTC));
        assertEquals("2h ago", Format.relativeTime(NOW.minusSeconds(2 * 3600), NOW, ZoneOffset.UTC));
        assertEquals("3d ago", Format.relativeTime(NOW.minusSeconds(3 * 86_400), NOW, ZoneOffset.UTC));
    }

    @Test
    void relativeTimeFallsBackToDate() {
        // 30 days ago, same year -> "MMM d"
        assertEquals("May 9", Format.relativeTime(NOW.minusSeconds(30L * 86_400), NOW, ZoneOffset.UTC));
        // previous year -> "MMM d, yyyy"
        Instant lastYear = Instant.parse("2025-01-02T00:00:00Z");
        assertEquals("Jan 2, 2025", Format.relativeTime(lastYear, NOW, ZoneOffset.UTC));
    }
}
