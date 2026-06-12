package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.service.ClaudeDataService;
import ee.doniss.claudeweb.service.chat.ChatEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The global "what needs me" panel: every chat turn currently parked on an unanswered
 * permission/question card, across all projects, with the cards themselves (browser-shaped, same
 * vocabulary as the per-session {@code …/pending} endpoint) and the session title for display.
 */
@RestController
public class WaitingController {

    private final ChatEngine chat;
    private final ClaudeDataService data;

    public WaitingController(ChatEngine chat, ClaudeDataService data) {
        this.chat = chat;
        this.data = data;
    }

    @GetMapping("/api/waiting")
    public List<Map<String, Object>> waiting() {
        List<Map<String, Object>> out = new ArrayList<>();
        chat.waitingSessions().forEach((sessionId, projectId) -> {
            List<Map<String, Object>> cards = chat.pendingAsks(sessionId);
            if (cards.isEmpty()) {
                return; // answered between the two calls — nothing to show
            }
            out.add(Map.of(
                    "projectId", projectId,
                    "sessionId", sessionId,
                    "title", titleOf(projectId, sessionId),
                    "cards", cards));
        });
        return out;
    }

    /** Display title from the store; a session not (yet) on disk falls back to its short id. */
    private String titleOf(String projectId, String sessionId) {
        try {
            return data.getSession(projectId, sessionId).getTitle();
        } catch (RuntimeException e) {
            return sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
        }
    }
}
