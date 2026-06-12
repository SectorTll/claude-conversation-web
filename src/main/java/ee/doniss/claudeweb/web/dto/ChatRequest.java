package ee.doniss.claudeweb.web.dto;

import java.util.List;

/**
 * A chat message from the browser. {@code text} is the user's message; {@code permissionMode} is an
 * optional per-message override of the server default ({@code plan|acceptEdits|bypassPermissions|...}),
 * honoured only when {@code claude.chat.allow-per-request-permission-mode} is true; {@code model} is
 * an optional model alias for the turn ({@code haiku|sonnet|opus|fable}; blank = the user's default);
 * {@code attachments} are pasted/dropped images ({@code sdk} engine only — the headless {@code cli}
 * engine rejects them).
 */
public record ChatRequest(String text, String permissionMode, String model, List<Attachment> attachments) {

    /** One pasted image: a whitelisted media type + bare base64 data (no {@code data:} prefix). */
    public record Attachment(String mediaType, String data) {
    }
}
