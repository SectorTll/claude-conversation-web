package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.LiveSession;
import ee.doniss.claudeweb.service.ClaudeDataService;
import ee.doniss.claudeweb.service.chat.ChatEngine;
import ee.doniss.claudeweb.web.dto.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitingControllerTest {

    @TempDir
    Path home;

    private ClaudeDataService data;

    /** A stub engine with a fixed waiting set — the controller only reads these two methods. */
    private ChatEngine engine(Map<String, String> waiting, Map<String, List<Map<String, Object>>> cards) {
        return new ChatEngine() {
            @Override
            public ResponseBodyEmitter sendToSession(String p, String s, String t, String m, String mo,
                                                     List<ChatRequest.Attachment> a) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ResponseBodyEmitter createSessionAndSend(String p, String t, String m, String mo,
                                                            List<ChatRequest.Attachment> a) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean cancel(String sessionId) {
                return false;
            }

            @Override
            public List<LiveSession> runningChatSessions() {
                return List.of();
            }

            @Override
            public String engineName() {
                return "stub";
            }

            @Override
            public Map<String, String> waitingSessions() {
                return waiting;
            }

            @Override
            public List<Map<String, Object>> pendingAsks(String sessionId) {
                return cards.getOrDefault(sessionId, List.of());
            }
        };
    }

    @BeforeEach
    void setUp() throws IOException {
        ClaudeProperties props = new ClaudeProperties();
        props.setHome(home);
        data = new ClaudeDataService(props, JsonMapper.builder().build());
        Path dir = home.resolve("projects").resolve("proj");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("sess-1.jsonl"), """
                {"type":"user","sessionId":"sess-1","message":{"role":"user","content":"hello title"}}
                """, StandardCharsets.UTF_8);
    }

    @Test
    void listsWaitingSessionsWithTitlesAndCards() {
        Map<String, Object> card = Map.of("type", "permission", "requestId", "r1", "toolName", "Bash");
        WaitingController c = new WaitingController(
                engine(Map.of("sess-1", "proj"), Map.of("sess-1", List.of(card))), data);

        List<Map<String, Object>> out = c.waiting();

        assertEquals(1, out.size());
        assertEquals("proj", out.get(0).get("projectId"));
        assertEquals("sess-1", out.get(0).get("sessionId"));
        assertEquals("hello title", out.get(0).get("title"));
        assertEquals(List.of(card), out.get(0).get("cards"));
    }

    @Test
    void unknownSessionFallsBackToTheShortIdAndEmptyCardsAreDropped() {
        Map<String, Object> card = Map.of("type", "question", "requestId", "r2");
        WaitingController c = new WaitingController(
                engine(Map.of("0123456789ab", "nope", "answered", "proj"),
                        Map.of("0123456789ab", List.of(card))), data);

        List<Map<String, Object>> out = c.waiting();

        assertEquals(1, out.size(), "the card-less session disappears from the panel");
        assertEquals("01234567", out.get(0).get("title"));
    }

    @Test
    void emptyWhenNothingWaits() {
        assertTrue(new WaitingController(engine(Map.of(), Map.of()), data).waiting().isEmpty());
    }
}
