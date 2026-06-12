package ee.doniss.claudeweb.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * The {@code SESSION} cookie: HttpOnly + SameSite=Strict, {@code Secure} only over HTTPS so the
 * cookie is still stored in dev over plain http. SameSite=Strict is the CSRF defense — a cross-site
 * request won't carry it, so forged state-changing calls hit the login wall with no valid session.
 */
public final class SessionCookie {

    public static final String NAME = "SESSION";

    private SessionCookie() {
    }

    /** Read the session token from the request, or null if absent. */
    public static String read(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /** A {@code Set-Cookie} header value that installs {@code token}. */
    public static String set(String token, boolean secure, Duration maxAge) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build()
                .toString();
    }

    /** A {@code Set-Cookie} header value that clears the cookie. */
    public static String clear(boolean secure) {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build()
                .toString();
    }
}
