package ee.doniss.claudeweb.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The network-perimeter filter: a remote address outside the allowlist gets 403 and never reaches the
 * chain. Both higher-level integration tests run with enforcement off, so this 403 path is only
 * covered here.
 */
class IpAllowlistFilterTest {

    private final CidrMatcher matcher = CidrMatcher.parse(List.of("127.0.0.1", "10.20.30.0/24"));

    private SecurityProperties props(boolean enabled, boolean enforce) {
        SecurityProperties p = new SecurityProperties();
        p.setEnabled(enabled);
        p.setEnforceIpAllowlist(enforce);
        return p;
    }

    /** Run the filter for {@code remoteAddr}; returns whether the request reached the downstream chain. */
    private boolean runFilter(SecurityProperties p, String remoteAddr, MockHttpServletResponse res) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/projects");
        req.setRemoteAddr(remoteAddr);
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (rq, rs) -> chained.set(true);
        new IpAllowlistFilter(p, matcher).doFilterInternal(req, res, chain);
        return chained.get();
    }

    @Test
    void blocksDisallowedAddressWith403() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean chained = runFilter(props(true, true), "8.8.8.8", res);
        assertFalse(chained, "a blocked request must not reach the chain");
        assertEquals(HttpServletResponse.SC_FORBIDDEN, res.getStatus());
    }

    @Test
    void allowsAddressInsideTheAllowlist() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(runFilter(props(true, true), "10.20.30.42", res));
        assertNotEquals(HttpServletResponse.SC_FORBIDDEN, res.getStatus());
    }

    @Test
    void passesThroughWhenSecurityDisabled() throws Exception {
        assertTrue(runFilter(props(false, true), "8.8.8.8", new MockHttpServletResponse()));
    }

    @Test
    void passesThroughWhenEnforcementDisabled() throws Exception {
        assertTrue(runFilter(props(true, false), "8.8.8.8", new MockHttpServletResponse()));
    }
}
