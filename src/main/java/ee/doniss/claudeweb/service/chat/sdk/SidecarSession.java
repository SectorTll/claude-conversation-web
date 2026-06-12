package ee.doniss.claudeweb.service.chat.sdk;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.os.StreamingProcessRunner;
import ee.doniss.claudeweb.os.StreamingProcessRunner.InteractiveHandle;
import ee.doniss.claudeweb.service.chat.ChatEvents;
import ee.doniss.claudeweb.support.BadRequestException;
import ee.doniss.claudeweb.web.dto.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One persistent sidecar process bound to one chat session: starts it lazily, writes protocol
 * lines to its stdin over time, translates its stdout events into the simplified browser stream,
 * and owns the per-session timers (the sidecar itself has none — Java is the timeout authority):
 *
 * <ul>
 *   <li><b>activity watchdog</b> ({@code turnTimeout}) — fires when a turn produces no events for
 *       too long, but is suspended while a permission/question waits on the user;</li>
 *   <li><b>auto-deny</b> ({@code permissionTimeout}) — a pending ask the user never answers is
 *       denied so the turn continues deterministically;</li>
 *   <li>the emitter's hard cap ({@code turnHardTimeout}) backstops everything.</li>
 * </ul>
 *
 * <p>Threading: stdout lines arrive on one reader thread; REST calls (turn start, decisions,
 * cancel) come from request threads. All state transitions are funneled through synchronized
 * methods; stdin writes are serialized inside {@link InteractiveHandle}.
 */
