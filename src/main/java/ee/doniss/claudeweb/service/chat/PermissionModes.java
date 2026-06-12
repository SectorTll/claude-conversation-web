package ee.doniss.claudeweb.service.chat;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.support.BadRequestException;

import java.util.Set;

/**
 * The single, security-sensitive place where a chat turn's permission mode is decided. Shared by
 * both chat engines so the gate cannot drift between them.
 *
 * <p>SECURITY: with {@code acceptEdits}/{@code bypassPermissions} an authenticated browser runs
 * arbitrary shell/file operations on the host. {@code allow-per-request-permission-mode=false}
 * forces the server default and ignores the client's picker.
 */
public final class PermissionModes {

    /** Every mode the CLI/SDK accepts; anything else is rejected before a process is spawned. */
    public static final Set<String> ALLOWED =
            Set.of("plan", "acceptEdits", "bypassPermissions", "default", "dontAsk", "auto");

    private PermissionModes() {
    }

    /** Resolve the effective mode for one turn from the server default and an optional client override. */
    public static String resolve(ClaudeProperties.Chat chat, String override) {
        if (override != null && !override.isBlank()) {
            if (!chat.isAllowPerRequestPermissionMode()) {
                return chat.getPermissionMode(); // client overrides disabled — use the server default
            }
            String m = override.strip();
            if (!ALLOWED.contains(m)) {
                throw new BadRequestException("invalid permissionMode: " + m);
            }
            return m;
        }
        return chat.getPermissionMode();
    }
}
