package ee.doniss.claudeweb.service.notify;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Short-token registry for Telegram inline buttons. {@code callback_data} is capped at 64 bytes by
 * Telegram, far too small for (session, request, option) — so each button carries a random token
 * and the action lives here. Tokens are single-use ({@link #take} removes) and the map is bounded:
 * stale siblings of an answered card simply age out.
 */
@Component
public class TelegramCallbacks {

    /** What pressing one button means. {@code allow} is permission-only; {@code option} question-only. */
    public record Action(String sessionId, String requestId, String kind, boolean allow, String option) {
    }

    private static final int CAP = 500;
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Action> tokens = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Action> eldest) {
                    return size() > CAP;
                }
            });

    public String register(Action action) {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        String token = sb.toString();
        tokens.put(token, action);
        return token;
    }

    /** Resolve and consume a token; null for unknown/expired/garbage. */
    public Action take(String token) {
        return token == null ? null : tokens.remove(token);
    }
}
