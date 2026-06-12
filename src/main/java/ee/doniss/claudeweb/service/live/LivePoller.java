package ee.doniss.claudeweb.service.live;

import ee.doniss.claudeweb.domain.LiveSession;
import ee.doniss.claudeweb.domain.LiveSnapshot;
import ee.doniss.claudeweb.domain.LiveStatus;
import ee.doniss.claudeweb.service.LiveStatusService;
import ee.doniss.claudeweb.service.chat.ChatEngine;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Periodically reads the live-session registry and, when it has changed, pushes a {@code live}
 * event to all SSE subscribers. Port of the desktop app's 2-second status timer.
 *
 * <p>In-browser chat turns (driven by the active {@link ChatEngine}) are merged in as
 * {@code WORKING}: the headless {@code claude} process doesn't reliably register in the on-disk
 * registry, so this is what lights the "working" badge for a browser turn — in any tab and after a
 * page reload, since the server is the source of truth.
 */
@Component
public class LivePoller {

    private final LiveStatusService liveStatus;
    private final ChatEngine chat;
    private final SseHub hub;
    private volatile String lastSignature = "";

    public LivePoller(LiveStatusService liveStatus, ChatEngine chat, SseHub hub) {
        this.liveStatus = liveStatus;
        this.chat = chat;
        this.hub = hub;
    }

    public LiveSnapshot snapshot() {
        // Merge the on-disk registry with the in-browser chat turns, deduped by sessionId with
        // WAITING > WORKING > IDLE: a chat turn parked on a permission/question card needs the
        // user's decision and must outrank everything (incl. a busy-looking registry entry for the
        // same id); a busy process outranks an open-but-idle shell.
        Map<String, LiveSession> byId = new LinkedHashMap<>();
        for (LiveSession s : liveStatus.getLiveSessions()) {
            byId.put(s.sessionId(), s);
        }
        for (LiveSession s : chat.runningChatSessions()) {
            byId.merge(s.sessionId(), s, (existing, incoming) ->
                    rank(incoming.status()) > rank(existing.status()) ? incoming : existing);
        }
        List<LiveSession> sessions = new ArrayList<>(byId.values());
        // Only true needs-a-decision sessions — idle shells are not "waiting for input".
        long waiting = sessions.stream().filter(s -> s.status() == LiveStatus.WAITING).count();
        return new LiveSnapshot(sessions, waiting);
    }

    private static int rank(LiveStatus status) {
        return switch (status) {
            case WAITING -> 3;
            case WORKING -> 2;
            case IDLE -> 1;
            case NONE -> 0;
        };
    }

    @Scheduled(fixedDelayString = "${claude.live.poll-ms:2000}")
    public void poll() {
        if (!hub.hasSubscribers()) {
            return;
        }
        LiveSnapshot snap = snapshot();
        String sig = signature(snap);
        if (sig.equals(lastSignature)) {
            return;
        }
        lastSignature = sig;
        hub.broadcast("live", snap);
    }

    private static String signature(LiveSnapshot snap) {
        return snap.sessions().stream()
                .map(s -> s.sessionId() + ":" + s.status())
                .sorted()
                .collect(Collectors.joining("|"));
    }
}
