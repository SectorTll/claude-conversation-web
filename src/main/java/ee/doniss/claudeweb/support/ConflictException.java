package ee.doniss.claudeweb.support;

/** Thrown when a request conflicts with current state (e.g. a turn is already running) — mapped to HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
