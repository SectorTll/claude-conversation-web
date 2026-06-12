package ee.doniss.claudeweb.service.chat;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.LiveSession;
import ee.doniss.claudeweb.domain.LiveStatus;
import ee.doniss.claudeweb.service.chat.CliChatEngine.RunningTurn;
import ee.doniss.claudeweb.service.chat.CliChatEngine.TurnParse;
import ee.doniss.claudeweb.support.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the two pure, security/contract-sensitive pieces of the chat service: the exact
 * {@code claude} argv it builds, and how it resolves the permission mode (which governs what the
 * headless turn is allowed to do). Collaborators aren't touched by these methods, so they're null.
 */
class CliChatEngineTest {

    private CliChatEngine service(ClaudeProperties props) {
        return new CliChatEngine(props, null, null, null, null);
    }

    private CliChatEngine service() {
        return service(new ClaudeProperties());
    }

    // ----------------------------------------------------------------- attachments (no image channel)

    @Test
    void imageAttachmentsAreRejectedBeforeAnyProcessSpawns() {
        var attachments = List.of(new ee.doniss.claudeweb.web.dto.ChatRequest.Attachment("image/png", "aWJt"));
        // Collaborators are null — reaching any of them would NPE, so the 400 proves the guard fires first.
        assertThrows(BadRequestException.class,
                () -> service().sendToSession("p", "s", "hi", null, "", attachments));
        assertThrows(BadRequestException.class,
                () -> service().createSessionAndSend("p", "hi", null, "", attachments));
    }

    // ----------------------------------------------------------------- buildCommand (CLI contract)

    @Test
    void newSessionUsesSessionIdFlag() {
        List<String> cmd = service().buildCommand("sess-1", "plan", "", false);
        assertEquals(List.of("claude", "-p", "--output-format", "stream-json",
                "--include-partial-messages", "--verbose", "--permission-mode", "plan",
                "--session-id", "sess-1"), cmd);
    }

    @Test
    void resumeUsesResumeFlag() {
        List<String> cmd = service().buildCommand("sess-1", "acceptEdits", "", true);
        assertEquals(List.of("claude", "-p", "--output-format", "stream-json",
                "--include-partial-messages", "--verbose", "--permission-mode", "acceptEdits",
                "--resume", "sess-1"), cmd);
    }

    @Test
    void modelAliasIsPassedThroughAndBlankIsOmitted() {
        List<String> cmd = service().buildCommand("s", "plan", "haiku", false);
        int at = cmd.indexOf("--model");
        assertTrue(at > 0, "--model present: " + cmd);
        assertEquals("haiku", cmd.get(at + 1));

        assertFalse(service().buildCommand("s", "plan", "", false).contains("--model"),
                "blank model = the user's default, no flag");
    }

    @Test
    void honorsConfiguredExecutable() {
        ClaudeProperties props = new ClaudeProperties();
        props.getChat().setExecutable("/opt/claude/bin/claude");
        assertEquals("/opt/claude/bin/claude", service(props).buildCommand("s", "plan", "", false).get(0));
    }

    // ----------------------------------------------------------------- model gate

    @Test
    void modelOverrideIsValidated() {
        assertEquals("haiku", ChatModels.resolve(" haiku "));
        assertEquals("fable", ChatModels.resolve("fable"));
        assertEquals("", ChatModels.resolve(null));
        assertEquals("", ChatModels.resolve("  "));
        assertThrows(BadRequestException.class, () -> ChatModels.resolve("gpt-4o; rm -rf /"));
    }

    // ----------------------------------------------------------------- resolveMode (permission gate)

    @Test
    void nullOrBlankOverrideUsesServerDefault() {
        ClaudeProperties props = new ClaudeProperties(); // default permission-mode = plan
        assertEquals("plan", service(props).resolveMode(null));
        assertEquals("plan", service(props).resolveMode("   "));
    }

    @Test
    void validOverrideIsAcceptedAndTrimmed() {
        assertEquals("bypassPermissions", service().resolveMode("bypassPermissions"));
        assertEquals("acceptEdits", service().resolveMode("  acceptEdits  "));
    }

