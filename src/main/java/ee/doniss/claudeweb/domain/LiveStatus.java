package ee.doniss.claudeweb.domain;

/**
 * Live state of a session, merged from the {@code ~/.claude/sessions} registry and the in-browser
 * chat engine. Precedence wherever statuses collide for one session: {@code WAITING} (a decision is
 * blocking the turn) > {@code WORKING} > {@code IDLE}.
 */
public enum LiveStatus {
    /** Not currently running. */
    NONE,
    /** Running, Claude is busy. */
    WORKING,
    /** A live but idle CLI shell — terminal open, nothing needed from the user. */
    IDLE,
    /** Genuinely needs the user's decision: a chat turn parked on a permission/question card. */
    WAITING
}
