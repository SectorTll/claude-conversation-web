package ee.doniss.claudeweb.service.chat.sdk;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Spring→sidecar wire shapes. The TypeScript side ({@code sidecar/src/protocol.ts})
 * parses these exact field names — a rename here without a sidecar rebuild breaks chat.
 */
class SidecarProtocolTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private JsonNode roundTrip(Object message) {
        return MAPPER.readTree(MAPPER.writeValueAsString(message));
    }

    @Test
    void initCarriesSessionResumeCwdModeAndExecutable() {
        JsonNode n = roundTrip(SidecarProtocol.init("sid-1", true, "C:/work", "default", "claude"));
        assertEquals("init", n.get("type").asText());
        assertEquals("sid-1", n.get("sessionId").asText());
        assertTrue(n.get("resume").booleanValue());
        assertEquals("C:/work", n.get("cwd").asText());
        assertEquals("default", n.get("permissionMode").asText());
        assertEquals("claude", n.get("executable").asText());
    }

    @Test
    void userTurnOmitsModeAndModelWhenAbsent() {
        JsonNode n = roundTrip(SidecarProtocol.userTurn("t1", "hello", null, ""));
        assertEquals("user_turn", n.get("type").asText());
        assertEquals("t1", n.get("turnId").asText());
        assertEquals("hello", n.get("text").asText());
        assertFalse(n.has("permissionMode"));
        assertFalse(n.has("model"));

        JsonNode withMode = roundTrip(SidecarProtocol.userTurn("t1", "hello", "acceptEdits", "haiku"));
        assertEquals("acceptEdits", withMode.get("permissionMode").asText());
        assertEquals("haiku", withMode.get("model").asText());
    }

    @Test
    void userTurnCarriesImagesOnlyWhenPresent() {
        var img = new ee.doniss.claudeweb.web.dto.ChatRequest.Attachment("image/png", "aWJt");
        JsonNode with = roundTrip(SidecarProtocol.userTurn("t1", "look", null, "", List.of(img)));
        assertEquals(1, with.get("images").size());
        assertEquals("image/png", with.get("images").get(0).get("mediaType").asText());
        assertEquals("aWJt", with.get("images").get(0).get("data").asText());

        assertFalse(roundTrip(SidecarProtocol.userTurn("t1", "hello", null, "", null)).has("images"));
        assertFalse(roundTrip(SidecarProtocol.userTurn("t1", "hello", null, "", List.of())).has("images"));
    }

    @Test
    void permissionResponseEncodesBehaviorMessageAndAlways() {
        JsonNode allow = roundTrip(SidecarProtocol.permissionResponse("r1", true, null, false));
        assertEquals("permission_response", allow.get("type").asText());
        assertEquals("allow", allow.get("behavior").asText());
        assertFalse(allow.has("message"));
        assertFalse(allow.has("always"));

        JsonNode deny = roundTrip(SidecarProtocol.permissionResponse("r1", false, "user said no", false));
        assertEquals("deny", deny.get("behavior").asText());
        assertEquals("user said no", deny.get("message").asText());

        JsonNode always = roundTrip(SidecarProtocol.permissionResponse("r1", true, null, true));
        assertTrue(always.get("always").booleanValue());
        // `always` only makes sense for allow — a deny must never persist allow-rules.
        assertFalse(roundTrip(SidecarProtocol.permissionResponse("r1", false, "no", true)).has("always"));
    }

    @Test
    void questionAnswerKeepsPerQuestionSelectionLists() {
        JsonNode n = roundTrip(SidecarProtocol.questionAnswer("r2", List.of(List.of("Red", "Blue"), List.of("Yes"))));
        assertEquals("question_answer", n.get("type").asText());
        assertEquals("r2", n.get("requestId").asText());
        assertEquals(2, n.get("answers").size());
        assertEquals("Red", n.get("answers").get(0).get(0).asText());
        assertEquals("Yes", n.get("answers").get(1).get(0).asText());
    }

    @Test
    void controlMessagesAreBareTypes() {
        assertEquals("interrupt", roundTrip(SidecarProtocol.interrupt()).get("type").asText());
        assertEquals("shutdown", roundTrip(SidecarProtocol.shutdown()).get("type").asText());
    }
}
