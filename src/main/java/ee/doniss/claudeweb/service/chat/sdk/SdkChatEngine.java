package ee.doniss.claudeweb.service.chat.sdk;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.LiveSession;
import ee.doniss.claudeweb.domain.LiveStatus;
import ee.doniss.claudeweb.os.StreamingProcessRunner;
import ee.doniss.claudeweb.service.ClaudeDataService;
import ee.doniss.claudeweb.service.chat.ChatAttachments;
import ee.doniss.claudeweb.service.chat.ChatEngine;
import ee.doniss.claudeweb.service.chat.ChatModels;
import ee.doniss.claudeweb.service.chat.PermissionModes;
import ee.doniss.claudeweb.service.notify.NotificationDispatcher;
import ee.doniss.claudeweb.support.BadRequestException;
import ee.doniss.claudeweb.support.ConflictException;
import ee.doniss.claudeweb.web.dto.ChatRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The {@code sdk} chat engine: one persistent Node sidecar (Claude Agent SDK) per active session,
 * so turns have no cold start, tool permissions become interactive browser cards, and
 * {@code AskUserQuestion} is answered within the same turn. The SDK drives the system {@code claude}
 * CLI underneath, so the canonical {@code .jsonl} keeps being written exactly as with the
 * {@code cli} engine.
 *
 * <p>SECURITY: the permission mode is still resolved server-side ({@link PermissionModes}); what
 * this engine adds is the {@code default} mode becoming a real interactive gate — every
 * non-allowlisted tool waits for an authenticated browser's allow/deny (auto-deny on timeout).
 */
@Service
public class SdkChatEngine implements ChatEngine {

    private final ClaudeProperties props;
    private final StreamingProcessRunner runner;
    private final SidecarLocator locator;
    private final ClaudeDataService data;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final NotificationDispatcher dispatcher;

    /** Live sidecar sessions keyed by session id (re-keyed once the sidecar reports the real id). */
    private final ConcurrentHashMap<String, SidecarSession> sessions = new ConcurrentHashMap<>();

    /** Which project each live session belongs to — the global WAITING panel needs the deep link. */
    private final ConcurrentHashMap<String, String> projectBySession = new ConcurrentHashMap<>();

    public SdkChatEngine(ClaudeProperties props, StreamingProcessRunner runner, SidecarLocator locator,
                         ClaudeDataService data, ObjectMapper mapper,
                         @Qualifier("claudeChatExecutor") Executor executor,
                         @Qualifier("claudeChatScheduler") ScheduledExecutorService scheduler,
                         NotificationDispatcher dispatcher) {
        this.props = props;
        this.runner = runner;
        this.locator = locator;
        this.data = data;
        this.mapper = mapper;
        this.executor = executor;
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
    }

    @Override
    public String engineName() {
        return "sdk";
    }

    @Override
    public ResponseBodyEmitter sendToSession(String projectId, String sessionId, String text, String modeOverride,
                                             String model, List<ChatRequest.Attachment> attachments) {
        ChatAttachments.validate(attachments, props.getChat());
        data.sessionPath(projectId, sessionId); // 404 before we touch any process
        String mode = PermissionModes.resolve(props.getChat(), modeOverride);
        String cwd = data.sessionWorkingDir(projectId, sessionId);
        SidecarSession session = sessions.computeIfAbsent(sessionId,
                id -> newSession(projectId, id, cwd, false));
        projectBySession.put(sessionId, projectId);
        return startGuarded(session, text, mode, ChatModels.resolve(model), attachments);
    }

    @Override
    public ResponseBodyEmitter createSessionAndSend(String projectId, String text, String modeOverride, String model,
                                                    List<ChatRequest.Attachment> attachments) {
        ChatAttachments.validate(attachments, props.getChat());
        String mode = PermissionModes.resolve(props.getChat(), modeOverride);
        String cwd = data.projectWorkingDir(projectId);
        String sessionId = UUID.randomUUID().toString();
        SidecarSession session = newSession(projectId, sessionId, cwd, true);
        sessions.put(sessionId, session);
        projectBySession.put(sessionId, projectId);
        return startGuarded(session, text, mode, ChatModels.resolve(model), attachments);
    }

