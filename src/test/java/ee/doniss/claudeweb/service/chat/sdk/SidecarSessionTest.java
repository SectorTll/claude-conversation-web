package ee.doniss.claudeweb.service.chat.sdk;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.os.TestProcesses;
import ee.doniss.claudeweb.os.TestProcesses.FakeProcess;
import ee.doniss.claudeweb.support.BadRequestException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the sdk engine's per-session core: sidecar→browser event translation, decision
 * routing (a decision must hit exactly its pending request), the auto-deny timer, and the activity
 * watchdog incl. its pause while a card waits on the user. The sidecar process is faked — its
 * captured stdin is asserted on instead.
 */
@Timeout(30)
class SidecarSessionTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "sidecar-test-timer");
                t.setDaemon(true);
                return t;
            });

    @AfterAll
    static void stopScheduler() {
        SCHEDULER.shutdownNow();
    }

    private static final class Fixture {
        final ClaudeProperties props = new ClaudeProperties();
        final FakeProcess process = new FakeProcess();
        final AtomicReference<String> readyId = new AtomicReference<>();
        final SidecarSession session;

        Fixture(boolean createdNew) {
            session = new SidecarSession(props, null, null, MAPPER, Runnable::run, SCHEDULER,
                    System::nanoTime, "requested-id", "C:/work", createdNew, readyId::set);
            // The handle is alive, so ensureProcess() short-circuits — no real process is spawned
            // and every protocol line lands in the fake's capture buffer.
            session.handle = TestProcesses.interactiveHandle(process);
        }
    }

    /**
     * A fixture whose process really goes through {@code ensureProcess}: the runner is faked to
     * hand out fresh capture-buffer processes, so process (re)launches can be asserted on.
     */
    private static final class SpawningFixture {
        final ClaudeProperties props = new ClaudeProperties();
        final java.util.List<FakeProcess> spawned = new java.util.ArrayList<>();
        final SidecarSession session;

        SpawningFixture() {
            var runner = new ee.doniss.claudeweb.os.StreamingProcessRunner() {
                @Override
                public InteractiveHandle startInteractive(List<String> command, String workingDir,
                                                          java.util.function.Consumer<String> onStdoutLine,
                                                          java.util.concurrent.Executor executor) {
                    FakeProcess p = new FakeProcess();
                    spawned.add(p);
                    return TestProcesses.interactiveHandle(p);
                }
            };
            var locator = new SidecarLocator(props) {
                @Override
                public java.nio.file.Path locate() {
                    return java.nio.file.Path.of("sidecar.mjs");
                }
            };
            session = new SidecarSession(props, runner, locator, MAPPER, Runnable::run, SCHEDULER,
                    System::nanoTime, "sid", "C:/work", false, id -> {
            });
        }
    }

    /** Polls until the condition holds (the timers run on a real scheduler). */
    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + "ms");
            }
            Thread.sleep(20);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static String line(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return MAPPER.writeValueAsString(m);
    }

    // ----------------------------------------------------------------- ready / re-key

    @Test
    void readyReportsAuthoritativeSessionIdAndRekeys() {
        Fixture f = new Fixture(true);
        f.session.onLine(line("type", "ready", "sessionId", "actual-id"));
        assertEquals("actual-id", f.readyId.get(), "the engine is told the authoritative id");
        assertEquals("actual-id", f.session.sessionId());
    }

    // ----------------------------------------------------------------- decision routing

    @Test
    void decideRoutesOnlyToItsPendingRequest() {
        Fixture f = new Fixture(false);
        f.session.onLine(line("type", "permission_request", "requestId", "r1", "toolName", "Bash"));

        assertThrows(BadRequestException.class, () -> f.session.decide("unknown", true, null, false),
                "an unknown requestId is rejected, not silently dropped");

        f.session.decide("r1", true, null, false);
        String stdin = f.process.stdinText();
        assertTrue(stdin.contains("\"permission_response\"") && stdin.contains("\"r1\"")
                        && stdin.contains("\"allow\""),
                "the allow is written to the sidecar: " + stdin);

        assertThrows(BadRequestException.class, () -> f.session.decide("r1", true, null, false),
                "a request can be decided only once");
    }

    @Test
    void alwaysAllowForwardsTheAlwaysFlag() {
        Fixture f = new Fixture(false);
        f.session.onLine(line("type", "permission_request", "requestId", "r1", "toolName", "Bash"));
        f.session.decide("r1", true, null, true);
        assertTrue(f.process.stdinText().contains("\"always\":true"),
                "always-allow reaches the sidecar so it can persist the suggested rules: "
                        + f.process.stdinText());
    }

    @Test
    void questionMustBeAnsweredThroughAnswerNotDecide() {
        Fixture f = new Fixture(false);
        f.session.onLine(line("type", "question", "requestId", "q1",
                "questions", List.of()));

        assertThrows(BadRequestException.class, () -> f.session.decide("q1", true, null, false),
                "a question is not decidable via the permission endpoint");

        f.session.answer("q1", List.of(List.of("Red")));
        String stdin = f.process.stdinText();
        assertTrue(stdin.contains("\"question_answer\"") && stdin.contains("\"Red\""),
                "the answer is written to the sidecar: " + stdin);
    }

    // ----------------------------------------------------------------- pending cards (reload/second tab)

    @Test
    void pendingCardsExposeUnansweredAsksOldestFirstAndShrinkOnDecision() {
        Fixture f = new Fixture(false);
        f.session.onLine(line("type", "permission_request", "requestId", "r1", "toolName", "Bash",
                "input", Map.of("command", "ls"), "suggestions", List.of()));
        f.session.onLine(line("type", "question", "requestId", "q1", "questions", List.of()));

        List<Map<String, Object>> cards = f.session.pendingCards();
        assertEquals(2, cards.size());
        assertEquals("permission", cards.get(0).get("type"));
        assertEquals("r1", cards.get(0).get("requestId"));
        assertEquals("Bash", cards.get(0).get("toolName"));
        assertEquals("question", cards.get(1).get("type"));

        f.session.decide("r1", true, null, false);
        assertEquals(1, f.session.pendingCards().size(), "a decided card is no longer pending");
        assertTrue(f.session.hasPendingAsk());
    }

    // ----------------------------------------------------------------- auto-deny timer

    @Test
    void unansweredPermissionIsAutoDeniedAfterTimeout() throws Exception {
        Fixture f = new Fixture(false);
        f.props.getChat().setPermissionTimeout(Duration.ofMillis(80));
        f.session.onLine(line("type", "permission_request", "requestId", "r1", "toolName", "Bash"));

        await(() -> f.process.stdinText().contains("timed out waiting for approval"), 5_000);
        assertThrows(BadRequestException.class, () -> f.session.decide("r1", true, null, false),
                "after auto-deny the request is no longer pending");
    }

    // ----------------------------------------------------------------- bypass-boundary relaunch

    @Test
    void switchingIntoBypassRelaunchesTheSidecar() {
        // The CLI can only bypass permissions when LAUNCHED with --dangerously-skip-permissions —
        // setPermissionMode(bypassPermissions) on a live process is refused. The session must
        // relaunch the sidecar (which resumes from disk) instead of keeping the warm one.
        SpawningFixture f = new SpawningFixture();
        f.session.startTurn("hi", "default", "");
        assertEquals(1, f.spawned.size());
        assertTrue(f.spawned.get(0).stdinText().contains("\"permissionMode\":\"default\""));
        f.session.onLine(line("type", "ready", "sessionId", "sid"));
        f.session.onLine(line("type", "turn_done"));

        f.session.startTurn("go full access", "bypassPermissions", "");
        assertEquals(2, f.spawned.size(), "crossing into bypassPermissions relaunches the sidecar");
        assertFalse(f.spawned.get(0).isAlive(), "the old sidecar is killed");
        String stdin = f.spawned.get(1).stdinText();
        assertTrue(stdin.contains("\"init\"") && stdin.contains("\"permissionMode\":\"bypassPermissions\"")
                        && stdin.contains("\"resume\":true"),
                "the fresh sidecar is launched in bypass and resumes the session: " + stdin);
        assertTrue(stdin.contains("\"user_turn\""), "the turn rides the fresh process: " + stdin);
    }

    @Test
    void switchingOutOfBypassRelaunchesTheSidecar() {
        // The reverse direction is restarted too: a process launched with the dangerous flag must
        // not be trusted to start prompting again just because the mode was switched back.
        SpawningFixture f = new SpawningFixture();
        f.session.startTurn("hi", "bypassPermissions", "");
        f.session.onLine(line("type", "ready", "sessionId", "sid"));
        f.session.onLine(line("type", "turn_done"));

        f.session.startTurn("back to safety", "plan", "");
        assertEquals(2, f.spawned.size(), "leaving bypassPermissions relaunches the sidecar");
        assertFalse(f.spawned.get(0).isAlive());
        assertTrue(f.spawned.get(1).stdinText().contains("\"permissionMode\":\"plan\""));
    }

    @Test
    void modeChangesWithinTheNonBypassFamilyKeepTheWarmProcess() {
        SpawningFixture f = new SpawningFixture();
        f.session.startTurn("hi", "default", "");
        f.session.onLine(line("type", "ready", "sessionId", "sid"));
        f.session.onLine(line("type", "turn_done"));

        f.session.startTurn("again", "acceptEdits", "");
        assertEquals(1, f.spawned.size(), "default -> acceptEdits is applied via setPermissionMode, no relaunch");
        assertTrue(f.spawned.get(0).stdinText().contains("\"permissionMode\":\"acceptEdits\""),
                "the new mode rides the user_turn line");
    }

    // ----------------------------------------------------------------- turn lifecycle + watchdog

    @Test
    void turnDoneCompletesTheTurn() {
        Fixture f = new Fixture(false);
        f.session.startTurn("hi", "plan", "");
        assertTrue(f.session.turnActive());
        assertTrue(f.process.stdinText().contains("\"user_turn\""), "the turn was written to the sidecar");

        f.session.onLine(line("type", "turn_done"));
        assertFalse(f.session.turnActive(), "turn_done finishes the streamed turn");
    }

    @Test
    void imageAttachmentsRideTheUserTurnLine() {
        Fixture f = new Fixture(false);
        f.session.startTurn("what is this?", "plan", "",
                List.of(new ee.doniss.claudeweb.web.dto.ChatRequest.Attachment("image/png", "aWJt")));
        String stdin = f.process.stdinText();
        assertTrue(stdin.contains("\"images\"") && stdin.contains("\"image/png\"") && stdin.contains("\"aWJt\""),
                "pasted images are written as the user_turn images list: " + stdin);
    }

    @Test
    void turnStaysBusyAfterTheBrowserStreamDies() {
        // A reloaded/closed tab completes the emitter, but the SIDECAR is still working the turn —
        // the slot must stay taken (409 for new sends) and the live badge must stay lit.
        Fixture f = new Fixture(false);
        var emitter = f.session.startTurn("hi", "plan", "");
        emitter.complete(); // simulates the client disconnecting
        assertTrue(f.session.turnActive(), "emitter death must not free a turn the sidecar still runs");

        f.session.onLine(line("type", "turn_done"));
        assertFalse(f.session.turnActive());
    }

    @Test
    void watchdogTimesOutAnIdleTurnButNotWhileACardIsPending() throws Exception {
        Fixture f = new Fixture(false);
        f.props.getChat().setTurnTimeout(Duration.ofMillis(200)); // watchdog period floors at 500ms
        f.session.startTurn("hi", "plan", "");

        // Pending ask: the watchdog must NOT fire even though no activity happens for >> timeout.
        f.session.onLine(line("type", "permission_request", "requestId", "r1", "toolName", "Bash"));
        Thread.sleep(1_300);
        assertTrue(f.session.turnActive(), "a turn waiting on a human is not a hung turn");

        // Resolve the ask; now the idle clock runs again and the watchdog interrupts the turn.
        f.session.decide("r1", false, "no", false);
        await(() -> !f.session.turnActive(), 5_000);
        assertTrue(f.process.stdinText().contains("\"interrupt\""),
                "the timed-out turn is interrupted in the sidecar: " + f.process.stdinText());
    }

    @Test
    void idleLongerThanIsFalseWhileTurnActiveOrAskPending() {
        Fixture f = new Fixture(false);
        assertTrue(f.session.idleLongerThan(-1), "fresh session with no turn is idle");

        f.session.startTurn("hi", "plan", "");
        assertFalse(f.session.idleLongerThan(-1), "an active turn is never idle");

        f.session.onLine(line("type", "turn_done"));
        f.session.onLine(line("type", "permission_request", "requestId", "r1", "toolName", "Bash"));
        assertFalse(f.session.idleLongerThan(-1), "a pending ask keeps the session alive");
    }
}
