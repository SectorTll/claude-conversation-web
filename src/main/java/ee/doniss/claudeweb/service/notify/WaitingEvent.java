package ee.doniss.claudeweb.service.notify;

/**
 * A chat turn parked on an interactive card — the one moment that genuinely needs the user.
 *
 * @param projectId project of the waiting session
 * @param sessionId the waiting session
 * @param kind      {@code permission} or {@code question}
 * @param title     short notification title
 * @param body      human detail (tool name / first question)
 * @param path      in-app deep link, e.g. {@code /p/<projectId>/s/<sessionId>}
 */
public record WaitingEvent(String projectId, String sessionId, String kind,
                           String title, String body, String path) {
}
