package ee.doniss.claudeweb.service.chat;

import ee.doniss.claudeweb.domain.LiveSession;
import ee.doniss.claudeweb.support.BadRequestException;
import ee.doniss.claudeweb.web.dto.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;
import java.util.Map;

/**
 * A backend that drives one in-browser chat turn and streams simplified NDJSON events
 * ({@code session} | {@code text-delta} | {@code tool} | {@code question} | {@code permission} |
 * {@code done} | {@code error}) to the browser. Two implementations exist, selected by
 * {@code claude.chat.engine}:
 *
 * <ul>
 *   <li>{@link CliChatEngine} ({@code cli}) — spawns a fresh {@code claude -p} process per turn.
 *       Headless: permissions are frozen per turn and {@code AskUserQuestion} ends the turn.</li>
 *   <li>{@code SdkChatEngine} ({@code sdk}) — keeps a Node sidecar (Claude Agent SDK) per session.
 *       Interactive: tool permissions and questions pause the turn and are answered via
 *       {@link #decide} / {@link #answer}.</li>
 * </ul>
 */
public interface ChatEngine {

    /**
     * Send {@code text} (plus optional image {@code attachments}) to an existing session and stream
     * the reply. {@code model} may be blank (default).
     */
    ResponseBodyEmitter sendToSession(String projectId, String sessionId, String text, String modeOverride,
                                      String model, List<ChatRequest.Attachment> attachments);

    /** Create a new session (generated UUID) in the project's working dir and stream the first turn. */
    ResponseBodyEmitter createSessionAndSend(String projectId, String text, String modeOverride, String model,
                                             List<ChatRequest.Attachment> attachments);

    /** Stop an in-flight turn. Idempotent; false if nothing was running. */
    boolean cancel(String sessionId);

    /** The alive in-browser turns as live-sessions, for the live snapshot/badges. */
    List<LiveSession> runningChatSessions();

    /** Short identifier reported to the browser via the capabilities endpoint. */
    String engineName();

    /**
     * Resolve a pending tool-permission request (allow/deny). {@code always} additionally persists
     * the SDK's suggested "don't ask again" rules. Only meaningful on engines that surface
     * interactive {@code permission} events.
     */
    default void decide(String sessionId, String requestId, boolean allow, String message, boolean always) {
        throw new BadRequestException("interactive permissions are not supported by the " + engineName() + " engine");
    }

    /**
     * Answer a pending in-turn {@code AskUserQuestion}. Only meaningful on engines that keep the
     * turn open at the question.
     */
    default void answer(String sessionId, String requestId, List<List<String>> answers) {
        throw new BadRequestException("in-turn answers are not supported by the " + engineName() + " engine");
    }

    /**
     * The unanswered permission/question cards of a session's in-flight turn, as browser-shaped
     * events — lets a reloaded page or second tab render and answer cards it never saw streamed.
     */
    default List<Map<String, Object>> pendingAsks(String sessionId) {
        return List.of();
    }
}
