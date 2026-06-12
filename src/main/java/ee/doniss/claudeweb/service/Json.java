package ee.doniss.claudeweb.service;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Small null-tolerant accessors over a parsed JSONL line. Mirrors the C# {@code GetStr/GetTime}. */
final class Json {

    private Json() {
    }

    /** The textual value of {@code field}, or null if absent / not a JSON string. */
    static String str(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** The numeric value of {@code field} as a long, or 0 if absent / not a number. */
    static long lng(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return 0;
        }
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.asLong() : 0;
    }

    /** Parse {@code field} as an instant (assume UTC when no offset), or null. */
    static Instant time(JsonNode node, String field) {
        String s = str(node, field);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception ignore) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception ignore) {
            // fall through
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (Exception ignore) {
            return null;
        }
    }
}
