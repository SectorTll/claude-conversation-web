package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.service.live.LivePoller;
import ee.doniss.claudeweb.service.live.SseHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events stream of live updates. Emits {@code live} (running-session status) and
 * {@code sessions} (a project's .jsonl files changed on disk) events. The browser's EventSource
 * reconnects automatically, so no client→server channel is needed.
 */
@RestController
public class LiveStreamController {

    private final SseHub hub;
    private final LivePoller poller;

    public LiveStreamController(SseHub hub, LivePoller poller) {
        this.hub = hub;
        this.poller = poller;
    }

    @GetMapping(path = "/sse/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = hub.register();
        // Push the current status immediately so the client doesn't wait for the next poll tick.
        hub.sendTo(emitter, "live", poller.snapshot());
        return emitter;
    }
}
