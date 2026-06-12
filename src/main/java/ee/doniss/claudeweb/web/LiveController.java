package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.domain.LiveSnapshot;
import ee.doniss.claudeweb.service.live.LivePoller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live-status snapshot (poll fallback for the SSE stream at {@code /sse/live}). */
@RestController
@RequestMapping("/api")
public class LiveController {

    private final LivePoller poller;

    public LiveController(LivePoller poller) {
        this.poller = poller;
    }

    @GetMapping("/live")
    public LiveSnapshot live() {
        return poller.snapshot();
    }
}
