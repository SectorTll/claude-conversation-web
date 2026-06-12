package ee.doniss.claudeweb.service.live;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.LiveSession;
import ee.doniss.claudeweb.domain.LiveSnapshot;
import ee.doniss.claudeweb.domain.LiveStatus;
import ee.doniss.claudeweb.service.LiveStatusService;
import ee.doniss.claudeweb.service.chat.CliChatEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The poller merges the on-disk registry with the in-browser chat turns. Both collaborators are
 * stubbed via overrides so the merge/dedup logic is tested in isolation (no filesystem, no process).
 */
class LivePollerTest {

    private LiveStatusService registry(List<LiveSession> sessions) {
        return new LiveStatusService(new ClaudeProperties(), null) {
            @Override
            public List<LiveSession> getLiveSessions() {
                return sessions;
            }
        };
    }

    private CliChatEngine chat(List<LiveSession> chatSessions) {
        return new CliChatEngine(new ClaudeProperties(), null, null, null, null) {
            @Override
            public List<LiveSession> runningChatSessions() {
                return chatSessions;
            }
        };
    }

    @Test
    void mergesChatTurnsAsWorkingAndDedupsWorkingWins() {
        LiveStatusService reg = registry(List.of(
                new LiveSession("sess-a", "C:/a", LiveStatus.IDLE),     // idle CLI shell
                new LiveSession("sess-b", "C:/b", LiveStatus.WORKING)));
        CliChatEngine chat = chat(List.of(
                new LiveSession("sess-a", "C:/a", LiveStatus.WORKING),  // a browser turn on the same id
                new LiveSession("sess-c", "C:/c", LiveStatus.WORKING))); // a browser-only session

        LiveSnapshot snap = new LivePoller(reg, chat, null).snapshot();

        Map<String, LiveStatus> byId = snap.sessions().stream()
                .collect(Collectors.toMap(LiveSession::sessionId, LiveSession::status));
        assertEquals(3, byId.size(), "deduped by sessionId");
        assertEquals(LiveStatus.WORKING, byId.get("sess-a"), "a live chat turn wins over registry idle");
        assertEquals(LiveStatus.WORKING, byId.get("sess-b"));
        assertEquals(LiveStatus.WORKING, byId.get("sess-c"), "browser-only session is included");
        assertEquals(0, snap.waitingCount(), "no session needs a decision");
    }

    @Test
    void chatWaitingOutranksRegistryWorkingForTheSameSession() {
        // A turn parked on a permission card needs the user's decision — that must win even when a
        // registry entry reports the same session as busy.
        LiveStatusService reg = registry(List.of(
                new LiveSession("sess-a", "C:/a", LiveStatus.WORKING)));
        CliChatEngine chat = chat(List.of(
                new LiveSession("sess-a", "C:/a", LiveStatus.WAITING)));

        LiveSnapshot snap = new LivePoller(reg, chat, null).snapshot();

        assertEquals(1, snap.sessions().size());
        assertEquals(LiveStatus.WAITING, snap.sessions().get(0).status());
        assertEquals(1, snap.waitingCount());
    }

    @Test
    void waitingCountIgnoresIdleShellsAndCountsParkedCards() {
        // Idle shells are just open terminals — only a parked permission/question card is "waiting".
        LiveStatusService reg = registry(List.of(
                new LiveSession("idle-1", "C:/1", LiveStatus.IDLE),
                new LiveSession("idle-2", "C:/2", LiveStatus.IDLE)));
        CliChatEngine chat = chat(List.of(
                new LiveSession("card-1", "C:/3", LiveStatus.WAITING)));

        LiveSnapshot snap = new LivePoller(reg, chat, null).snapshot();
        assertEquals(1, snap.waitingCount(), "two idle shells contribute nothing; one parked card counts");

        Map<String, LiveStatus> byId = snap.sessions().stream()
                .collect(Collectors.toMap(LiveSession::sessionId, LiveSession::status));
        assertEquals(LiveStatus.IDLE, byId.get("idle-1"), "idle shells keep their neutral badge");
    }
}
