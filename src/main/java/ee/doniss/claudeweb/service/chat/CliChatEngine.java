package ee.doniss.claudeweb.service.chat;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.LiveSession;
import ee.doniss.claudeweb.domain.LiveStatus;
import ee.doniss.claudeweb.os.StreamingProcessRunner;
import ee.doniss.claudeweb.service.ClaudeDataService;
import ee.doniss.claudeweb.service.Format;
import ee.doniss.claudeweb.support.ConflictException;
import ee.doniss.claudeweb.web.dto.ChatRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The {@code cli} chat engine: drives the {@code claude} CLI headlessly for one chat turn and
 * streams its reply to the browser.
 *
 * <p>Model: one fresh {@code claude -p ... --output-format stream-json} process per turn that
 * resumes the persisted session (so the CLI keeps writing the canonical {@code .jsonl}; the browse
 * views reload from disk afterwards). The process' NDJSON event stream is parsed and a simplified
 * stream is pushed to the {@link ResponseBodyEmitter}: {@code session} (new sessions only, first),
 * {@code text-delta}, {@code tool}, {@code question}, {@code done}, {@code error}.
 *
 * <p>SECURITY: headless mode cannot prompt for permission, so the resolved permission mode is final
 * for the turn (see {@link ClaudeProperties.Chat}).
 */
@Service
public class CliChatEngine implements ChatEngine {

    /** The CLI tool whose calls we surface as interactive questions instead of generic tool blocks. */
    private static final String QUESTION_TOOL = "AskUserQuestion";

    private final ClaudeProperties props;
    private final StreamingProcessRunner runner;
    private final ClaudeDataService data;
    private final ObjectMapper mapper;
    private final Executor executor;

    /** One in-flight turn per session id (prevents concurrent turns clobbering a session). */
    private final ConcurrentHashMap<String, RunningTurn> running = new ConcurrentHashMap<>();

    public CliChatEngine(ClaudeProperties props, StreamingProcessRunner runner,
                         ClaudeDataService data, ObjectMapper mapper,
                         @Qualifier("claudeChatExecutor") Executor executor) {
        this.props = props;
        this.runner = runner;
        this.data = data;
        this.mapper = mapper;
        this.executor = executor;
    }

    @Override
    public String engineName() {
        return "cli";
    }

    /** Send {@code text} to an existing session and stream the reply. */
    @Override
    public ResponseBodyEmitter sendToSession(String projectId, String sessionId, String text, String modeOverride,
                                             String model, List<ChatRequest.Attachment> attachments) {
        rejectAttachments(attachments);
        data.sessionPath(projectId, sessionId); // 404 before we spawn anything
        String mode = resolveMode(modeOverride);
        String cwd = data.sessionWorkingDir(projectId, sessionId);
        return startTurn(sessionId, cwd, text, mode, ChatModels.resolve(model), true);
    }

    /** Create a new session (generated UUID) in the project's working dir and stream the first turn. */
    @Override
    public ResponseBodyEmitter createSessionAndSend(String projectId, String text, String modeOverride, String model,
                                                    List<ChatRequest.Attachment> attachments) {
        rejectAttachments(attachments);
        String mode = resolveMode(modeOverride);
        String cwd = data.projectWorkingDir(projectId);
        String sessionId = UUID.randomUUID().toString();
        return startTurn(sessionId, cwd, text, mode, ChatModels.resolve(model), false);
    }

    /** Headless {@code claude -p} takes the prompt as plain stdin text — there is no image channel. */
    private static void rejectAttachments(List<ChatRequest.Attachment> attachments) {
        if (ChatAttachments.any(attachments)) {
            throw new ee.doniss.claudeweb.support.BadRequestException(
                    "image attachments require the sdk chat engine");
        }
    }

    /** Kill an in-flight turn's process (and its children). Idempotent; false if nothing was running. */
    @Override
    public boolean cancel(String sessionId) {
        RunningTurn turn = running.get(sessionId);
        if (turn == null) {
            return false;
        }
        turn.finished = true;
        StreamingProcessRunner.Handle h = turn.handle;
        if (h != null) {
            StreamingProcessRunner.killTree(h.process());
        }
        running.remove(sessionId, turn);
        return true;
    }

