package ee.doniss.claudeweb.service.schedule.server;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.ScheduleKind;
import ee.doniss.claudeweb.domain.ServerScheduledTask;
import ee.doniss.claudeweb.domain.ServerTaskSpec;
import ee.doniss.claudeweb.support.BadRequestException;
import ee.doniss.claudeweb.support.NotFoundException;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

/**
 * In-process scheduler — the "second approach". The always-on Spring server owns the schedule itself
 * via its own {@link ThreadPoolTaskScheduler} (NOT exposed as a {@code TaskScheduler} bean, so the
 * app's {@code @Scheduled} methods keep their default executor). A due trigger only <em>dispatches</em>
 * the task to {@link ServerTaskRunner#submit} (which runs it on the chat executor), so a fire never
 * occupies a scheduler thread for the whole run.
 *
 * <p>Works on every OS (unlike the Windows-only first approach). Each fire runs {@code claude -p}; the
 * CLI writes its canonical {@code .jsonl} so the run also shows up in the browse views.
 */
@Service
public class ServerScheduleService {

    private static final Set<String> ALLOWED_MODES =
            Set.of("plan", "acceptEdits", "bypassPermissions", "default", "dontAsk", "auto");

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MON_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter NEXT_OUT = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);

    private final ClaudeProperties props;
    private final ServerTaskStore store;
    private final ServerTaskRunner runner;
    private final ThreadPoolTaskScheduler scheduler;

    /** Live trigger per task name, so we can cancel/reschedule on update/enable/delete. */
    private final Map<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    public ServerScheduleService(ClaudeProperties props, ServerTaskStore store, ServerTaskRunner runner) {
        this.props = props;
        this.store = store;
        this.runner = runner;
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(Math.max(1, props.getServerSchedule().getSchedulerPoolSize()));
        s.setThreadNamePrefix("server-sched-");
        s.setDaemon(true);
        s.initialize();
        this.scheduler = s;
    }

    @PreDestroy
    public void shutdown() {
        scheduled.values().forEach(f -> f.cancel(false));
        scheduled.clear();
        scheduler.shutdown();
    }

    /** Re-arm all persisted tasks once the context is ready (and after a restart). */
    @EventListener(ApplicationReadyEvent.class)
    public void reload() {
        for (ServerScheduledTask t : store.readAll()) {
            try {
                register(t);
            } catch (RuntimeException ignore) {
                // one bad task must not stop the rest from arming
            }
        }
    }

    // ----------------------------------------------------------------- queries

    public Path folderOf(String name) {
        return store.folderOf(name);
    }

    public List<ServerScheduledTask> list() {
        List<ServerScheduledTask> tasks = store.readAll();
        for (ServerScheduledTask t : tasks) {
            t.setFolder(store.folderOf(t.getName()).toString());
            t.setScheduleText(describe(t));
            t.setState(!t.isEnabled() ? "disabled" : runner.isRunning(t.getName()) ? "running" : "idle");
            t.setNextRun(computeNextRun(t));
        }
        return tasks;
    }

    public String latestReport(String name) {
        Path latest = store.folderOf(name).resolve("latest.md");
        try {
            return Files.isRegularFile(latest) ? Files.readString(latest, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    // ----------------------------------------------------------------- mutations

    public ServerScheduledTask create(ServerTaskSpec spec) {
        if (spec.getName() == null || spec.getName().isBlank()) {
            throw new BadRequestException("Task name is required.");
        }
        if (spec.getPrompt() == null || spec.getPrompt().isBlank()) {
            throw new BadRequestException("Prompt is required.");
        }
        String safe = ServerTaskStore.safeName(spec.getName());
        String mode = resolveMode(spec.getPermissionMode());

        ServerScheduledTask t = new ServerScheduledTask();
        t.setName(safe);
        t.setPrompt(spec.getPrompt());
        t.setWorkingDir(spec.getWorkingDir() == null ? "" : spec.getWorkingDir().trim());
        t.setAllowedTools(spec.getAllowedTools() == null ? "" : spec.getAllowedTools().trim());
        t.setPermissionMode(mode);
        t.setKind(spec.getKind() == null ? ScheduleKind.DAILY : spec.getKind());
        t.setAt(spec.getAt() == null ? LocalDateTime.now().plusHours(1) : spec.getAt());
        t.setDays(t.getKind() == ScheduleKind.WEEKLY && spec.getDays() != null ? spec.getDays() : List.of());
        t.setTimeLimitHours(Math.max(1, spec.getTimeLimitHours()));
        t.setMaxTurns(Math.max(0, spec.getMaxTurns())); // 0 = unlimited
        t.setEnabled(spec.isEnabled());
        t.setScheduleText(describe(t));

        store.write(t);
        register(t); // cancels any prior trigger of the same name, then arms if enabled

        t.setFolder(store.folderOf(safe).toString());
        t.setState(t.isEnabled() ? "idle" : "disabled");
        t.setNextRun(computeNextRun(t));
        return t;
    }

    public void setEnabled(String name, boolean enabled) {
        ServerScheduledTask t = store.read(name)
                .orElseThrow(() -> new NotFoundException("no such task: " + name));
        t.setEnabled(enabled);
        store.write(t);
        register(t); // re-arms when enabled, just cancels when disabled
    }

    public void runNow(String name) {
        ServerScheduledTask t = store.read(name)
                .orElseThrow(() -> new NotFoundException("no such task: " + name));
        runner.submit(t); // manual run fires regardless of enabled
    }

    public void delete(String name) {
        cancel(ServerTaskStore.safeName(name));
        store.delete(name);
    }

    // ----------------------------------------------------------------- scheduling

    private void register(ServerScheduledTask t) {
        cancel(t.getName());
        if (!t.isEnabled()) {
            return;
        }
        Runnable job = () -> runner.submit(store.read(t.getName()).orElse(t));
        ScheduledFuture<?> future;
        if (t.getKind() == ScheduleKind.ONCE) {
            Instant when = t.getAt().atZone(ZoneId.systemDefault()).toInstant();
            if (!when.isAfter(Instant.now())) {
                return; // a one-shot already in the past never arms
            }
            future = scheduler.schedule(job, when);
        } else {
            future = scheduler.schedule(job, new CronTrigger(cronFor(t)));
        }
        if (future != null) {
            scheduled.put(t.getName(), future);
        }
    }

    private void cancel(String name) {
        ScheduledFuture<?> f = scheduled.remove(name);
        if (f != null) {
            f.cancel(false);
        }
    }

    // package-private: the permission-mode gate (security-sensitive) is unit-tested directly.
    String resolveMode(String override) {
        ClaudeProperties.ServerSchedule cfg = props.getServerSchedule();
        if (override != null && !override.isBlank()) {
            if (!cfg.isAllowPerRequestPermissionMode()) {
                return cfg.getPermissionMode(); // per-task override disabled — use the server default
            }
            String m = override.strip();
            if (!ALLOWED_MODES.contains(m)) {
                throw new BadRequestException("invalid permissionMode: " + m);
            }
            return m;
        }
        return cfg.getPermissionMode();
    }

    /** 6-field Spring cron for a daily/weekly task. Package-private: unit-tested directly. */
    static String cronFor(ServerScheduledTask t) {
        int m = t.getAt().getMinute();
        int h = t.getAt().getHour();
        if (t.getKind() == ScheduleKind.WEEKLY) {
            List<DayOfWeek> days = t.getDays();
            String dow = (days == null || days.isEmpty())
                    ? "*"
                    : days.stream().map(d -> d.name().substring(0, 3)).collect(Collectors.joining(","));
            return "0 " + m + " " + h + " * * " + dow;
        }
        return "0 " + m + " " + h + " * * *"; // daily
    }

    private String computeNextRun(ServerScheduledTask t) {
        if (!t.isEnabled()) {
            return "";
        }
        if (t.getKind() == ScheduleKind.ONCE) {
            return t.getAt().isAfter(LocalDateTime.now()) ? t.getAt().format(NEXT_OUT) : "";
        }
        try {
            LocalDateTime next = CronExpression.parse(cronFor(t)).next(LocalDateTime.now());
            return next != null ? next.format(NEXT_OUT) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String describe(ServerScheduledTask t) {
        return switch (t.getKind()) {
            case ONCE -> "Once on " + t.getAt().format(MON_DAY_YEAR) + " at " + t.getAt().format(HHMM);
            case WEEKLY -> "Weekly (" + t.getDays().stream()
                    .map(d -> d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                    .collect(Collectors.joining("/")) + ") at " + t.getAt().format(HHMM);
            default -> "Daily at " + t.getAt().format(HHMM);
        };
    }
}
