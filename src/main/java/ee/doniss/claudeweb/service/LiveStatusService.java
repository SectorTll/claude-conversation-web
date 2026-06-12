package ee.doniss.claudeweb.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.LiveSession;
import ee.doniss.claudeweb.domain.LiveStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads the live-session registry ({@code ~/.claude/sessions/<pid>.json}). Each running Claude
 * process writes its sessionId, cwd and status ({@code busy}/{@code idle}). Only entries whose PID
 * is still alive are trusted (the registry can keep stale files after a crash).
 *
 * <p>Status mapping is strict: only {@code busy} ({@link LiveStatus#WORKING}) and {@code idle}
 * ({@link LiveStatus#IDLE} — a live shell with nothing needed from the user, the grey badge)
 * produce a badge. The registry never yields {@link LiveStatus#WAITING}: that orange
 * needs-your-decision state is reserved for chat turns parked on a permission/question card
 * (reported by the chat engine, merged in by {@code LivePoller}). A process that registers but
 * reports no/unknown status (e.g. the {@code claude-desktop} app) is hidden — showing it as
 * "working" by default would mislabel an idle session. Entries are de-duplicated by sessionId with
 * {@code WORKING} winning over {@code IDLE}: the in-browser chat runs a headless {@code claude}
 * as a separate process, so the same sessionId can be reported by both that busy turn and an idle
 * CLI shell — a busy process means real work is happening, so it must read "working".
 */
@Service
public class LiveStatusService {

    private final ClaudeProperties props;
    private final ObjectMapper mapper;

    public LiveStatusService(ClaudeProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public List<LiveSession> getLiveSessions() {
        // Keyed by sessionId so duplicate entries (e.g. an idle CLI shell + a busy headless
        // `claude` turn for the same session) collapse to one, WORKING winning over IDLE.
        Map<String, LiveSession> bySession = new LinkedHashMap<>();
        Path dir = props.sessionsRoot();
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))::iterator) {
                try {
                    JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
                    String sid = Json.str(root, "sessionId");
                    if (sid == null || sid.isEmpty()) {
                        continue;
                    }
                    JsonNode pidNode = root.get("pid");
                    if (pidNode != null && pidNode.canConvertToLong() && !isProcessAlive(pidNode.asLong())) {
                        continue; // stale registry entry
                    }
                    LiveStatus status = switch (Json.str(root, "status")) {
                        case "busy" -> LiveStatus.WORKING;
                        case "idle" -> LiveStatus.IDLE;
                        // No/unknown status (e.g. the claude-desktop app): hide it rather than
                        // mislabel an idle session as "working".
                        case null, default -> null;
                    };
                    if (status == null) {
                        continue;
                    }
                    LiveSession candidate = new LiveSession(sid, Json.str(root, "cwd"), status);
                    bySession.merge(sid, candidate, (existing, incoming) ->
                            existing.status() == LiveStatus.WORKING ? existing : incoming);
                } catch (Exception ignore) {
                    // skip unreadable registry file
                }
            }
        } catch (Exception ignore) {
            // sessions dir vanished mid-scan — return what we have
        }
        return new ArrayList<>(bySession.values());
    }

    private static boolean isProcessAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
