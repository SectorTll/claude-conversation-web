package ee.doniss.claudeweb.service.notify;

import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import com.interaso.webpush.WebPushService;
import ee.doniss.claudeweb.config.ClaudeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web Push (VAPID) delivery. The keypair is generated on first use and persisted under the push
 * store, so subscriptions survive restarts; expired/gone subscriptions are pruned on send.
 */
@Component
public class WebPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    private final ClaudeProperties props;
    private final PushSubscriptionStore store;
    private final ObjectMapper mapper;
    private volatile WebPushService service;
    private volatile VapidKeys keys;

    public WebPushSender(ClaudeProperties props, PushSubscriptionStore store, ObjectMapper mapper) {
        this.props = props;
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    public boolean enabled() {
        return props.getPush().isEnabled();
    }

    /** The base64url application server key the browser passes to {@code pushManager.subscribe}. */
    public String applicationServerKey() {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(vapidKeys().getApplicationServerKey());
    }

    @Override
    public void send(WaitingEvent event) {
        Map<String, PushSubscriptionStore.Keys> subs = store.all();
        if (subs.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", event.title());
        payload.put("body", event.body());
        payload.put("path", event.path());
        payload.put("tag", "claude-waiting-" + event.sessionId());
        String json = mapper.writeValueAsString(payload);

        for (Map.Entry<String, PushSubscriptionStore.Keys> e : subs.entrySet()) {
            try {
                WebPush.SubscriptionState state = service().send(
                        json, e.getKey(), e.getValue().p256dh(), e.getValue().auth(), null, null, null);
                if (state == WebPush.SubscriptionState.EXPIRED) {
                    log.info("pruning expired push subscription {}", e.getKey());
                    store.remove(e.getKey());
                }
            } catch (Exception ex) {
                log.warn("web push to {} failed: {}", e.getKey(), ex.toString());
            }
        }
    }

    private WebPushService service() {
        WebPushService s = service;
        if (s == null) {
            synchronized (this) {
                s = service;
                if (s == null) {
                    s = new WebPushService(props.getPush().getSubject(), vapidKeys());
                    service = s;
                }
            }
        }
        return s;
    }

    private VapidKeys vapidKeys() {
        VapidKeys k = keys;
        if (k == null) {
            synchronized (this) {
                k = keys;
                if (k == null) {
                    Path file = props.resolvedPushStore().resolve("vapid.keys");
                    try {
                        Files.createDirectories(file.getParent());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    k = VapidKeys.load(file, true);
                    keys = k;
                }
            }
        }
        return k;
    }
}
