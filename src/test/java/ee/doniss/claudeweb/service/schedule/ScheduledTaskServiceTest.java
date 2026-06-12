package ee.doniss.claudeweb.service.schedule;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.ScheduleKind;
import ee.doniss.claudeweb.domain.ScheduledTaskInfo;
import ee.doniss.claudeweb.domain.TaskSpec;
import ee.doniss.claudeweb.os.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskServiceTest {

    /** Captures every powershell invocation and pretends registration succeeded. */
    static final class CapturingRunner extends ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();

        @Override
        public Result run(List<String> command) {
            commands.add(command);
            return new Result(true, "OK");
        }

        List<String> last() {
            return commands.get(commands.size() - 1);
        }
    }

    @TempDir
    Path tasksRoot;

    private CapturingRunner runner;
    private ScheduledTaskService service;

    @BeforeEach
    void setUp() {
        ClaudeProperties props = new ClaudeProperties();
        props.setTasksRoot(tasksRoot);
        runner = new CapturingRunner();
        service = new ScheduledTaskService(props, runner, JsonMapper.builder().build());
    }

    private TaskSpec dailySpec() {
        TaskSpec spec = new TaskSpec();
        spec.setName("My Task!"); // '!' is stripped by safeName -> "My Task"
        spec.setPrompt("Сделай отчёт");
        spec.setWorkingDir(System.getProperty("user.home"));
        spec.setAllowedTools("Bash Read");
        spec.setKind(ScheduleKind.DAILY);
        spec.setAt(LocalDateTime.of(2026, 6, 9, 9, 0));
        spec.setTimeLimitHours(2);
        spec.setHidden(true);
        spec.setOpenReport(false);
        return spec;
    }

    @Test
    void createGeneratesAllScriptsAndRegisters() throws Exception {
        ScheduledTaskInfo info = service.create(dailySpec());
        assertEquals("My Task", info.getName());

        Path folder = tasksRoot.resolve("My Task");
        for (String f : List.of("prompt.txt", "run.ps1", "run-hidden.vbs", "meta.json", "register.ps1")) {
            assertTrue(Files.isRegularFile(folder.resolve(f)), "missing " + f);
        }

        // prompt stored verbatim, UTF-8 (Cyrillic preserved)
        assertEquals("Сделай отчёт", Files.readString(folder.resolve("prompt.txt"), StandardCharsets.UTF_8));

        String runnerScript = Files.readString(folder.resolve("run.ps1"), StandardCharsets.UTF_8);
        assertTrue(runnerScript.contains("[Console]::OutputEncoding"), "forces UTF-8 console");
        assertTrue(runnerScript.contains("$allowed = @('Bash', 'Read')"), "allowed tools array");
        assertTrue(runnerScript.contains("--allowedTools @allowed"), "passes the allowlist");
        assertTrue(runnerScript.contains("TrimStart([char]0xFEFF)"), "strips BOM");

        String register = Files.readString(folder.resolve("register.ps1"), StandardCharsets.UTF_8);
        assertTrue(register.contains("New-ScheduledTaskTrigger -Daily -At '09:00'"), register);
        assertTrue(register.contains("Register-ScheduledTask"), register);
        assertTrue(register.contains("-TaskPath '\\Claude\\'"), register);
        assertTrue(register.contains("wscript.exe"), "hidden launcher");

        String vbs = Files.readString(folder.resolve("run-hidden.vbs"), StandardCharsets.UTF_8);
        assertTrue(vbs.contains("WScript.Shell"));
        assertTrue(vbs.contains("run.ps1"));

        // registration ran: powershell -File <register.ps1>
        List<String> cmd = runner0Command();
        assertTrue(cmd.contains("powershell.exe"));
        assertTrue(cmd.contains("-File"));
        assertTrue(cmd.stream().anyMatch(a -> a.endsWith("register.ps1")));
    }

    private List<String> runner0Command() {
        return runner.commands.get(0);
    }

    @Test
    void weeklyTriggerListsDays() throws Exception {
        TaskSpec spec = dailySpec();
        spec.setKind(ScheduleKind.WEEKLY);
        spec.setDays(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY));
        service.create(spec);

        String register = Files.readString(tasksRoot.resolve("My Task").resolve("register.ps1"),
                StandardCharsets.UTF_8);
        assertTrue(register.contains("-Weekly -DaysOfWeek Monday,Wednesday"), register);
        assertEquals("Weekly (Mon/Wed) at 09:00", ScheduledTaskService.describeSchedule(spec));
    }

    @Test
    void runNowSendsEncodedStartCommand() {
        service.runNow("My Task");
        List<String> cmd = runner.last();
        int i = cmd.indexOf("-EncodedCommand");
        assertTrue(i >= 0 && i + 1 < cmd.size(), "has -EncodedCommand");
        String decoded = new String(Base64.getDecoder().decode(cmd.get(i + 1)), StandardCharsets.UTF_16LE);
        assertTrue(decoded.contains("Start-ScheduledTask"), decoded);
        assertTrue(decoded.contains("-TaskPath '\\Claude\\'"), decoded);
    }

    @Test
    void listReadsBackCreatedTask() {
        service.create(dailySpec());
        List<ScheduledTaskInfo> tasks = service.list();
        assertEquals(1, tasks.size());
        assertEquals("My Task", tasks.get(0).getName());
        assertEquals("Daily at 09:00", tasks.get(0).getScheduleText());
    }
}
