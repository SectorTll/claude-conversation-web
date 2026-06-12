package ee.doniss.claudeweb.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CidrMatcherTest {

    private static final CidrMatcher M =
            CidrMatcher.parse(List.of("10.20.30.0/24", "127.0.0.1", "::1"));

    @Test
    void allowsAddressesInsideTheV4Subnet() {
        assertTrue(M.matches("10.20.30.0"));
        assertTrue(M.matches("10.20.30.5"));
        assertTrue(M.matches("10.20.30.255"));
    }

    @Test
    void rejectsAddressesOutsideTheV4Subnet() {
        assertFalse(M.matches("10.1.2.5"));
        assertFalse(M.matches("10.2.1.5"));
        assertFalse(M.matches("192.168.0.1"));
    }

    @Test
    void allowsLoopbackInBothFamilies() {
        assertTrue(M.matches("127.0.0.1"));
        assertTrue(M.matches("::1"));
        assertTrue(M.matches("0:0:0:0:0:0:0:1"));
    }

    @Test
    void rejectsOtherLoopbackV4OutsideExactRule() {
        // 127.0.0.1 is a /32 rule, so a sibling loopback address must not match.
        assertFalse(M.matches("127.0.0.2"));
    }

    @Test
    void normalizesV4MappedV6ToMatchV4Rules() {
        assertTrue(M.matches("::ffff:10.20.30.5"));
        assertFalse(M.matches("::ffff:10.1.2.5"));
    }

    @Test
    void rejectsNullBlankAndGarbage() {
        assertFalse(M.matches(null));
        assertFalse(M.matches(""));
        assertFalse(M.matches("not-an-ip"));
    }

    @Test
    void rejectsAddressOutsideAnEmptyAllowlist() {
        CidrMatcher empty = CidrMatcher.parse(List.of());
        assertFalse(empty.matches("127.0.0.1"));
        assertFalse(empty.matches("10.20.30.5"));
    }

    @Test
    void failsFastOnMalformedCidr() {
        assertThrows(IllegalArgumentException.class, () -> CidrMatcher.parse(List.of("10.20.30.0/99")));
        assertThrows(IllegalArgumentException.class, () -> CidrMatcher.parse(List.of("nonsense")));
        assertThrows(IllegalArgumentException.class, () -> CidrMatcher.parse(List.of("10.20.30.0/x")));
    }
}
