package ee.doniss.claudeweb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Login wall (runs just inside {@link IpAllowlistFilter}). Gates only {@code /api/**} and
 * {@code /sse/**}: a request without a valid session cookie there gets a clean 401 (not a redirect,
 * so the SPA and the EventSource see a real status). Static assets and SPA routes always pass so the
 * login screen itself can boot; the {@code /api/auth/**} endpoints are reachable pre-login.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    private final SecurityProperties props;
    private final SessionStore sessions;

    public AuthFilter(SecurityProperties props, SessionStore sessions) {
        this.props = props;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!props.isEnabled() || isPreAuth(req) || sessions.isValid(SessionCookie.read(req))) {
            chain.doFilter(req, res);
            return;
        }
        String path = req.getRequestURI();
        if (path.startsWith("/api/") || path.startsWith("/sse/")) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } else {
            chain.doFilter(req, res); // static asset / SPA shell loads; Vue renders the login screen
        }
    }

    /** Endpoints reachable without a session: login, the status probe, and logout. */
    private static boolean isPreAuth(HttpServletRequest req) {
        String path = req.getRequestURI();
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/status")
                || path.equals("/api/auth/logout");
    }
}
