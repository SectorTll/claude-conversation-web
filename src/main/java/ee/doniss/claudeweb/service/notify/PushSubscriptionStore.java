package ee.doniss.claudeweb.service.notify;

import ee.doniss.claudeweb.config.ClaudeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser push subscriptions, persisted as one {@code subscriptions.json} under the push store
 * (keyed by endpoint URL — re-subscribing the same browser just overwrites its entry).
 */
@Component
public class PushSubscriptionStore {

    private static final Logger log = LoggerFactory.getLogger(PushSubscriptionStore.class);

    /** The browser PushSubscription key material needed to encrypt a push for it. */
    public record Keys(String p256dh, String auth) {
    }

    private final ClaudeProperties props;
    private final ObjectMapper mapper;
    private final Map<String, Keys> subscriptions = new LinkedHashMap<>();
    private boolean loaded;

    public PushSubscriptionStore(ClaudeProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public synchronized void add(String endpoint, Keys keys) {
        load();
        subscriptions.put(endpoint, keys);
        save();
    }

    public synchronized void remove(String endpoint) {
        load();
        if (subscriptions.remove(endpoint) != null) {
            save();
        }
    }

    public synchronized Map<String, Keys> all() {
        load();
        return Map.copyOf(subscriptions);
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            subscriptions.putAll(mapper.readValue(json, new TypeReference<Map<String, Keys>>() {
            }));
        } catch (Exception e) {
            log.warn("could not read {} — starting with no push subscriptions: {}", file, e.toString());
        }
    }

    private void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(subscriptions), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file() {
        return props.resolvedPushStore().resolve("subscriptions.json");
    }
}
