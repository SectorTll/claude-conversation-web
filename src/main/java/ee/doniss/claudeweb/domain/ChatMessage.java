package ee.doniss.claudeweb.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import ee.doniss.claudeweb.service.Format;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One rendered turn in the conversation. Mirrors {@code Models/ChatMessage.cs}: the view-helper
 * getters are serialized as plain JSON fields so the frontend stays dumb.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ChatMessage {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** "user", "assistant", "tool" (a user turn carrying only tool results), or "system". */
    private String role = "user";
    /** The source jsonl line's uuid — the cut point for "fork from here". Null on optimistic bubbles. */
    private String uuid;
    private Instant timestamp;
    /** Main visible text (markdown source, rendered on the client). */
    private String text = "";
    /** Collapsed extended-thinking content, if any. */
    private String thinking;
    private final List<ToolBlock> tools = new ArrayList<>();
    /** Model id of an assistant line ({@code message.model}); null elsewhere. */
    private String model;
    private Long inputTokens;
    private Long outputTokens;
    private Long cacheCreationTokens;
    private Long cacheReadTokens;

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }

    public List<ToolBlock> getTools() { return tools; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Long getInputTokens() { return inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public Long getCacheCreationTokens() { return cacheCreationTokens; }
    public Long getCacheReadTokens() { return cacheReadTokens; }

    public void setUsage(long input, long output, long cacheCreation, long cacheRead) {
        this.inputTokens = input;
        this.outputTokens = output;
        this.cacheCreationTokens = cacheCreation;
        this.cacheReadTokens = cacheRead;
    }

    // ---- view helpers (serialized) ----
    public boolean isUser() { return "user".equals(role); }
    public boolean isAssistant() { return "assistant".equals(role); }
    public boolean isTool() { return "tool".equals(role); }
    public boolean isSystem() { return "system".equals(role); }

    public boolean isHasText() { return text != null && !text.isBlank(); }
    public boolean isHasThinking() { return thinking != null && !thinking.isBlank(); }
    public boolean isHasTools() { return !tools.isEmpty(); }

    public String getRoleHeader() {
        return switch (role) {
            case "user" -> "You";
            case "assistant" -> "Claude";
            case "tool" -> "Tool output";
            case "system" -> "System";
            default -> role;
        };
    }

    public String getTimeDisplay() {
        return timestamp == null ? "" : timestamp.atZone(ZoneId.systemDefault()).format(HHMM);
    }

    /** Per-message usage for the bubble tooltip, e.g. {@code "claude-haiku-4-5 · in 3 · out 8 · cache w 3.9k / r 9.3k"}; null when no usage. */
    public String getTokensDisplay() {
        if (inputTokens == null && outputTokens == null) {
            return null;
        }
        long in = inputTokens == null ? 0 : inputTokens;
        long out = outputTokens == null ? 0 : outputTokens;
        long cw = cacheCreationTokens == null ? 0 : cacheCreationTokens;
        long cr = cacheReadTokens == null ? 0 : cacheReadTokens;
        StringBuilder sb = new StringBuilder();
        if (model != null && !model.isBlank()) {
            sb.append(model).append(" · ");
        }
        sb.append("in ").append(Format.compactTokens(in)).append(" · out ").append(Format.compactTokens(out));
        if (cw > 0 || cr > 0) {
            sb.append(" · cache w ").append(Format.compactTokens(cw))
              .append(" / r ").append(Format.compactTokens(cr));
        }
        return sb.toString();
    }
}