    private SidecarSession newSession(String projectId, String sessionId, String cwd, boolean createdNew) {
        return new SidecarSession(props, runner, locator, mapper, executor, scheduler,
                System::nanoTime, sessionId, cwd, createdNew,
                actualId -> rekey(sessionId, actualId),
                (sid, kind, card) -> dispatcher.onWaiting(projectId, sid, kind, card));
    }

    /** A turn may already be streaming (other tab / queued send) — that is a 409, not a clobber. */
    private ResponseBodyEmitter startGuarded(SidecarSession session, String text, String mode, String model,
                                             List<ChatRequest.Attachment> attachments) {
        synchronized (session) {
            if (session.turnActive()) {
                throw new ConflictException("a chat turn is already running for this session");
            }
            return session.startTurn(text, mode, model, attachments);
        }
    }

    /** The sidecar's {@code ready} id is authoritative; move the map entry if it differs. */
    private void rekey(String requestedId, String actualId) {
        if (requestedId.equals(actualId)) {
            return;
        }
        SidecarSession s = sessions.remove(requestedId);
        if (s != null) {
            sessions.put(actualId, s);
        }
        String pid = projectBySession.remove(requestedId);
        if (pid != null) {
            projectBySession.put(actualId, pid);
        }
    }

    @Override
    public boolean cancel(String sessionId) {
        SidecarSession s = sessions.get(sessionId);
        return s != null && s.cancelTurn();
    }

    @Override
    public void decide(String sessionId, String requestId, boolean allow, String message, boolean always) {
        required(sessionId).decide(requestId, allow, message, always);
    }

    @Override
    public void answer(String sessionId, String requestId, List<List<String>> answers) {
        required(sessionId).answer(requestId, answers);
    }

    @Override
    public List<java.util.Map<String, Object>> pendingAsks(String sessionId) {
        SidecarSession s = sessions.get(sessionId);
        return s == null ? List.of() : s.pendingCards();
    }

    @Override
    public java.util.Map<String, String> waitingSessions() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        sessions.forEach((sid, s) -> {
            String pid = projectBySession.get(sid);
            if (pid != null && s.hasPendingAsk()) {
                out.put(sid, pid);
            }
        });
        return out;
    }

    private SidecarSession required(String sessionId) {
        SidecarSession s = sessions.get(sessionId);
        if (s == null) {
            throw new BadRequestException("no active chat session " + sessionId);
        }
        return s;
    }

    @Override
    public List<LiveSession> runningChatSessions() {
        List<LiveSession> out = new ArrayList<>();
        sessions.forEach((sid, s) -> {
            if (s.turnActive()) {
                // A turn parked on a permission/question card is "waiting for YOU", same as a
                // terminal session at a prompt — the orange badge is what makes a card in a
                // background tab discoverable.
                LiveStatus status = s.hasPendingAsk() ? LiveStatus.WAITING : LiveStatus.WORKING;
                out.add(new LiveSession(sid, s.cwd(), status));
            }
        });
        return out;
    }

    /** Reap sidecars that have sat idle (no turn, no pending ask) past the configured timeout. */
    @Scheduled(fixedDelay = 60_000)
    public void reapIdleSessions() {
        long idleNanos = props.getChat().getSessionIdleTimeout().toNanos();
        sessions.forEach((sid, s) -> {
            if (s.idleLongerThan(idleNanos)) {
                s.close();
                sessions.remove(sid, s);
                projectBySession.remove(sid);
            }
        });
    }

    @PreDestroy
    public void shutdownAll() {
        sessions.forEach((sid, s) -> s.close());
        sessions.clear();
        projectBySession.clear();
    }
}