    @Test
    void invalidOverrideIsRejected() {
        assertThrows(BadRequestException.class, () -> service().resolveMode("rm-rf-everything"));
    }

    @Test
    void overrideIgnoredWhenPerRequestModeDisabled() {
        ClaudeProperties props = new ClaudeProperties();
        props.getChat().setPermissionMode("plan");
        props.getChat().setAllowPerRequestPermissionMode(false);
        // Even a valid, more-powerful override must fall back to the server default.
        assertEquals("plan", service(props).resolveMode("bypassPermissions"));
    }

    // ----------------------------------------------------------------- reserve (self-healing slot)

    @Test
    void reserveEvictsStaleTurnButKeepsLiveOne() {
        CliChatEngine s = service();
        RunningTurn first = new RunningTurn(null, "C:/x"); // handle == null -> treated as alive (starting)
        assertTrue(s.reserve("sid", first), "free slot is claimed");
        assertFalse(s.reserve("sid", new RunningTurn(null, "C:/x")), "a live turn still holds the slot");

        first.finished = true; // turn finished / its process exited
        assertTrue(s.reserve("sid", new RunningTurn(null, "C:/x")),
                "a stale (finished/dead) entry is evicted so a new turn can start");
    }

    @Test
    void cancelMarksFinishedAndFreesSlot() {
        CliChatEngine s = service();
        s.reserve("sid", new RunningTurn(null, "C:/x"));
        assertTrue(s.cancel("sid"), "cancel reports it stopped a running turn");
        assertTrue(s.reserve("sid", new RunningTurn(null, "C:/x")), "the slot is free after cancel");
        assertFalse(s.cancel("absent"), "cancel on an unknown session is a no-op");
    }

    // ----------------------------------------------------------------- runningChatSessions (live badge)

    @Test
    void runningChatSessionsReportsAliveTurnsAsWorking() {
        CliChatEngine s = service();
        s.reserve("sess-alive", new RunningTurn(null, "C:/work"));
        RunningTurn done = new RunningTurn(null, "C:/done");
        s.reserve("sess-done", done);
        done.finished = true; // finished turns are not "working"

        List<LiveSession> live = s.runningChatSessions();
        assertEquals(1, live.size(), "only alive turns are reported");
        assertEquals("sess-alive", live.get(0).sessionId());
        assertEquals(LiveStatus.WORKING, live.get(0).status());
        assertEquals("C:/work", live.get(0).cwd());
    }

