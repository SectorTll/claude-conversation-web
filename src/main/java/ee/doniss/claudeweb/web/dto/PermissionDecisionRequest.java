package ee.doniss.claudeweb.web.dto;

/**
 * The browser's allow/deny for a pending tool-permission card ({@code sdk} engine).
 * {@code behavior} is {@code allow} | {@code deny}; {@code message} optionally tells the model why
 * it was denied; {@code always} (allow only) also persists the SDK's suggested permission rules so
 * this tool stops asking for the session/project.
 */
public record PermissionDecisionRequest(String requestId, String behavior, String message, boolean always) {
}
