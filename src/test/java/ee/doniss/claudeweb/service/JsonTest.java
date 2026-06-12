package ee.doniss.claudeweb.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Null-tolerant JSONL accessors, including the "assume UTC when no offset" timestamp rule. */
class JsonTest {

    private static final JsonMapper M = JsonMapper.builder().build();

    private JsonNode node(String json) {
        return M.readTree(json);
    }

    @Test
    void strReturnsOnlyTextualValues() {
        JsonNode n = node("{\"a\":\"hello\",\"n\":5,\"z\":null}");
        assertEquals("hello", Json.str(n, "a"));
        assertNull(Json.str(n, "n"), "a number is not a string");
        assertNull(Json.str(n, "z"), "a JSON null is not a string");
        assertNull(Json.str(n, "missing"));
        assertNull(Json.str(null, "a"));
    }

    @Test
    void timeParsesIsoInstant() {
        assertEquals(Instant.parse("2026-06-01T10:00:00Z"),
                Json.time(node("{\"t\":\"2026-06-01T10:00:00Z\"}"), "t"));
    }

    @Test
    void timeParsesOffsetDateTime() {
        assertEquals(Instant.parse("2026-06-01T08:00:00Z"),
                Json.time(node("{\"t\":\"2026-06-01T10:00:00+02:00\"}"), "t"));
    }

    @Test
    void timeAssumesUtcForOffsetlessLocalDateTime() {
        assertEquals(Instant.parse("2026-06-01T10:00:00Z"),
                Json.time(node("{\"t\":\"2026-06-01T10:00:00\"}"), "t"));
    }

    @Test
    void timeReturnsNullForGarbageOrMissing() {
        assertNull(Json.time(node("{\"t\":\"not-a-date\"}"), "t"));
        assertNull(Json.time(node("{}"), "t"));
    }
}
