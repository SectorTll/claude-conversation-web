package ee.doniss.claudeweb.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SESSION cookie: read-back and the security attributes (HttpOnly, SameSite=Strict, Secure). */
class SessionCookieTest {

    @Test
    void readReturnsNullWhenNoCookies() {
        assertNull(SessionCookie.read(new MockHttpServletRequest()));
    }

    @Test
    void readReturnsNullWhenSessionCookieAbsent() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("other", "x"));
        assertNull(SessionCookie.read(req));
    }

    @Test
    void readReturnsTokenAmongOtherCookies() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("other", "x"), new Cookie("SESSION", "tok-123"));
        assertEquals("tok-123", SessionCookie.read(req));
    }

    @Test
    void setOverHttpsCarriesSecureAndHardeningAttributes() {
        String header = SessionCookie.set("tok-123", true, Duration.ofHours(12));
        assertTrue(header.contains("SESSION=tok-123"), header);
        assertTrue(header.contains("HttpOnly"), header);
        assertTrue(header.contains("SameSite=Strict"), header);
        assertTrue(header.contains("Secure"), header);
        assertTrue(header.contains("Path=/"), header);
    }

    @Test
    void setOverPlainHttpOmitsSecureSoTheCookieStillSticks() {
        String header = SessionCookie.set("tok-123", false, Duration.ofHours(12));
        assertFalse(header.contains("Secure"), header);
        assertTrue(header.contains("SameSite=Strict"), header);
    }

    @Test
    void clearExpiresTheCookieImmediately() {
        String header = SessionCookie.clear(true);
        assertTrue(header.contains("SESSION="), header);
        assertTrue(header.contains("Max-Age=0"), header);
    }
}
