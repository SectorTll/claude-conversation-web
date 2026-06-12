package ee.doniss.claudeweb.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of valid session tokens. Tokens are opaque, 256-bit and expire after a TTL.
 * Deliberately not persisted: a JVM restart logs everyone out, which is fine for a personal tool.
 */
@Component
public class SessionStore {

    private final ConcurrentHashMap<String, Instant> tokens = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    /** Issue a fresh token valid for {@code ttl}. */
    public String issue(Duration ttl) {
        byte[] raw = new byte[32];
        rng.nextBytes(raw);
        String token = encoder.encodeToString(raw);
        tokens.put(token, Instant.now().plus(ttl));
        return token;
    }

    public boolean isValid(String token) {
        if (token == null) {
            return false;
        }
        Instant expiry = tokens.get(token);
        if (expiry == null) {
            return false;
        }
        if (expiry.isBefore(Instant.now())) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    public void revoke(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    /** Drop expired tokens so the map can't grow without bound (scheduling is enabled app-wide). */
    @Scheduled(fixedDelay = 30 * 60 * 1000L)
    void sweepExpired() {
        Instant now = Instant.now();
        tokens.values().removeIf(expiry -> expiry.isBefore(now));
    }
}
