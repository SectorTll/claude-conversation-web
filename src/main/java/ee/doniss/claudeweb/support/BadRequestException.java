package ee.doniss.claudeweb.support;

/** Thrown on invalid input (e.g. path traversal, blank title) — mapped to HTTP 400. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
