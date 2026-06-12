package ee.doniss.claudeweb.service.schedule.server;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.ServerScheduledTask;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * On-disk persistence for server-scheduled tasks. Each task is a folder under the server-schedule
 * store holding one {@code task.json} (definition + last-run state) plus its {@code report-*.md} /
 * {@code latest.md} run outputs. Deliberately a separate root from the Windows approach's tasks-root
 * so neither {@code list()} ever sees the other's folders.
 */
@Component
public class ServerTaskStore {

    private static final Pattern UNSAFE = Pattern.compile("[^\\w\\- ]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final ClaudeProperties props;
    private final ObjectMapper mapper;

    public ServerTaskStore(ClaudeProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public Path root() {
        return props.resolvedServerScheduleStore();
    }

    public Path folderOf(String name) {
        return root().resolve(safeName(name));
    }

    public Path reportFolder(String name) {
        return folderOf(name);
    }

    /** Strip filesystem-unsafe characters and collapse spaces (same rules as the Windows approach). */
    public static String safeName(String name) {
        String cleaned = UNSAFE.matcher(name == null ? "" : name.trim()).replaceAll("").trim();
        cleaned = SPACES.matcher(cleaned).replaceAll(" ");
        return cleaned.isEmpty() ? "ClaudeTask" : cleaned;
    }

    public List<ServerScheduledTask> readAll() {
        List<ServerScheduledTask> list = new ArrayList<>();
        Path root = root();
        if (!Files.isDirectory(root)) {
            return list;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path folder : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                readFrom(folder).ifPresent(list::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        list.sort(Comparator.comparing(ServerScheduledTask::getName, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public Optional<ServerScheduledTask> read(String name) {
        return readFrom(folderOf(name));
    }

    private Optional<ServerScheduledTask> readFrom(Path folder) {
        Path meta = folder.resolve("task.json");
        if (!Files.isRegularFile(meta)) {
            return Optional.empty();
        }
        try {
            ServerScheduledTask t =
                    mapper.readValue(Files.readString(meta, StandardCharsets.UTF_8), ServerScheduledTask.class);
            t.setFolder(folder.toString());
            return Optional.of(t);
        } catch (Exception ignore) {
            return Optional.empty(); // skip unreadable
        }
    }

    public void write(ServerScheduledTask task) {
        try {
            Path folder = folderOf(task.getName());
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("task.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(task), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String name) {
        Path folder = folderOf(name);
        if (Files.isDirectory(folder)) {
            deleteRecursively(folder);
        }
    }

    private static void deleteRecursively(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // leave locked files behind
                }
            });
        } catch (IOException ignore) {
            // best effort
        }
    }
}
