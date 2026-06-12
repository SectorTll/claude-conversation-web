package ee.doniss.claudeweb.service.notify;

import java.util.List;

/**
 * A chat turn parked on an interactive card — the one moment that genuinely needs the user.
 *
 * @param projectId project of the waiting session
 * @param sessionId the waiting session
 * @param kind      {@code permission} or {@code question}
 * @param title     short notification title
 * @param body      human detail (tool name / first question)
 * @param path      in-app deep link, e.g. {@code /p/<projectId>/s/<sessionId>}
 * @param requestId the card's id — lets a sender offer answering remotely (null = display only)
 * @param options   answer labels for a single-question card (empty for permissions/multi-question)
 */
public record WaitingEvent(String projectId, String sessionId, String kind,
                           String title, String body, String path,
                           String requestId, List<String> options) {

    /** Display-only event (test pings, paths that don't carry interactive-answer data). */
    public WaitingEvent(String projectId, String sessionId, String kind,
                        String title, String body, String path) {
        this(projectId, sessionId, kind, title, body, path, null, List.of());
    }
}