final class SidecarSession {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SidecarSession.class);

    /**
     * Pending interactive ask (permission or question): its auto-deny timer, creation order, and
     * the browser-shaped event card — kept so a reloaded/second tab can re-fetch and render the
     * cards it never saw on a stream ({@code GET …/pending}).
     */
    private record Pending(String kind, long seq, Map<String, Object> card, ScheduledFuture<?> autoDeny) {
        static final String PERMISSION = "permission";
        static final String QUESTION = "question";
    }

    /** One streamed turn: its emitter, watchdog, completion flag, and inputs (kept for one replay). */
    private static final class Turn {
        final String turnId;
        final ResponseBodyEmitter emitter;
        final String text;
        final String mode;
        final String model;
        final List<ChatRequest.Attachment> attachments;
        volatile ScheduledFuture<?> watchdog;
        volatile boolean finished;
        /** A turn is replayed at most once after a sidecar that died before its first ready. */
        volatile boolean replayed;

        Turn(String turnId, ResponseBodyEmitter emitter, String text, String mode, String model,
             List<ChatRequest.Attachment> attachments) {
            this.turnId = turnId;
            this.emitter = emitter;
            this.text = text;
            this.mode = mode;
            this.model = model;
            this.attachments = attachments;
        }
    }

    private final ClaudeProperties props;
    private final StreamingProcessRunner runner;
    private final SidecarLocator locator;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    /** Called once the sidecar reports the authoritative session id (re-keys the engine's map). */
    private final Consumer<String> onReady;

    /** A turn just parked on an interactive card — the push-notification hook. Must not block. */
    @FunctionalInterface
    interface WaitingListener {
        void onWaiting(String sessionId, String kind, Map<String, Object> card);
    }

    private final WaitingListener onWaiting;
    private final String cwd;
    private final boolean createdNew;
    private volatile String sessionId;
    // package-private so tests can wire a fake transport without spawning a real Node process
    volatile InteractiveHandle handle;
    /** The permission mode the live process was launched with (see the bypass-boundary restart). */
    private volatile String processMode;
    private volatile boolean readySeen;
    private volatile boolean closed;
    private volatile long lastActivityNanos;

    /**
     * True from {@code user_turn} until the SIDECAR reports {@code turn_done}/{@code turn_error}
     * (or the process dies / a timeout gives up). Deliberately independent of the emitter: a closed
     * browser tab must not make a still-running turn look free — that would drop the live badge and
     * let a second turn collide with it in the same SDK session.
     */
    private volatile boolean sidecarBusy;

    private volatile Turn turn;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong pendingSeq = new java.util.concurrent.atomic.AtomicLong();

    SidecarSession(ClaudeProperties props, StreamingProcessRunner runner, SidecarLocator locator,
                   ObjectMapper mapper, Executor executor, ScheduledExecutorService scheduler,
                   LongSupplier nanoTime, String sessionId, String cwd, boolean createdNew,
                   Consumer<String> onReady) {
        this(props, runner, locator, mapper, executor, scheduler, nanoTime, sessionId, cwd, createdNew,
                onReady, (sid, kind, card) -> {
                });
    }

    SidecarSession(ClaudeProperties props, StreamingProcessRunner runner, SidecarLocator locator,
                   ObjectMapper mapper, Executor executor, ScheduledExecutorService scheduler,
                   LongSupplier nanoTime, String sessionId, String cwd, boolean createdNew,
                   Consumer<String> onReady, WaitingListener onWaiting) {
        this.props = props;
        this.runner = runner;
        this.locator = locator;
        this.mapper = mapper;
        this.executor = executor;
        this.scheduler = scheduler;
        this.nanoTime = nanoTime;
        this.sessionId = sessionId;
        this.cwd = cwd;
        this.createdNew = createdNew;
        this.onReady = onReady;
        this.onWaiting = onWaiting;
        this.lastActivityNanos = nanoTime.getAsLong();
    }

    String sessionId() {
        return sessionId;
    }

    String cwd() {
        return cwd;
    }

    // ----------------------------------------------------------------- turn lifecycle

    /**
     * True while the sidecar is working a turn — regardless of whether a browser is still attached
     * to its stream. This is the one-turn-per-session gate AND the live-badge source.
     */
    boolean turnActive() {
        return sidecarBusy;
    }

    /** True when the active turn (if any) is parked on a permission/question card. */
    boolean hasPendingAsk() {
        return !pending.isEmpty();
    }

    /** The unanswered cards of the in-flight turn, oldest first, browser-event shaped. */
    List<Map<String, Object>> pendingCards() {
        return pending.values().stream()
                .sorted(java.util.Comparator.comparingLong(Pending::seq))
                .map(Pending::card)
                .toList();
    }

    boolean idleLongerThan(long nanos) {
        return !turnActive() && pending.isEmpty() && nanoTime.getAsLong() - lastActivityNanos > nanos;
    }

    /** Text-only turn (kept for tests and parity with the pre-image protocol). */
    ResponseBodyEmitter startTurn(String text, String mode, String model) {
        return startTurn(text, mode, model, null);
    }

    /** Start one turn; the caller (engine) has already enforced the one-turn-per-session rule. */
    synchronized ResponseBodyEmitter startTurn(String text, String mode, String model,
                                               List<ChatRequest.Attachment> attachments) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(props.getChat().getTurnHardTimeout().toMillis());
        Turn t = new Turn(UUID.randomUUID().toString(), emitter, text, mode, model, attachments);
        turn = t;
        emitter.onCompletion(() -> finishTurn(t));
        emitter.onError(e -> finishTurn(t));
        emitter.onTimeout(() -> {
            send(ChatEvents.event("error", "message", "turn hard-timeout exceeded"));
            interruptQuietly();
            sidecarBusy = false; // we gave up on this turn; a late turn_done is ignored
            finishTurn(t);
            completeQuietly(emitter);
        });

        try {
            ensureProcess(mode);
            writeLine(SidecarProtocol.userTurn(t.turnId, text, mode, model, attachments));
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            ChatEvents.send(emitter, mapper, ChatEvents.event("error",
                    "message", "failed to launch chat sidecar: " + cause.getMessage()));
            finishTurn(t);
            completeQuietly(emitter);
            return emitter;
        }

        sidecarBusy = true;
        touch();
        armWatchdog(t);
        return emitter;
    }

    /** Interrupt the in-flight turn (the sidecar denies pending asks and stops). Process stays warm. */
    synchronized boolean cancelTurn() {
        if (!sidecarBusy) {
            return false;
        }
        interruptQuietly();
        return true;
    }

    // ----------------------------------------------------------------- user decisions

    void decide(String requestId, boolean allow, String message, boolean always) {
        takePending(requestId, Pending.PERMISSION);
        writeLine(SidecarProtocol.permissionResponse(requestId, allow, message, always));
        touch(); // resolving the ask resets the activity clock — the watchdog resumes from here
    }

    void answer(String requestId, List<List<String>> answers) {
        takePending(requestId, Pending.QUESTION);
        writeLine(SidecarProtocol.questionAnswer(requestId, answers));
        touch();
    }

    private void takePending(String requestId, String expectedKind) {
        Pending p = pending.remove(requestId);
        if (p == null) {
            throw new BadRequestException("no pending request " + requestId + " for this session");
        }
        if (!p.kind().equals(expectedKind)) {
            // put it back — the caller used the wrong endpoint, the ask is still answerable
            pending.put(requestId, p);
            throw new BadRequestException("request " + requestId + " is a " + p.kind() + ", not a " + expectedKind);
        }
        p.autoDeny().cancel(false);
    }

    // ----------------------------------------------------------------- process management

    private void ensureProcess(String mode) {
        InteractiveHandle h = handle;
        if (h != null && h.process().isAlive()) {
            if (!crossesBypassBoundary(processMode, mode)) {
                return;
            }
            // bypassPermissions is a LAUNCH capability of the CLI (--dangerously-skip-permissions),
            // not a switchable mode: setPermissionMode INTO it on a live process is refused, and
            // OUT of it the flagged process could keep skipping prompts silently. Relaunch instead —
            // the session is on disk, the fresh sidecar resumes it. No turn is running here (the
            // engine serializes turns), so killing the warm process loses nothing.
            log.info("chat session {}: permission mode {} -> {} crosses the bypass boundary — relaunching sidecar",
                    sessionId, processMode, mode);
            StreamingProcessRunner.killTree(h.process());
        }
        // (Re)start. After the first ready the session exists on disk, so a restart always resumes.
        boolean resume = !createdNew || readySeen;
        String sidecar = locator.locate().toString();
        List<String> cmd = List.of(props.getChat().getNodeExecutable(), sidecar);
        InteractiveHandle fresh = runner.startInteractive(cmd, cwd, this::onLine, executor);
        fresh.exit().whenComplete((code, ex) -> onProcessExit(fresh, code));
        handle = fresh;
        fresh.writeLine(mapper.writeValueAsString(SidecarProtocol.init(
                sessionId, resume, cwd, mode,
                SidecarLocator.resolveExecutable(props.getChat().getExecutable()))));
        processMode = mode;
    }

    private static boolean crossesBypassBoundary(String oldMode, String newMode) {
        return !java.util.Objects.equals(oldMode, newMode)
                && ("bypassPermissions".equals(oldMode) || "bypassPermissions".equals(newMode));
    }

    private synchronized void onProcessExit(InteractiveHandle h, Integer code) {
        if (handle != h) {
            return; // an old process; a fresh one already took over
        }
        Turn t = turn;
        // A sidecar that died before its first ready is most likely a transient spawn failure
        // (cold AV scan of the fresh bundle and the like) — restart and replay the turn once.
        if (t != null && !t.finished && !readySeen && !t.replayed) {
            t.replayed = true;
            log.warn("chat sidecar for session {} died before ready (exit {}) — retrying once: {}",
                    sessionId, code, truncate(h.stderr().strip(), 300));
            pending.clear();
            try {
                ensureProcess(t.mode);
                writeLine(SidecarProtocol.userTurn(t.turnId, t.text, t.mode, t.model, t.attachments));
                touch();
                return; // sidecarBusy stays true — the replayed turn is still running
            } catch (RuntimeException retryFailure) {
                // fall through to the error path below
            }
        }
        sidecarBusy = false;
        if (t != null && !t.finished) {
            String err = h.stderr().strip();
            String msg = "chat sidecar exited with code " + code;
            if (!err.isEmpty()) {
                msg += ": " + truncate(err, 600);
            }
            send(ChatEvents.event("error", "message", msg));
            finishTurn(t);
            completeQuietly(t.emitter);
        }
        pending.clear();
    }

    /** Graceful stop (idle reap / app shutdown): polite shutdown line, then kill after a grace period. */
    synchronized void close() {
        closed = true;
        InteractiveHandle h = handle;
        if (h == null) {
            return;
        }
        try {
            h.writeLine(mapper.writeValueAsString(SidecarProtocol.shutdown()));
        } catch (RuntimeException ignore) {
            // already gone
        }
        h.closeStdin();
        scheduler.schedule(() -> {
            if (h.process().isAlive()) {
                StreamingProcessRunner.killTree(h.process());
            }
        }, 5, TimeUnit.SECONDS);
    }

    boolean isClosed() {
        return closed;
    }

    // ----------------------------------------------------------------- sidecar -> browser translation

    /** One stdout NDJSON line from the sidecar; package-private for direct unit testing. */
    void onLine(String line) {
        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (Exception e) {
            return; // not protocol JSON (stray output) — ignore
        }
        if (root == null || !root.isObject()) {
            return;
        }
        String type = text(root, "type");
        if (type == null) {
            return;
        }
        touch();
        switch (type) {
            case "ready" -> {
                String actual = text(root, "sessionId");
                if (actual != null && !actual.isBlank()) {
                    readySeen = true;
                    sessionId = actual;
                    log.info("chat sidecar ready: session={} agent-sdk={}", actual, text(root, "sdkVersion"));
                    onReady.accept(actual);
                    if (createdNew) {
                        send(ChatEvents.event("session", "sessionId", actual));
                    }
                }
            }
            case "text_delta" -> {
                String t = text(root, "text");
                if (t != null && !t.isEmpty()) {
                    send(ChatEvents.event("text-delta", "text", t));
                }
            }
            case "thinking_delta" -> {
                String t = text(root, "text");
                if (t != null && !t.isEmpty()) {
                    send(ChatEvents.event("thinking-delta", "text", t));
                }
            }
            case "tool" -> {
                String title = text(root, "title");
                send(ChatEvents.event("tool",
                        "title", title != null ? title : "tool",
                        "toolUseId", text(root, "toolUseId")));
            }
            case "tool_result" -> send(ChatEvents.event("tool-result",
                    "toolUseId", text(root, "toolUseId"),
                    "body", text(root, "body")));
            case "permission_request" -> {
                String requestId = text(root, "requestId");
                if (requestId != null) {
                    Map<String, Object> card = ChatEvents.event("permission",
                            "requestId", requestId,
                            "toolName", text(root, "toolName"),
                            "input", mapper.convertValue(root.get("input"), Object.class),
                            "suggestions", mapper.convertValue(root.get("suggestions"), Object.class));
                    registerPending(requestId, Pending.PERMISSION, card);
                    send(card);
                }
            }
            case "question" -> {
                String requestId = text(root, "requestId");
                if (requestId != null) {
                    Map<String, Object> card = ChatEvents.event("question",
                            "requestId", requestId,
                            "questions", mapper.convertValue(root.get("questions"), Object.class));
                    registerPending(requestId, Pending.QUESTION, card);
                    send(card);
                }
            }
            case "turn_started" -> { /* activity only (touch() above) */ }
            case "turn_done" -> endTurn(null);
            case "turn_error" -> {
                String msg = text(root, "message");
                endTurn(msg != null ? msg : "turn failed");
            }
            case "fatal" -> {
                endTurn("chat sidecar failed: " + text(root, "message"));
                InteractiveHandle h = handle;
                if (h != null) {
                    StreamingProcessRunner.killTree(h.process());
                }
            }
            default -> { /* unknown events are activity only */ }
        }
    }

    private synchronized void endTurn(String error) {
        sidecarBusy = false;
        Turn t = turn;
        if (t == null || t.finished) {
            return;
        }
        if (error != null) {
            send(ChatEvents.event("error", "message", error));
        } else {
            send(ChatEvents.event("done"));
        }
        finishTurn(t);
        completeQuietly(t.emitter);
    }

    private void registerPending(String requestId, String kind, Map<String, Object> card) {
        long timeoutMs = props.getChat().getPermissionTimeout().toMillis();
        ScheduledFuture<?> autoDeny = scheduler.schedule(() -> autoDeny(requestId, kind), timeoutMs, TimeUnit.MILLISECONDS);
        // While this entry exists the watchdog skips its idle check — a human thinking is not a hang.
        pending.put(requestId, new Pending(kind, pendingSeq.incrementAndGet(), card, autoDeny));
        // The session just turned WAITING — let the push channels ping a user without an open tab.
        // (We're on the sidecar stdout reader thread: the listener must only enqueue, never block.)
        try {
            onWaiting.onWaiting(sessionId, kind, card);
        } catch (RuntimeException e) {
            log.warn("waiting listener failed: {}", e.toString());
        }
    }

    /** Nobody answered the card in time — deny so the turn continues instead of hanging forever. */
    private void autoDeny(String requestId, String kind) {
        if (pending.remove(requestId) == null) {
            return; // answered in the meantime
        }
        try {
            if (Pending.QUESTION.equals(kind)) {
                writeLine(SidecarProtocol.questionAnswer(requestId, List.of()));
            } else {
                writeLine(SidecarProtocol.permissionResponse(requestId, false,
                        "timed out waiting for approval in the browser", false));
            }
        } catch (RuntimeException ignore) {
            // sidecar gone — the exit handler cleans up
        }
        touch();
    }

    // ----------------------------------------------------------------- timers

    private void touch() {
        lastActivityNanos = nanoTime.getAsLong();
    }

    private void armWatchdog(Turn t) {
        long periodMs = Math.max(500, props.getChat().getTurnTimeout().toMillis() / 20);
        t.watchdog = scheduler.scheduleWithFixedDelay(() -> checkWatchdog(t), periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private void checkWatchdog(Turn t) {
        if (t.finished) {
            cancelWatchdog(t);
            return;
        }
        if (!pending.isEmpty()) {
            return; // suspended: a human is thinking
        }
        long idleNanos = nanoTime.getAsLong() - lastActivityNanos;
        if (idleNanos > props.getChat().getTurnTimeout().toNanos()) {
            send(ChatEvents.event("error", "message", "turn timed out (no activity)"));
            interruptQuietly();
            synchronized (this) {
                sidecarBusy = false; // give up — don't let a wedged sidecar hold the slot forever
                finishTurn(t);
                completeQuietly(t.emitter);
            }
        }
    }

    private void finishTurn(Turn t) {
        t.finished = true;
        cancelWatchdog(t);
    }

    private void cancelWatchdog(Turn t) {
        ScheduledFuture<?> w = t.watchdog;
        if (w != null) {
            w.cancel(false);
        }
    }

    // ----------------------------------------------------------------- plumbing

    private void interruptQuietly() {
        try {
            writeLine(SidecarProtocol.interrupt());
        } catch (RuntimeException ignore) {
            InteractiveHandle h = handle;
            if (h != null) {
                StreamingProcessRunner.killTree(h.process());
            }
        }
    }

    private void writeLine(Map<String, Object> message) {
        InteractiveHandle h = handle;
        if (h == null) {
            throw new IllegalStateException("sidecar not started");
        }
        h.writeLine(mapper.writeValueAsString(message));
    }

    private void send(Map<String, Object> evt) {
        Turn t = turn;
        if (t != null && !t.finished) {
            ChatEvents.send(t.emitter, mapper, evt);
        }
    }

    private static void completeQuietly(ResponseBodyEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // already completed/closed
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
