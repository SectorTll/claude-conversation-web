package ee.doniss.claudeweb.service.live;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of connected SSE clients. Server→client only; the browser's {@code EventSource}
 * auto-reconnects, so we simply drop emitters that error out. Payloads are pre-serialized to a JSON
 * string and sent as the event data (the client JSON.parses it).
 */
@Component
public class SseHub {

    private static final long TIMEOUT_MS = Duration.ofHours(8).toMillis();

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;

    public SseHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(t -> emitters.remove(emitter));
        return emitter;
    }

    public boolean hasSubscribers() {
        return !emitters.isEmpty();
    }

    /** Send one event to a single emitter (used for the initial snapshot on connect). */
    public void sendTo(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(payload)));
        } catch (Exception ex) {
            emitters.remove(emitter);
        }
    }

    public void broadcast(String event, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(json));
            } catch (Exception ex) {
                emitters.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignore) {
                    // already broken
                }
            }
        }
    }
}
