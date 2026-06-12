package ee.doniss.claudeweb.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Small formatting helpers shared by the domain models. Port of {@code Models/Format.cs}. */
public final class Format {

    private static final DateTimeFormatter MON_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MON_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private Format() {
    }

    public static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        s = s.strip();
        return s.length() <= max ? s : s.substring(0, max).stripTrailing() + "…";
    }

    /** Compact token count: {@code 999}, {@code 3.9k}, {@code 1.2M}. */
    public static String compactTokens(long n) {
        if (n < 1_000) {
            return Long.toString(n);
        }
        if (n < 1_000_000) {
            return trimZero(n / 1_000.0) + "k";
        }
        return trimZero(n / 1_000_000.0) + "M";
    }

    /** Dollar display for estimated costs: {@code $1.23}, {@code $0.05}, {@code <$0.01}. */
    public static String usd(double v) {
        if (v <= 0) {
            return "$0.00";
        }
        if (v < 0.01) {
            return "<$0.01";
        }
        return String.format(Locale.ENGLISH, "$%.2f", v);
    }

    private static String trimZero(double v) {
        String s = String.format(Locale.ENGLISH, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** Relative time against the system clock / zone. */
    public static String relativeTime(Instant value) {
        return relativeTime(value, Instant.now(), ZoneId.systemDefault());
    }

    /** Relative time against an explicit "now" and zone (testable). */
    public static String relativeTime(Instant value, Instant now, ZoneId zone) {
        if (value == null) {
            return "";
        }
        Duration delta = Duration.between(value, now);
        long seconds = delta.getSeconds();
        if (seconds < 60) {
            return "just now";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        }
        if (seconds < 86_400) {
            return (seconds / 3600) + "h ago";
        }
        if (seconds < 604_800) {
            return (seconds / 86_400) + "d ago";
        }
        ZonedDateTime local = value.atZone(zone);
        ZonedDateTime localNow = now.atZone(zone);
        return local.getYear() == localNow.getYear() ? local.format(MON_DAY) : local.format(MON_DAY_YEAR);
    }
}
