package ee.doniss.claudeweb.service.schedule.server;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.ServerScheduledTask;
import ee.doniss.claudeweb.os.StreamingProcessRunner;
import ee.doniss.claudeweb.service.Format;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes one server-scheduled task: launches {@code claude -p} headlessly (reusing the chat
 * {@link StreamingProcessRunner} + executor), captures its plain-text output into a timestamped
 * {@code report-*.md} (+ {@code latest.md}), enforces the per-task time limit, and persists the
 * last-run state back into {@code task.json}.
 *
 * <p>SECURITY: headless mode can't prompt, so the resolved permission mode is final for the run; the
 * mode was validated and frozen into the task at create time (see {@code ServerScheduleService}).
 */
@Service
public class ServerTaskRunner {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter HEAD = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ClaudeProperties props;
    private final StreamingProcessRunner runner;
    private final ServerTaskStore store;
    private final Executor executor;

    /** Names of tasks with a run in flight — prevents overlapping fires of the same task (IgnoreNew). */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public ServerTaskRunner(ClaudeProperties props, StreamingProcessRunner runner, ServerTaskStore store,
                            @Qualifier("claudeChatExecutor") Executor executor) {
        this.props = props;
        this.runner = runner;
        this.store = store;
        this.executor = executor;
    }

    public boolean isRunning(String name) {
        return running.contains(name);
    }

    /** Fire-and-forget: dispatch the (blocking) run onto the chat executor so callers never block. */
    public void submit(ServerScheduledTask task) {
        executor.execute(() -> run(task));
    }

    /** Run one task to completion. No-op if a run for the same task is already in flight. */
    public void run(ServerScheduledTask task) {
        String name = task.getName();
        if (!running.add(name)) {
            return;
        }
        try {
            doRun(task);
        } catch (RuntimeException e) {
            persist(name, "failed", -1);
        } finally {
            running.remove(name);
        }
    }

    private void doRun(ServerScheduledTask task) {
        String cwd = resolveCwd(task.getWorkingDir());
        List<String> cmd = buildCommand(task);
        StringBuilder body = new StringBuilder();

        StreamingProcessRunner.Handle handle = runner.start(cmd, cwd, task.getPrompt(),
                line -> {
                    synchronized (body) {
                        body.append(line).append('\n');
                    }
                }, executor);

        String status;
        int exit;
        try {
            exit = handle.exit().get(Math.max(1, task.getTimeLimitHours()), TimeUnit.HOURS);
            status = exit == 0 ? "ok" : "failed";
        } catch (TimeoutException te) {
            StreamingProcessRunner.killTree(handle.process());
            exit = -1;
            status = "timeout";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            StreamingProcessRunner.killTree(handle.process());
            exit = -1;
            status = "failed";
        } catch (ExecutionException ee) {
            exit = -1;
            status = "failed";
        }

        String snapshot;
        synchronized (body) {
            snapshot = body.toString();
        }
        writeReport(task, cwd, snapshot, handle.stderr().strip(), status, exit);
        persist(task.getName(), status, exit);
    }

    /** Build the headless CLI command. Package-private: the contract is unit-tested directly. */
    List<String> buildCommand(ServerScheduledTask task) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getChat().getExecutable()); // same CLI binary as chat
        cmd.add("-p");
        cmd.add("--permission-mode");
        cmd.add(mode(task));
        if (task.getMaxTurns() > 0) {
            // A turn cap stops a looping run far cheaper than the wall-clock limit.
            cmd.add("--max-turns");
            cmd.add(String.valueOf(task.getMaxTurns()));
        }
        List<String> tools = splitTools(task.getAllowedTools());
        if (!tools.isEmpty()) {
            cmd.add("--allowedTools");
            cmd.addAll(tools);
        }
        return cmd;
    }

    private String mode(ServerScheduledTask task) {
        String m = task.getPermissionMode();
        return (m == null || m.isBlank()) ? props.getServerSchedule().getPermissionMode() : m;
    }

    /** Split a space/comma-separated allowlist into individual CLI tokens. */
    static List<String> splitTools(String allowedTools) {
        if (allowedTools == null || allowedTools.isBlank()) {
            return List.of();
        }
        return Arrays.stream(allowedTools.split("[ ,]+")).filter(s -> !s.isBlank()).toList();
    }

    private static String resolveCwd(String dir) {
        return (dir != null && !dir.isBlank() && Files.isDirectory(Path.of(dir)))
                ? dir : System.getProperty("user.home");
    }

    /** Write the run output to {@code report-<stamp>.md} and mirror it to {@code latest.md}. */
    void writeReport(ServerScheduledTask task, String cwd, String body, String stderr, String status, int exit) {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder md = new StringBuilder();
        md.append("# ").append(task.getName()).append("\n\n")
                .append("- when: ").append(now.format(HEAD)).append('\n')
                .append("- schedule: ").append(task.getScheduleText()).append('\n')
                .append("- mode: ").append(mode(task)).append('\n')
                .append("- working dir: ").append(cwd).append('\n')
                .append("- status: ").append(status).append(" (exit ").append(exit).append(")\n\n")
                .append("---\n\n")
                .append(body);
        if (stderr != null && !stderr.isBlank()) {
            md.append("\n\n---\n\n### stderr\n\n```\n").append(Format.truncate(stderr, 4000)).append("\n```\n");
        }
        try {
            Path folder = store.reportFolder(task.getName());
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("report-" + now.format(STAMP) + ".md"), md, StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("latest.md"), md, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Re-read the task from disk and stamp its last-run fields (avoids clobbering a concurrent edit). */
    private void persist(String name, String status, int exit) {
        store.read(name).ifPresent(t -> {
            t.setLastRun(LocalDateTime.now().format(WHEN));
            t.setLastStatus(status);
            t.setLastExitCode(exit);
            store.write(t);
        });
    }
}
