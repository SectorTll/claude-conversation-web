package ee.doniss.claudeweb.domain;

/**
 * One entry of the live-session registry ({@code ~/.claude/sessions/<pid>.json}) whose process is
 * still alive.
 */
public record LiveSession(String sessionId, String cwd, LiveStatus status) {
}
