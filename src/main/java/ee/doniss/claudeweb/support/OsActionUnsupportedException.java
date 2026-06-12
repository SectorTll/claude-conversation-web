package ee.doniss.claudeweb.support;

/** Thrown when an OS-integration action is requested on a platform that does not support it. */
public class OsActionUnsupportedException extends RuntimeException {
    public OsActionUnsupportedException(String message) {
        super(message);
    }
}
