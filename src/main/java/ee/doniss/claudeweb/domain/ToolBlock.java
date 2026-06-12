package ee.doniss.claudeweb.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One tool invocation or result inside an assistant/tool turn.
 *
 * <p>For file-editing tools (Edit/Write) the structured fields carry the change so the client can
 * render a real diff; {@code body} keeps the generic pretty-JSON rendering for everything else (and
 * stays populated for Edit/Write too — the conversation filter searches it).
 *
 * @param kind     "use" or "result"
 * @param title    tool name (for use) or "result"
 * @param body     prettified input JSON (for use) or flattened output text (for result)
 * @param filePath target file of an Edit/Write call; null otherwise
 * @param oldText  replaced text (Edit's {@code old_string}); null for Write (pure addition)
 * @param newText  inserted text (Edit's {@code new_string} / Write's {@code content}); null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolBlock(String kind, String title, String body,
                        String filePath, String oldText, String newText) {

    public static ToolBlock use(String title, String body) {
        return new ToolBlock("use", title, body, null, null, null);
    }

    public static ToolBlock result(String body) {
        return new ToolBlock("result", "result", body, null, null, null);
    }

    /** A file-editing tool_use with the structured old/new payload for the diff renderer. */
    public static ToolBlock edit(String title, String body, String filePath, String oldText, String newText) {
        return new ToolBlock("use", title, body, filePath, oldText, newText);
    }
}
