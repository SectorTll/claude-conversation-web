package ee.doniss.claudeweb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Outermost gate: rejects (403) any request whose remote address is outside the configured
 * allowlist. Uses only {@code getRemoteAddr()} — there is no reverse proxy, so {@code X-Forwarded-For}
 * is deliberately ignored (it would be client-spoofable).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IpAllowlistFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IpAllowlistFilter.class);

    private final SecurityProperties props;
    private final CidrMatcher matcher;

    public IpAllowlistFilter(SecurityProperties props, CidrMatcher matcher) {
        this.props = props;
        this.matcher = matcher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (props.isEnabled() && props.isEnforceIpAllowlist() && !matcher.matches(req.getRemoteAddr())) {
            log.debug("Blocked {} {} from {}", req.getMethod(), req.getRequestURI(), req.getRemoteAddr());
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(req, res);
    }
}
