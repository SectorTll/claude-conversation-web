package ee.doniss.claudeweb.service.live;

import ee.doniss.claudeweb.config.ClaudeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Watches {@code ~/.claude/projects} for {@code .jsonl} create/modify/delete and pushes a coalesced
 * {@code sessions} event over SSE. Mirrors the desktop's debounced FileSystemWatcher: events in a
 * burst are batched until ~400ms of quiet. {@link WatchService} is not recursive, so each project
 * subdir is registered individually and newly created project dirs are registered on the fly.
 */
@Component
public class SessionWatchService {

    private static final Logger log = LoggerFactory.getLogger(SessionWatchService.class);

    /** One changed session file. */
    public record ChangedSession(String projectId, String sessionId) {
    }

    /** Payload of the {@code sessions} SSE event. */
    public record SessionsChanged(List<ChangedSession> changes) {
    }

    private final ClaudeProperties props;
    private final SseHub hub;
    private final ApplicationEventPublisher events;
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();

    private volatile boolean running;
    private Thread thread;
    private WatchService watchService;

    public SessionWatchService(ClaudeProperties props, SseHub hub, ApplicationEventPublisher events) {
        this.props = props;
        this.hub = hub;
        this.events = events;
    }

    @PostConstruct
    void start() {
        running = true;
        thread = new Thread(this::runLoop, "session-watch");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignore) {
                // closing anyway
            }
        }
    }

    private void runLoop() {
        Path root = props.projectsRoot();
        if (!Files.isDirectory(root)) {
            log.info("projects root {} does not exist — file watching disabled", root);
            return;
        }
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            this.watchService = ws;
            register(ws, root);
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.filter(Files::isDirectory).forEach(d -> register(ws, d));
            }

            while (running) {
                WatchKey key;
                try {
                    key = ws.take();
                } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                    break;
                }
                Set<ChangedSession> pending = new LinkedHashSet<>();
                do {
                    collect(ws, root, key, pending);
                    key.reset();
                    try {
                        key = ws.poll(400, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        key = null;
                    }
                } while (key != null);

                if (!pending.isEmpty()) {
                    SessionsChanged payload = new SessionsChanged(List.copyOf(pending));
                    // In-process listeners (the search indexer) always hear about changes; the SSE
                    // broadcast stays gated on someone actually listening.
                    events.publishEvent(payload);
                    if (hub.hasSubscribers()) {
                        hub.broadcast("sessions", payload);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("file watch loop stopped: {}", e.getMessage());
        }
    }

    private void collect(WatchService ws, Path root, WatchKey key, Set<ChangedSession> pending) {
        Path dir = keys.get(key);
        if (dir == null) {
            return;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                continue;
            }
            Path name = (Path) event.context();
            Path child = dir.resolve(name);

            // A new project directory appeared under the root — start watching it.
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                    && dir.equals(root) && Files.isDirectory(child)) {
                register(ws, child);
                continue;
            }

            String fileName = name.toString();
            if (fileName.endsWith(".jsonl") && !dir.equals(root)) {
                String sessionId = fileName.substring(0, fileName.length() - ".jsonl".length());
                pending.add(new ChangedSession(dir.getFileName().toString(), sessionId));
            }
        }
    }

    private void register(WatchService ws, Path dir) {
        try {
            WatchKey key = dir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            keys.put(key, dir);
        } catch (IOException e) {
            log.debug("could not watch {}: {}", dir, e.getMessage());
        }
    }
}
