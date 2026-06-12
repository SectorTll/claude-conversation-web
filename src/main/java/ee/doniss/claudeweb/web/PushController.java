package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.service.notify.PushSubscriptionStore;
import ee.doniss.claudeweb.service.notify.WebPushSender;
import ee.doniss.claudeweb.support.BadRequestException;
import ee.doniss.claudeweb.support.NotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Web-push subscription management. Behind the auth wall like the rest of {@code /api}; the
 * service worker itself ({@code /sw.js}) is a static asset and loads pre-login.
 */
@RestController
@RequestMapping("/api/push")
public class PushController {

    /** The browser PushSubscription JSON shape ({@code endpoint} + {@code keys.p256dh/auth}). */
    public record SubscribeRequest(String endpoint, PushSubscriptionStore.Keys keys) {
    }

    public record UnsubscribeRequest(String endpoint) {
    }

    private final ClaudeProperties props;
    private final WebPushSender sender;
    private final PushSubscriptionStore store;

    public PushController(ClaudeProperties props, WebPushSender sender, PushSubscriptionStore store) {
        this.props = props;
        this.sender = sender;
        this.store = store;
    }

    /** The VAPID application server key for {@code pushManager.subscribe} (404 when push is off). */
    @GetMapping("/key")
    public Map<String, String> key() {
        if (!props.getPush().isEnabled()) {
            throw new NotFoundException("push notifications are disabled");
        }
        return Map.of("key", sender.applicationServerKey());
    }

    @PostMapping("/subscribe")
    public ResponseEntity<Void> subscribe(@RequestBody SubscribeRequest req) {
        if (req == null || blank(req.endpoint()) || req.keys() == null
                || blank(req.keys().p256dh()) || blank(req.keys().auth())) {
            throw new BadRequestException("endpoint and keys (p256dh, auth) are required");
        }
        store.add(req.endpoint(), req.keys());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<Void> unsubscribe(@RequestBody UnsubscribeRequest req) {
        if (req == null || blank(req.endpoint())) {
            throw new BadRequestException("endpoint is required");
        }
        store.remove(req.endpoint());
        return ResponseEntity.noContent().build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
