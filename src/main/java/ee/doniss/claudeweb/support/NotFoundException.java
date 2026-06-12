package ee.doniss.claudeweb.support;

/** Thrown when a requested project/session/resource does not exist (mapped to HTTP 404). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
