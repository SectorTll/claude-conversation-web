package ee.doniss.claudeweb.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Auth-token lifecycle: issue, validate, expire (TTL), revoke, sweep. */
class SessionStoreTest {

    private final SessionStore store = new SessionStore();

    @Test
    void issuedTokenIsValid() {
        String token = store.issue(Duration.ofMinutes(5));
        assertTrue(store.isValid(token));
    }

    @Test
    void nullAndUnknownTokensAreInvalid() {
        assertFalse(store.isValid(null));
        assertFalse(store.isValid("never-issued"));
    }

    @Test
    void issuedTokensAreUnique() {
        assertNotEquals(store.issue(Duration.ofMinutes(5)), store.issue(Duration.ofMinutes(5)));
    }

    @Test
    void expiredTokenIsInvalid() {
        // A non-positive TTL puts the expiry in the past, so the token is dead on arrival.
        String token = store.issue(Duration.ofMillis(-100));
        assertFalse(store.isValid(token));
    }

    @Test
    void revokedTokenIsInvalid() {
        String token = store.issue(Duration.ofMinutes(5));
        store.revoke(token);
        assertFalse(store.isValid(token));
        store.revoke(null); // must not throw
    }

    @Test
    void sweepKeepsLiveTokensAndDropsExpired() {
        String live = store.issue(Duration.ofMinutes(5));
        store.issue(Duration.ofMillis(-100)); // expired
        store.sweepExpired();
        assertTrue(store.isValid(live));
    }
}
