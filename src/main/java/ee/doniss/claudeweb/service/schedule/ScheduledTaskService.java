package ee.doniss.claudeweb.service.schedule;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.ScheduleKind;
import ee.doniss.claudeweb.domain.ScheduledTaskInfo;
import ee.doniss.claudeweb.domain.TaskSpec;
import ee.doniss.claudeweb.os.ProcessRunner;
import ee.doniss.claudeweb.support.BadRequestException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Creates and manages Windows Task Scheduler jobs that run the Claude CLI headless on a schedule.
 * Faithful port of the desktop {@code Services/ScheduledTaskService.cs}: a hidden wscript launcher
 * runs a UTF-8 PowerShell that pipes {@code $null | claude -p "<prompt>"} to a report file. Tasks
 * live under Task Scheduler folder {@code \Claude\}; their scripts/reports live under the
 * configured tasks root.
 */
@Service
public class ScheduledTaskService {

    public static final String TASK_FOLDER = "\\Claude\\";

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter ONCE_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter MON_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter NEXT_IN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter NEXT_OUT = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH);
    private static final Pattern UNSAFE = Pattern.compile("[^\\w\\- ]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final ClaudeProperties props;
    private final ProcessRunner runner;
    private final ObjectMapper mapper;

    public ScheduledTaskService(ClaudeProperties props, ProcessRunner runner, ObjectMapper mapper) {
        this.props = props;
        this.runner = runner;
        this.mapper = mapper;
    }

    private Path tasksRoot() {
        return props.resolvedTasksRoot();
    }

    public Path folderOf(String name) {
        return tasksRoot().resolve(safeName(name));
    }

    // ----------------------------------------------------------------- create

    public ScheduledTaskInfo create(TaskSpec spec) {
        if (spec.getName() == null || spec.getName().isBlank()) {
            throw new BadRequestException("Task name is required.");
        }
        if (spec.getPrompt() == null || spec.getPrompt().isBlank()) {
            throw new BadRequestException("Prompt is required.");
        }

        String safe = safeName(spec.getName());
        Path folder = tasksRoot().resolve(safe);
        try {
            Files.createDirectories(folder);

            String dir = isDir(spec.getWorkingDir()) ? spec.getWorkingDir() : userHome();

            writeUtf8(folder.resolve("prompt.txt"), spec.getPrompt());
            writeUtf8(folder.resolve("run.ps1"), buildRunner(spec, folder, dir));
            writeUtf8(folder.resolve("run-hidden.vbs"), buildVbs(folder));

            ScheduledTaskInfo info = new ScheduledTaskInfo();
            info.setName(safe);
            info.setFolder(folder.toString());
            info.setPrompt(spec.getPrompt());
            info.setWorkingDir(dir);
            info.setScheduleText(describeSchedule(spec));
            writeUtf8(folder.resolve("meta.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(info));

            Path register = folder.resolve("register.ps1");
            writeUtf8(register, buildRegister(spec, safe, folder, dir));

            ProcessRunner.Result result = runPowerShellFile(register);
            if (!result.ok()) {
                throw new IllegalStateException("schtasks registration failed:\n" + result.output());
            }
            return info;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ----------------------------------------------------------------- list / run / delete

    public List<ScheduledTaskInfo> list() {
        List<ScheduledTaskInfo> list = new ArrayList<>();
        Path root = tasksRoot();
        if (!Files.isDirectory(root)) {
            return list;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path folder : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                Path meta = folder.resolve("meta.json");
                if (!Files.isRegularFile(meta)) {
                    continue;
                }
                try {
                    ScheduledTaskInfo info =
                            mapper.readValue(Files.readString(meta, StandardCharsets.UTF_8), ScheduledTaskInfo.class);
                    info.setFolder(folder.toString());
                    list.add(info);
                } catch (Exception ignore) {
                    // skip unreadable
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        enrichState(list);
        list.sort(Comparator.comparing(ScheduledTaskInfo::getName, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public void runNow(String name) {
        ProcessRunner.Result r = runEncoded(
                "Start-ScheduledTask -TaskName '" + ps(safeName(name)) + "' -TaskPath '" + TASK_FOLDER + "'");
        if (!r.ok()) {
            throw new IllegalStateException("Start-ScheduledTask failed:\n" + r.output());
        }
    }

    public void delete(String name, boolean removeFiles) {
        runEncoded("Unregister-ScheduledTask -TaskName '" + ps(safeName(name)) + "' -TaskPath '"
                + TASK_FOLDER + "' -Confirm:$false");
        Path folder = folderOf(name);
        if (removeFiles && Files.isDirectory(folder)) {
            deleteRecursively(folder);
        }
    }

    /** Pull live State / NextRun for each task in one PowerShell round-trip (best effort). */
    private void enrichState(List<ScheduledTaskInfo> list) {
        if (list.isEmpty()) {
            return;
        }
        try {
            ProcessRunner.Result r = runEncoded(
                    "Get-ScheduledTask -TaskPath '\\Claude\\' -ErrorAction SilentlyContinue | "
                            + "ForEach-Object { $i = $_ | Get-ScheduledTaskInfo; $n=''; "
                            + "if($i.NextRunTime){ $n=$i.NextRunTime.ToString('yyyy-MM-dd HH:mm') }; "
                            + "[pscustomobject]@{ Name=$_.TaskName; State=[string]$_.State; Next=$n } } | "
                            + "ConvertTo-Json -Compress");
            if (!r.ok() || r.output().isBlank()) {
                return;
            }
            String json = r.output().strip();
            if (json.startsWith("{")) {
                json = "[" + json + "]";
            }
            JsonNode arr = mapper.readTree(json);
            if (!arr.isArray()) {
                return;
            }
            for (JsonNode el : arr) {
                String name = el.has("Name") ? el.get("Name").asString() : null;
                if (name == null) {
                    continue;
                }
                list.stream().filter(t -> t.getName().equals(name)).findFirst().ifPresent(match -> {
                    match.setState(el.has("State") ? el.get("State").asString() : "");
                    String next = el.has("Next") ? el.get("Next").asString() : "";
                    match.setNextRun(formatNext(next));
                });
            }
        } catch (Exception ignore) {
            // state is best-effort
        }
    }

    // ----------------------------------------------------------------- script builders

    private String buildRunner(TaskSpec spec, Path folder, String dir) {
        List<String> allow = Arrays.stream(spec.getAllowedTools() == null ? new String[0]
                        : spec.getAllowedTools().split("[ ,]+"))
                .filter(s -> !s.isBlank())
                .toList();
        String allowLine = allow.isEmpty()
                ? "$allowed = @()"
                : "$allowed = @(" + allow.stream().map(a -> "'" + ps(a) + "'").collect(Collectors.joining(", ")) + ")";
        String allowArg = allow.isEmpty() ? "" : "--allowedTools @allowed ";
        String openBlock = spec.isOpenReport() ? loadTemplate("open-report.ps1") : "";

        return loadTemplate("runner.ps1")
                .replace("@@DIR@@", ps(dir))
                .replace("@@FOLDER@@", ps(folder.toString()))
                .replace("@@ALLOW_LINE@@", allowLine)
                .replace("@@ALLOW_ARG@@", allowArg)
                .replace("@@OPEN_BLOCK@@", openBlock);
    }

    private String buildVbs(Path folder) {
        String ps1 = folder.resolve("run.ps1").toString();
        String vbs = loadTemplate("run-hidden.vbs").replace("@@PS1@@", ps1);
        // wscript wants CRLF line endings.
        return vbs.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    private String buildRegister(TaskSpec spec, String name, Path folder, String dir) {
        String exec;
        String args;
        if (spec.isHidden()) {
            exec = "wscript.exe";
            args = "\"" + folder.resolve("run-hidden.vbs") + "\"";
        } else {
            exec = "powershell.exe";
            args = "-NoProfile -ExecutionPolicy Bypass -File \"" + folder.resolve("run.ps1") + "\"";
        }

        String trigger = switch (spec.getKind()) {
            case ONCE -> "New-ScheduledTaskTrigger -Once -At '" + spec.getAt().format(ONCE_AT) + "'";
            case WEEKLY -> "New-ScheduledTaskTrigger -Weekly -DaysOfWeek "
                    + spec.getDays().stream().map(ScheduledTaskService::psDay).collect(Collectors.joining(","))
                    + " -At '" + spec.getAt().format(HHMM) + "'";
            default -> "New-ScheduledTaskTrigger -Daily -At '" + spec.getAt().format(HHMM) + "'";
        };

        String desc = "Headless Claude run (" + describeSchedule(spec) + "). Report -> "
                + folder + "\\report-*.md";

        return loadTemplate("register.ps1")
                .replace("@@EXEC@@", ps(exec))
                .replace("@@ARGS@@", ps(args))
                .replace("@@DIR@@", ps(dir))
                .replace("@@TRIGGER@@", trigger)
                .replace("@@TIME_LIMIT@@", String.valueOf(spec.getTimeLimitHours()))
                .replace("@@NAME@@", ps(name))
                .replace("@@TASK_FOLDER@@", TASK_FOLDER)
                .replace("@@DESC@@", ps(desc));
    }

    // ----------------------------------------------------------------- helpers

    public static String describeSchedule(TaskSpec s) {
        return switch (s.getKind()) {
            case ONCE -> "Once on " + s.getAt().format(MON_DAY_YEAR) + " at " + s.getAt().format(HHMM);
            case WEEKLY -> "Weekly (" + s.getDays().stream()
                    .map(d -> d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                    .collect(Collectors.joining("/")) + ") at " + s.getAt().format(HHMM);
            default -> "Daily at " + s.getAt().format(HHMM);
        };
    }

    private static String psDay(DayOfWeek d) {
        return d.getDisplayName(TextStyle.FULL, Locale.ENGLISH); // Monday, Tuesday, ...
    }

    private static String formatNext(String next) {
        if (next == null || next.isBlank()) {
            return "";
        }
        try {
            return LocalDateTime.parse(next.trim(), NEXT_IN).format(NEXT_OUT);
        } catch (Exception e) {
            return next.trim();
        }
    }

    private static String safeName(String name) {
        String cleaned = UNSAFE.matcher(name == null ? "" : name.trim()).replaceAll("").trim();
        cleaned = SPACES.matcher(cleaned).replaceAll(" ");
        return cleaned.isEmpty() ? "ClaudeTask" : cleaned;
    }

    /** Escape a value for embedding inside a PowerShell single-quoted string. */
    private static String ps(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private ProcessRunner.Result runPowerShellFile(Path path) {
        return runner.run(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", path.toString()));
    }

    private ProcessRunner.Result runEncoded(String command) {
        String encoded = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
        return runner.run(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded));
    }

    private String loadTemplate(String name) {
        try (InputStream in = getClass().getResourceAsStream("/scheduler/" + name)) {
            if (in == null) {
                throw new IllegalStateException("scheduler template not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeUtf8(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static boolean isDir(String p) {
        return p != null && !p.isBlank() && Files.isDirectory(Path.of(p));
    }

    private static String userHome() {
        return System.getProperty("user.home");
    }

    private static void deleteRecursively(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // leave reports if locked
                }
            });
        } catch (IOException ignore) {
            // best effort
        }
    }
}
