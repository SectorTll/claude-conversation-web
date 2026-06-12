package ee.doniss.claudeweb.service.schedule.server;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.ServerScheduledTask;
import ee.doniss.claudeweb.os.StreamingProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTaskRunnerTest {

    @TempDir
    Path store;

    private ClaudeProperties props;
    private ServerTaskStore taskStore;
    private ServerTaskRunner runner;

    @BeforeEach
    void setUp() {
        props = new ClaudeProperties();
        props.getServerSchedule().setStore(store);
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        taskStore = new ServerTaskStore(props, mapper);
        runner = new ServerTaskRunner(props, new StreamingProcessRunner(), taskStore, Runnable::run);
    }

    private ServerScheduledTask task(String mode, String tools) {
        ServerScheduledTask t = new ServerScheduledTask();
        t.setName("T");
        t.setPrompt("hi");
        t.setPermissionMode(mode);
        t.setAllowedTools(tools);
        t.setScheduleText("Daily at 09:00");
        return t;
    }

    @Test
    void buildCommandHasFlagsModeAndTools() {
        List<String> cmd = runner.buildCommand(task("plan", "Bash Read"));
        assertEquals("claude", cmd.get(0));
        assertTrue(cmd.contains("-p"));
        int pm = cmd.indexOf("--permission-mode");
        assertEquals("plan", cmd.get(pm + 1));
        int at = cmd.indexOf("--allowedTools");
        assertTrue(at > 0);
        assertEquals("Bash", cmd.get(at + 1));
        assertEquals("Read", cmd.get(at + 2));
    }

    @Test
    void buildCommandBlankModeUsesServerDefaultAndOmitsEmptyTools() {
        props.getServerSchedule().setPermissionMode("acceptEdits");
        List<String> cmd = runner.buildCommand(task("", ""));
        int pm = cmd.indexOf("--permission-mode");
        assertEquals("acceptEdits", cmd.get(pm + 1));
        assertFalse(cmd.contains("--allowedTools"));
    }

    @Test
    void buildCommandAddsMaxTurnsOnlyWhenSet() {
        ServerScheduledTask capped = task("plan", "");
        capped.setMaxTurns(25);
        List<String> cmd = runner.buildCommand(capped);
        int mt = cmd.indexOf("--max-turns");
        assertTrue(mt > 0, "--max-turns present: " + cmd);
        assertEquals("25", cmd.get(mt + 1));

        assertFalse(runner.buildCommand(task("plan", "")).contains("--max-turns"),
                "0 = unlimited, no flag");
    }

    @Test
    void splitToolsSplitsOnSpaceAndComma() {
        assertEquals(List.of("Bash", "Read", "Edit"), ServerTaskRunner.splitTools("Bash, Read  Edit"));
        assertTrue(ServerTaskRunner.splitTools("   ").isEmpty());
        assertTrue(ServerTaskRunner.splitTools(null).isEmpty());
    }

    @Test
    void writeReportWritesLatestAndStamped() throws Exception {
        ServerScheduledTask t = task("plan", "");
        taskStore.write(t); // create the folder
        runner.writeReport(t, System.getProperty("user.home"), "hello world output", "", "ok", 0);

        Path latest = store.resolve("T").resolve("latest.md");
        assertTrue(Files.isRegularFile(latest));
        String md = Files.readString(latest);
        assertTrue(md.contains("hello world output"));
        assertTrue(md.contains("status: ok"));

        try (Stream<Path> s = Files.list(store.resolve("T"))) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("report-")));
        }
    }
}
