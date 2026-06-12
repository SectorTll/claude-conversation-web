package ee.doniss.claudeweb.service.schedule.server;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.ScheduleKind;
import ee.doniss.claudeweb.domain.ServerScheduledTask;
import ee.doniss.claudeweb.domain.ServerTaskSpec;
import ee.doniss.claudeweb.os.StreamingProcessRunner;
import ee.doniss.claudeweb.support.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerScheduleServiceTest {

    @TempDir
    Path store;

    private ClaudeProperties props;
    private ServerScheduleService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        props = new ClaudeProperties();
        props.getServerSchedule().setStore(store);
        ServerTaskStore taskStore = new ServerTaskStore(props, mapper);
        ServerTaskRunner runner = new ServerTaskRunner(props, new StreamingProcessRunner(), taskStore, Runnable::run);
        service = new ServerScheduleService(props, taskStore, runner);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private ServerTaskSpec dailySpec() {
        ServerTaskSpec s = new ServerTaskSpec();
        s.setName("My Task!"); // '!' stripped by safeName -> "My Task"
        s.setPrompt("Сделай отчёт");
        s.setWorkingDir(System.getProperty("user.home"));
        s.setAllowedTools("Bash Read");
        s.setPermissionMode(""); // blank -> server default
        s.setKind(ScheduleKind.DAILY);
        s.setAt(LocalDateTime.of(2026, 6, 9, 9, 0));
        s.setTimeLimitHours(2);
        s.setEnabled(true);
        return s;
    }

    @Test
    void createPersistsAndReadsBack() {
        ServerScheduledTask info = service.create(dailySpec());
        assertEquals("My Task", info.getName());
        assertTrue(Files.isRegularFile(store.resolve("My Task").resolve("task.json")));

        List<ServerScheduledTask> tasks = service.list();
        assertEquals(1, tasks.size());
        ServerScheduledTask t = tasks.get(0);
        assertEquals("My Task", t.getName());
        assertEquals("Daily at 09:00", t.getScheduleText());
        assertEquals("Сделай отчёт", t.getPrompt()); // Cyrillic survives the JSON round-trip
        assertEquals("plan", t.getPermissionMode()); // server default applied
        assertEquals("idle", t.getState());
    }

    @Test
    void disabledTaskReportsDisabledStateAndNoNextRun() {
        ServerTaskSpec spec = dailySpec();
        spec.setEnabled(false);
        service.create(spec);

        ServerScheduledTask t = service.list().get(0);
        assertEquals("disabled", t.getState());
        assertEquals("", t.getNextRun());
    }

    @Test
    void deleteRemovesFolderAndTrigger() {
        service.create(dailySpec());
        service.delete("My Task");
        assertFalse(Files.exists(store.resolve("My Task")));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void cronForDailyAndWeekly() {
        ServerScheduledTask daily = new ServerScheduledTask();
        daily.setKind(ScheduleKind.DAILY);
        daily.setAt(LocalDateTime.of(2026, 6, 9, 9, 5));
        assertEquals("0 5 9 * * *", ServerScheduleService.cronFor(daily));

        ServerScheduledTask weekly = new ServerScheduledTask();
        weekly.setKind(ScheduleKind.WEEKLY);
        weekly.setAt(LocalDateTime.of(2026, 6, 9, 9, 0));
        weekly.setDays(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY));
        assertEquals("0 0 9 * * MON,WED,FRI", ServerScheduleService.cronFor(weekly));
    }

    @Test
    void resolveModeValidatesAndDefaults() {
        assertEquals("plan", service.resolveMode(""));
        assertEquals("plan", service.resolveMode(null));
        assertEquals("acceptEdits", service.resolveMode("acceptEdits"));
        assertThrows(BadRequestException.class, () -> service.resolveMode("nonsense"));
    }

    @Test
    void resolveModeIgnoresOverrideWhenPerRequestDisabled() {
        props.getServerSchedule().setAllowPerRequestPermissionMode(false);
        props.getServerSchedule().setPermissionMode("plan");
        assertEquals("plan", service.resolveMode("bypassPermissions")); // override ignored
    }
}
