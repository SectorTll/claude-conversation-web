package ee.doniss.claudeweb.service;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.LiveSession;
import ee.doniss.claudeweb.domain.LiveStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveStatusServiceTest {

    @TempDir
    Path home;

    private LiveStatusService live;
    private Path sessionsRoot;

    @BeforeEach
    void setUp() {
        ClaudeProperties props = new ClaudeProperties();
        props.setHome(home);
        live = new LiveStatusService(props, JsonMapper.builder().build());
        sessionsRoot = home.resolve("sessions");
    }

    private void writeRegistry(String pidFile, String sessionId, String status, long pid) throws IOException {
        String json = "{\"sessionId\":\"" + sessionId + "\",\"cwd\":\"C:/x\",\"status\":\"" + status
                + "\",\"pid\":" + pid + "}";
        writeRaw(pidFile, json);
    }

    /** Registry entry with NO status field (e.g. the claude-desktop app). */
    private void writeRegistryNoStatus(String pidFile, String sessionId, long pid) throws IOException {
        writeRaw(pidFile, "{\"sessionId\":\"" + sessionId + "\",\"cwd\":\"C:/x\",\"pid\":" + pid + "}");
    }

    private void writeRaw(String pidFile, String json) throws IOException {
        Files.createDirectories(sessionsRoot);
        Files.writeString(sessionsRoot.resolve(pidFile + ".json"), json, StandardCharsets.UTF_8);
    }

    @Test
    void aliveProcessTrustedDeadDropped() throws IOException {
        long alivePid = ProcessHandle.current().pid();
        writeRegistry("alive", "sess-idle", "idle", alivePid);
        writeRegistry("busy", "sess-busy", "busy", alivePid);
        writeRegistry("stale", "sess-stale", "idle", 2_000_000_000L); // unlikely to exist -> dropped

        List<LiveSession> sessions = live.getLiveSessions();
        Map<String, LiveStatus> byId = sessions.stream()
                .collect(java.util.stream.Collectors.toMap(LiveSession::sessionId, LiveSession::status));

        assertEquals(2, sessions.size(), "stale (dead pid) entry must be dropped");
        assertEquals(LiveStatus.IDLE, byId.get("sess-idle"),
                "idle -> IDLE (an open shell, NOT the needs-a-decision WAITING)");
        assertEquals(LiveStatus.WORKING, byId.get("sess-busy"), "busy -> working");
        assertTrue(!byId.containsKey("sess-stale"));
    }

    @Test
    void missingOrUnknownStatusHidden() throws IOException {
        long alivePid = ProcessHandle.current().pid();
        writeRegistryNoStatus("nostatus", "sess-nostatus", alivePid); // claude-desktop style
        writeRegistry("unknown", "sess-unknown", "starting", alivePid); // unrecognized value
        writeRegistry("idle", "sess-idle", "idle", alivePid); // control: still shown

        Map<String, LiveStatus> byId = live.getLiveSessions().stream()
                .collect(java.util.stream.Collectors.toMap(LiveSession::sessionId, LiveSession::status));

        assertEquals(1, byId.size(), "only the busy/idle entry produces a badge");
        assertEquals(LiveStatus.IDLE, byId.get("sess-idle"));
        assertTrue(!byId.containsKey("sess-nostatus"), "no status field -> hidden");
        assertTrue(!byId.containsKey("sess-unknown"), "unknown status -> hidden");
    }

    @Test
    void duplicateSessionIdWorkingWins() throws IOException {
        long alivePid = ProcessHandle.current().pid();
        // Same sessionId reported by two alive processes: an idle CLI shell + a busy headless turn.
        writeRegistry("shell", "sess-dup", "idle", alivePid);
        writeRegistry("headless", "sess-dup", "busy", alivePid);

        List<LiveSession> sessions = live.getLiveSessions();

        assertEquals(1, sessions.size(), "duplicate sessionId collapses to one entry");
        assertEquals("sess-dup", sessions.get(0).sessionId());
        assertEquals(LiveStatus.WORKING, sessions.get(0).status(), "a busy process wins over idle");
    }

    @Test
    void noRegistryDirReturnsEmpty() {
        assertTrue(live.getLiveSessions().isEmpty());
    }
}