    // ----------------------------------------------------------------- internals

    private ResponseBodyEmitter startTurn(String sessionId, String cwd, String text, String mode, String model,
                                          boolean resume) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(props.getChat().getTurnTimeout().toMillis());
        RunningTurn turn = new RunningTurn(emitter, cwd);
        if (!reserve(sessionId, turn)) {
            throw new ConflictException("a chat turn is already running for this session");
        }

        emitter.onCompletion(() -> {
            turn.finished = true;
            running.remove(sessionId, turn);
        });
        emitter.onError(t -> cancelInternal(sessionId, turn));
        emitter.onTimeout(() -> {
            send(emitter, event("error", "message", "turn timed out"));
            cancelInternal(sessionId, turn);
            emitter.complete();
        });

        if (!resume) {
            send(emitter, event("session", "sessionId", sessionId));
        }

        List<String> cmd = buildCommand(sessionId, mode, model, resume);
        TurnParse parse = new TurnParse();
        try {
            StreamingProcessRunner.Handle handle =
                    runner.start(cmd, cwd, text, line -> onLine(emitter, turn, parse, line), executor);
            turn.handle = handle;
            // If the turn was already wrapped up at a question while still starting (handle wasn't set
            // when the question arrived), kill now that we hold the process.
            if (turn.endedAtQuestion) {
                StreamingProcessRunner.killTree(handle.process());
            }
            handle.exit().whenComplete((code, ex) -> finish(emitter, handle, code, turn));
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            send(emitter, event("error", "message", "failed to launch claude: " + cause.getMessage()));
            turn.finished = true;
            running.remove(sessionId, turn);
            emitter.complete();
        }
        return emitter;
    }

    // package-private: the CLI contract is unit-tested directly in CliChatEngineTest.
    List<String> buildCommand(String sessionId, String mode, String model, boolean resume) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getChat().getExecutable());
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--include-partial-messages");
        cmd.add("--verbose"); // required for stream-json to emit events in print mode
        cmd.add("--permission-mode");
        cmd.add(mode);
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        if (resume) {
            cmd.add("--resume");
        } else {
            cmd.add("--session-id");
        }
        cmd.add(sessionId);
        return cmd;
    }

    /** Translate one CLI stream-json line into simplified browser events on the emitter. */
    private void onLine(ResponseBodyEmitter emitter, RunningTurn turn, TurnParse ctx, String line) {
        parseLine(line, ctx, evt -> {
            if (turn.endedAtQuestion) {
                return; // turn already wrapped up at the question — drop the model's fallback output
            }
            send(emitter, evt);
            if ("question".equals(evt.get("type"))) {
                endAtQuestion(emitter, turn);
            }
        });
    }

    /**
     * End the turn the moment an interactive question is emitted. Headless {@code claude -p} can't
     * pause for {@code AskUserQuestion}: the CLI auto-errors the tool and the model would continue
     * this same turn with a fallback answer (polluting the canonical {@code .jsonl}). So we send
     * {@code done} and kill the process tree here; the browser keeps the interactive card and the
     * user's pick is sent as the next turn (resume). The kill's non-zero exit is expected — see
     * {@link #finish}. Package-private so the contract is unit-tested directly.
     */
    void endAtQuestion(ResponseBodyEmitter emitter, RunningTurn turn) {
        turn.endedAtQuestion = true;
        send(emitter, event("done"));
        StreamingProcessRunner.Handle h = turn.handle;
        if (h != null) {
            StreamingProcessRunner.killTree(h.process()); // handle null only if the question raced startup — startTurn kills then
        }
    }

    /**
     * Pure translation of one CLI stream-json line into zero or more simplified browser events handed
     * to {@code out}. {@code ctx} carries per-turn state so a tool's streamed input
     * ({@code input_json_delta} fragments) can be reassembled across lines — needed for
     * {@code AskUserQuestion}, whose questions/options arrive incrementally. Package-private so the
     * translation contract is unit-tested directly.
     */
    void parseLine(String line, TurnParse ctx, Consumer<Map<String, Object>> out) {
        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (Exception e) {
            return; // not JSON / partial — ignore
        }
        if (root == null || !root.isObject() || !"stream_event".equals(str(root, "type"))) {
            return;
        }
        JsonNode evt = root.get("event");
        if (evt == null) {
            return;
        }
        String etype = str(evt, "type");
        if ("content_block_start".equals(etype)) {
            JsonNode cb = evt.get("content_block");
            if (cb != null && "tool_use".equals(str(cb, "type"))) {
                String name = str(cb, "name");
                name = name != null ? name : "tool";
                int index = intVal(evt, "index");
                ctx.toolNames.put(index, name);
                ctx.toolInputs.put(index, new StringBuilder());
                // AskUserQuestion becomes an interactive question (built once its streamed input
                // arrives), not an empty generic tool block.
                if (!QUESTION_TOOL.equals(name)) {
                    out.accept(event("tool", "title", name));
                }
            }
        } else if ("content_block_delta".equals(etype)) {
            JsonNode delta = evt.get("delta");
            if (delta == null) {
                return;
            }
            String dtype = str(delta, "type");
            if ("text_delta".equals(dtype)) {
                String t = str(delta, "text");
                if (t != null && !t.isEmpty()) {
                    out.accept(event("text-delta", "text", t));
                }
            } else if ("input_json_delta".equals(dtype)) {
                StringBuilder sb = ctx.toolInputs.get(intVal(evt, "index"));
                if (sb != null) {
                    String frag = str(delta, "partial_json");
                    if (frag != null) {
                        sb.append(frag);
                    }
                }
            }
        } else if ("content_block_stop".equals(etype)) {
            int index = intVal(evt, "index");
            String name = ctx.toolNames.remove(index);
            StringBuilder sb = ctx.toolInputs.remove(index);
            if (QUESTION_TOOL.equals(name) && sb != null) {
                emitQuestion(sb.toString(), out);
            }
        }
    }

    /** Parse a buffered {@code AskUserQuestion} tool input into a {@code question} browser event. */
    private void emitQuestion(String json, Consumer<Map<String, Object>> out) {
        JsonNode input;
        try {
            input = mapper.readTree(json);
        } catch (Exception e) {
            return; // malformed / partial input — drop, like any other unparseable line
        }
        if (input == null || !input.isObject()) {
            return;
        }
        JsonNode questions = input.get("questions");
        if (questions == null || !questions.isArray() || questions.isEmpty()) {
            return;
        }
        List<Map<String, Object>> qlist = new ArrayList<>();
        for (JsonNode q : questions) {
            Map<String, Object> qm = new LinkedHashMap<>();
            qm.put("question", strOr(q, "question", ""));
            qm.put("header", strOr(q, "header", ""));
            JsonNode ms = q.get("multiSelect");
            qm.put("multiSelect", ms != null && ms.isBoolean() && ms.booleanValue());
            List<Map<String, Object>> opts = new ArrayList<>();
            JsonNode options = q.get("options");
            if (options != null && options.isArray()) {
                for (JsonNode o : options) {
                    Map<String, Object> om = new LinkedHashMap<>();
                    om.put("label", strOr(o, "label", ""));
                    om.put("description", strOr(o, "description", ""));
                    opts.add(om);
                }
            }
            qm.put("options", opts);
            qlist.add(qm);
        }
        if (qlist.isEmpty()) {
            return;
        }
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("type", "question");
        evt.put("questions", qlist);
        out.accept(evt);
    }

    /** Null-tolerant text field accessor (local copy; the package-private {@code service.Json} isn't visible here). */
    private static String str(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    /** {@link #str} with a non-null fallback, for fields that must serialize as a string. */
    private static String strOr(JsonNode node, String field, String def) {
        String v = str(node, field);
        return v != null ? v : def;
    }

    /** Null/type-tolerant integer field accessor; {@code -1} when absent or not an integer. */
    private static int intVal(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return -1;
        }
        JsonNode v = node.get(field);
        return v != null && v.isIntegralNumber() ? v.intValue() : -1;
    }

    // Package-private so the "ended at a question" branch is unit-testable.
    void finish(ResponseBodyEmitter emitter, StreamingProcessRunner.Handle handle, Integer code, RunningTurn turn) {
        try {
            if (turn.endedAtQuestion) {
                // `done` was already sent at the question and we killed the process — the resulting
                // non-zero exit is expected, so don't surface it as an error.
            } else if (code != null && code == 0) {
                send(emitter, event("done"));
            } else {
                String err = handle.stderr().strip();
                String msg = "claude exited with code " + code;
                if (!err.isEmpty()) {
                    msg += ": " + Format.truncate(err, 600);
                }
                send(emitter, event("error", "message", msg));
            }
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // already completed/closed
            }
        }
    }

    private void cancelInternal(String sessionId, RunningTurn turn) {
        if (running.remove(sessionId, turn)) {
            turn.finished = true;
            StreamingProcessRunner.Handle h = turn.handle;
            if (h != null) {
                StreamingProcessRunner.killTree(h.process());
            }
        }
    }

    /**
     * Atomically claim the single in-flight slot for {@code sessionId}, evicting a prior turn that is
     * no longer alive (process dead, or never cleaned up after a client disconnect). Returns false
     * only when a genuinely live turn already holds the slot. Package-private for direct unit testing.
     */
    boolean reserve(String sessionId, RunningTurn turn) {
        RunningTurn holder = running.compute(sessionId, (id, prev) ->
                (prev != null && prev.isAlive()) ? prev : turn);
        return holder == turn;
    }

    /** The alive in-browser turns as {@code WORKING} live-sessions, for the live snapshot/badges. */
    @Override
    public List<LiveSession> runningChatSessions() {
        List<LiveSession> out = new ArrayList<>();
        running.forEach((sid, turn) -> {
            if (turn.isAlive()) {
                out.add(new LiveSession(sid, turn.cwd, LiveStatus.WORKING));
            }
        });
        return out;
    }


    // package-private: the permission-mode resolution (security-sensitive) is unit-tested directly.
    String resolveMode(String override) {
        return PermissionModes.resolve(props.getChat(), override);
    }

    private void send(ResponseBodyEmitter emitter, Map<String, Object> evt) {
        ChatEvents.send(emitter, mapper, evt);
    }

    private static Map<String, Object> event(String type, Object... kv) {
        return ChatEvents.event(type, kv);
    }

    /**
     * Per-turn parse state: reassembles each streamed {@code tool_use} input from its
     * {@code input_json_delta} fragments, keyed by content-block index. One instance per turn (a
     * turn is single-threaded over its stdout reader), so plain maps are fine. Package-private so the
     * translation contract is unit-testable.
     */
    static final class TurnParse {
        final Map<Integer, String> toolNames = new HashMap<>();
        final Map<Integer, StringBuilder> toolInputs = new HashMap<>();
    }

    /** Package-private (not private) so {@link ClaudeChatService}'s slot logic is unit-testable. */
    static final class RunningTurn {
        final ResponseBodyEmitter emitter;
        final String cwd;
        volatile StreamingProcessRunner.Handle handle;
        volatile boolean finished;
        /** Set once the turn was wrapped up at an interactive question (process killed, {@code done} sent). */
        volatile boolean endedAtQuestion;

        RunningTurn(ResponseBodyEmitter emitter, String cwd) {
            this.emitter = emitter;
            this.cwd = cwd;
        }

        /** A turn still holds its slot unless it finished or its process has exited. */
        boolean isAlive() {
            if (finished) {
                return false;
            }
            StreamingProcessRunner.Handle h = handle;
            return h == null || h.process().isAlive(); // handle == null: still starting up
        }
    }
}