    // ----------------------------------------------------------------- parseLine (event translation)

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Feed CLI stream-json lines through one turn's parser and collect the simplified browser events. */
    private List<Map<String, Object>> translate(String... lines) {
        CliChatEngine s = new CliChatEngine(new ClaudeProperties(), null, null, MAPPER, null);
        TurnParse ctx = new TurnParse();
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : lines) {
            s.parseLine(line, ctx, events::add);
        }
        return events;
    }

    private static String streamEvent(Map<String, Object> event) {
        return MAPPER.writeValueAsString(Map.of("type", "stream_event", "event", event));
    }

    private static String toolStart(int index, String name) {
        return streamEvent(Map.of("type", "content_block_start", "index", index,
                "content_block", Map.of("type", "tool_use", "name", name, "input", Map.of())));
    }

    private static String inputDelta(int index, String partialJson) {
        return streamEvent(Map.of("type", "content_block_delta", "index", index,
                "delta", Map.of("type", "input_json_delta", "partial_json", partialJson)));
    }

    private static String textDelta(int index, String text) {
        return streamEvent(Map.of("type", "content_block_delta", "index", index,
                "delta", Map.of("type", "text_delta", "text", text)));
    }

    private static String blockStop(int index) {
        return streamEvent(Map.of("type", "content_block_stop", "index", index));
    }

    @Test
    void askUserQuestionIsReassembledIntoQuestionEventAndSuppressesGenericTool() {
        // Input arrives across several input_json_delta fragments — the parser must reassemble them.
        String input = "{\"questions\":[{\"question\":\"Pick one\",\"header\":\"Choice\",\"multiSelect\":false,"
                + "\"options\":[{\"label\":\"A\",\"description\":\"first\"},"
                + "{\"label\":\"B\",\"description\":\"second\"}]}]}";
        int cut = input.length() / 2;
        List<Map<String, Object>> events = translate(
                toolStart(0, "AskUserQuestion"),
                inputDelta(0, input.substring(0, cut)),
                inputDelta(0, input.substring(cut)),
                blockStop(0));

        assertTrue(events.stream().noneMatch(e -> "tool".equals(e.get("type"))),
                "AskUserQuestion must not surface as an (empty) generic tool block");

        Map<String, Object> q = events.stream()
                .filter(e -> "question".equals(e.get("type")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) q.get("questions");
        assertEquals(1, questions.size());
        Map<String, Object> q0 = questions.get(0);
        assertEquals("Pick one", q0.get("question"));
        assertEquals("Choice", q0.get("header"));
        assertEquals(false, q0.get("multiSelect"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> opts = (List<Map<String, Object>>) q0.get("options");
        assertEquals(2, opts.size());
        assertEquals("A", opts.get(0).get("label"));
        assertEquals("first", opts.get(0).get("description"));
        assertEquals("B", opts.get(1).get("label"));
    }

    @Test
    void normalToolStillEmitsAToolEvent() {
        List<Map<String, Object>> events = translate(
                toolStart(0, "Read"),
                inputDelta(0, "{\"file\":\"x\"}"),
                blockStop(0));
        assertEquals(1, events.size());
        assertEquals("tool", events.get(0).get("type"));
        assertEquals("Read", events.get(0).get("title"));
    }

    @Test
    void textDeltaBecomesATextDeltaEvent() {
        List<Map<String, Object>> events = translate(textDelta(0, "hello"));
        assertEquals(1, events.size());
        assertEquals("text-delta", events.get(0).get("type"));
        assertEquals("hello", events.get(0).get("text"));
    }

    // ----------------------------------------------------------------- endAtQuestion / finish (lifecycle)

    /** Records what the service writes to the emitter (real Handler wiring is package-private to MVC). */
    private static final class CapturingEmitter extends ResponseBodyEmitter {
        final List<String> sent = new ArrayList<>();
        boolean completed;

        @Override
        public void send(Object object, MediaType mediaType) {
            sent.add(String.valueOf(object));
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    private CliChatEngine lifecycleService() {
        return new CliChatEngine(new ClaudeProperties(), null, null, MAPPER, null);
    }

    @Test
    void endAtQuestionEmitsDoneAndMarksTurnEnded() {
        CapturingEmitter emitter = new CapturingEmitter();
        RunningTurn turn = new RunningTurn(emitter, "C:/x"); // handle == null -> no real process to kill
        lifecycleService().endAtQuestion(emitter, turn);

        assertTrue(turn.endedAtQuestion, "the turn is marked as ended at the question");
        assertEquals(1, emitter.sent.size(), "exactly one event is sent: " + emitter.sent);
        assertTrue(emitter.sent.get(0).contains("\"type\":\"done\""),
                "a single done event is emitted: " + emitter.sent);
        assertFalse(emitter.completed, "completion is left to finish() once the killed process exits");
    }

    @Test
    void finishSkipsErrorAfterEndingAtQuestion() {
        CapturingEmitter emitter = new CapturingEmitter();
        RunningTurn turn = new RunningTurn(emitter, "C:/x");
        turn.endedAtQuestion = true;
        // A forcibly-killed process exits non-zero (e.g. 137) — finish must NOT surface that as an error
        // (done was already sent at the question). handle is unused on this branch, so null is fine.
        lifecycleService().finish(emitter, null, 137, turn);

        assertTrue(emitter.sent.isEmpty(), "no further event after the question's done: " + emitter.sent);
        assertTrue(emitter.completed, "the emitter is completed");
    }
}
