package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.security.SecurityProperties;
import ee.doniss.claudeweb.security.SessionCookie;
import ee.doniss.claudeweb.security.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared-password login. Issues / clears the {@code SESSION} cookie and reports auth status. These
 * three endpoints are reachable pre-login (see {@code AuthFilter}); everything else under
 * {@code /api/**} and {@code /sse/**} requires a valid session.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SecurityProperties props;
    private final SessionStore sessions;

    public AuthController(SecurityProperties props, SessionStore sessions) {
        this.props = props;
        this.sessions = sessions;
    }

    public record LoginRequest(String password) {
    }

    public record StatusResponse(boolean authenticated) {
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody(required = false) LoginRequest body, HttpServletRequest req) {
        if (!props.isEnabled()) {
            return ResponseEntity.noContent().build(); // nothing to log in to
        }
        String expected = props.getPassword();
        String provided = body == null ? null : body.password();
        if (expected == null || expected.isBlank() || provided == null
                || !constantTimeEquals(expected, provided)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String token = sessions.issue(props.getSessionTtl());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, SessionCookie.set(token, req.isSecure(), props.getSessionTtl()))
                .build();
    }

    @GetMapping("/status")
    public StatusResponse status(HttpServletRequest req) {
        boolean authenticated = !props.isEnabled() || sessions.isValid(SessionCookie.read(req));
        return new StatusResponse(authenticated);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req) {
        sessions.revoke(SessionCookie.read(req));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, SessionCookie.clear(req.isSecure()))
                .build();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
