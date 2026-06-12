package ee.doniss.claudeweb.service.chat.sdk;

import ee.doniss.claudeweb.web.dto.ChatRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builders for the Spring→sidecar NDJSON protocol lines (one JSON object per line). The
 * TypeScript mirror is {@code sidecar/src/protocol.ts} — keep the two in sync. Inbound
 * (sidecar→Spring) lines are read as {@code JsonNode} in {@link SidecarSession}.
 */
public final class SidecarProtocol {

    private SidecarProtocol() {
    }

    public static Map<String, Object> init(String sessionId, boolean resume, String cwd,
                                           String permissionMode, String executable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "init");
        m.put("sessionId", sessionId);
        m.put("resume", resume);
        m.put("cwd", cwd);
        m.put("permissionMode", permissionMode);
        m.put("executable", executable);
        return m;
    }

    public static Map<String, Object> userTurn(String turnId, String text, String permissionMode, String model) {
        return userTurn(turnId, text, permissionMode, model, null);
    }

    /** {@code images} (pasted attachments) become base64 image content blocks in the SDK user message. */
    public static Map<String, Object> userTurn(String turnId, String text, String permissionMode, String model,
                                               List<ChatRequest.Attachment> images) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "user_turn");
        m.put("turnId", turnId);
        m.put("text", text);
        if (permissionMode != null) {
            m.put("permissionMode", permissionMode);
        }
        if (model != null && !model.isBlank()) {
            m.put("model", model);
        }
        if (images != null && !images.isEmpty()) {
            m.put("images", images.stream()
                    .map(a -> Map.of("mediaType", a.mediaType(), "data", a.data()))
                    .toList());
        }
        return m;
    }

    /** {@code always} (allow only) additionally persists the SDK's suggested permission rules. */
    public static Map<String, Object> permissionResponse(String requestId, boolean allow, String message,
                                                         boolean always) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "permission_response");
        m.put("requestId", requestId);
        m.put("behavior", allow ? "allow" : "deny");
        if (message != null && !message.isBlank()) {
            m.put("message", message);
        }
        if (always && allow) {
            m.put("always", true);
        }
        return m;
    }

    public static Map<String, Object> questionAnswer(String requestId, List<List<String>> answers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "question_answer");
        m.put("requestId", requestId);
        m.put("answers", answers);
        return m;
    }

    public static Map<String, Object> interrupt() {
        return Map.of("type", "interrupt");
    }

    public static Map<String, Object> shutdown() {
        return Map.of("type", "shutdown");
    }
}
