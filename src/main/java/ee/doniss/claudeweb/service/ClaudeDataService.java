package ee.doniss.claudeweb.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.ChatMessage;
import ee.doniss.claudeweb.domain.ProjectInfo;
import ee.doniss.claudeweb.domain.SearchHit;
import ee.doniss.claudeweb.domain.SessionInfo;
import ee.doniss.claudeweb.domain.ToolBlock;
import ee.doniss.claudeweb.support.BadRequestException;
import ee.doniss.claudeweb.support.NotFoundException;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads the on-disk Claude Code session store at {@code ~/.claude/projects/}. Pure I/O + parsing —
 * a faithful port of the WPF app's {@code Services/ClaudeDataService.cs}.
 */
@Service
public class ClaudeDataService {

    private static final Comparator<SessionInfo> BY_LAST_ACTIVITY_DESC =
            Comparator.comparing(SessionInfo::getLastActivity, Comparator.nullsLast(Comparator.reverseOrder()));

    private final ClaudeProperties props;
    private final ObjectMapper mapper;
    private final Pricing pricing;

    public ClaudeDataService(ClaudeProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.pricing = new Pricing(props.getPricing());
    }

    // ----------------------------------------------------------------- projects

    public List<ProjectInfo> loadProjects() {
        Path root = props.projectsRoot();
        List<ProjectInfo> list = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return list;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                List<Path> files = listJsonl(dir);
                ProjectInfo p = new ProjectInfo();
                p.setFolderName(dir.getFileName().toString());
                p.setFolderPath(dir.toString());
                p.setSessionCount(files.size());
                p.setLastActivity(files.isEmpty()
                        ? mtime(dir)
                        : files.stream().map(this::mtime).max(Comparator.naturalOrder()).orElse(mtime(dir)));
                String cwd = tryGetCwd(files);
                p.setRealPath(cwd != null ? cwd : FolderNameCodec.decodeFolderName(p.getFolderName()));
                list.add(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        list.sort(Comparator
                .comparing((ProjectInfo p) -> p.getSessionCount() > 0).reversed()
                .thenComparing(ProjectInfo::getLastActivity, Comparator.nullsLast(Comparator.reverseOrder())));
        return list;
    }

    /** Peek the newest session file for a {@code cwd} field (the real working dir). */
    private String tryGetCwd(List<Path> files) {
        Path newest = files.stream().max(Comparator.comparing(this::mtime)).orElse(null);
        if (newest == null) {
            return null;
        }
        try (BufferedReader r = Files.newBufferedReader(newest, StandardCharsets.UTF_8)) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                if (n++ >= 60) {
                    break;
                }
                if (!line.contains("\"cwd\"")) {
                    continue;
                }
                try {
                    String v = Json.str(mapper.readTree(line), "cwd");
                    if (v != null && !v.isBlank()) {
                        return v;
                    }
                } catch (Exception ignore) {
                    // not valid JSON on this line — keep scanning
                }
            }
        } catch (IOException ignore) {
            // fall through to decode
        }
        return null;
    }

    // ----------------------------------------------------------------- sessions

    public List<SessionInfo> loadSessions(String projectId) {
        Path dir = resolveProjectDir(projectId);
        List<SessionInfo> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try {
            for (Path file : listJsonl(dir)) {
                try {
                    result.add(scanSessionMeta(file));
                } catch (Exception ignore) {
                    // ignore unreadable session
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        result.sort(BY_LAST_ACTIVITY_DESC);
        return result;
    }

    public SessionInfo getSession(String projectId, String sessionId) {
        Path file = resolveSessionFile(projectId, sessionId);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        try {
            return scanSessionMeta(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One streaming pass to collect lightweight metadata for the list. */
    public SessionInfo scanSessionMeta(Path file) throws IOException {
        SessionInfo s = new SessionInfo();
        s.setFilePath(file.toString());
        s.setSessionId(fileNameNoExt(file));
        s.setLastActivity(mtime(file));

        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode root;
                try {
                    root = mapper.readTree(line);
                } catch (Exception e) {
                    continue;
                }
                if (root == null || !root.isObject()) {
                    continue;
                }
                String type = Json.str(root, "type");
                if (type == null) {
                    continue;
                }
                switch (type) {
                    case "custom-title" -> {
                        String v = Json.str(root, "customTitle");
                        if (v != null) {
                            s.setCustomTitle(v);
                        }
                    }
                    case "ai-title" -> {
                        String v = Json.str(root, "aiTitle");
                        if (v != null) {
                            s.setAiTitle(v);
                        }
                    }
                    case "user" -> {
                        s.incrementUserMessages();
                        if (s.getCwd() == null) {
                            s.setCwd(Json.str(root, "cwd"));
                        }
                        if (s.getGitBranch() == null) {
                            s.setGitBranch(Json.str(root, "gitBranch"));
                        }
                        if (s.getVersion() == null) {
                            s.setVersion(Json.str(root, "version"));
                        }
                        if (s.getStarted() == null) {
                            s.setStarted(Json.time(root, "timestamp"));
                        }
                        if (s.getFirstPrompt() == null) {
                            String t = extractUserText(root);
                            if (t != null && !t.isBlank()) {
                                s.setFirstPrompt(CommandTagCleaner.clean(t));
                            }
                        }
                    }
                    case "assistant" -> {
                        s.incrementAssistantMessages();
                        JsonNode msg = root.get("message");
                        JsonNode usage = msg != null && msg.isObject() ? msg.get("usage") : null;
                        if (usage != null && usage.isObject()) {
                            long in = Json.lng(usage, "input_tokens");
                            long out = Json.lng(usage, "output_tokens");
                            long cw = Json.lng(usage, "cache_creation_input_tokens");
                            long cr = Json.lng(usage, "cache_read_input_tokens");
                            s.addUsage(in, out, cw, cr, pricing.costUsd(Json.str(msg, "model"), in, out, cw, cr));
                        }
                    }
                    default -> {
                        // ignore other line types
                    }
                }
            }
        }
        return s;
    }

    // ----------------------------------------------------------------- rename

    /**
     * Rename a session "at the Claude level": append the same records {@code claude --name} writes
     * (custom-title + agent-name), so the title shows up in Claude's own /resume picker and
     * survives auto ai-title regeneration. Returns the freshly re-scanned session.
     */
    public SessionInfo renameSession(String projectId, String sessionId, String newTitle) {
        String title = newTitle == null ? "" : newTitle.strip();
        Path file = resolveSessionFile(projectId, sessionId);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        if (title.isEmpty()) {
            throw new BadRequestException("title is required");
        }
        try {
            appendRecord(file, orderedMap("type", "custom-title", "customTitle", title, "sessionId", sessionId));
            appendRecord(file, orderedMap("type", "agent-name", "agentName", title, "sessionId", sessionId));
            return scanSessionMeta(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void appendRecord(Path file, Map<String, Object> record) throws IOException {
        String json = mapper.writeValueAsString(record);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            long len = raf.length();
            if (len > 0) {
                raf.seek(len - 1);
                if (raf.read() != '\n') {
                    raf.seek(len);
                    raf.writeByte('\n');
                }
            }
            raf.seek(raf.length());
            raf.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    // ----------------------------------------------------------------- branch

    /**
     * Fork a session at a message: copy every line from the start up to AND INCLUDING the line with
     * {@code atUuid} into a NEW session file in the same project dir (fresh UUID id, each copied
     * line's {@code sessionId} rewritten). If the cut line is an assistant message with tool_use
     * blocks whose results follow it, the cut extends over those immediately-following tool_result
     * lines so {@code --resume} never sees a dangling tool_use. The fork gets a "⑂ <original title>"
     * custom title and the freshly scanned {@link SessionInfo} is returned.
     */
    public SessionInfo branchSession(String projectId, String sessionId, String atUuid) {
        if (atUuid == null || atUuid.isBlank()) {
            throw new BadRequestException("uuid is required");
        }
        Path src = resolveSessionFile(projectId, sessionId);
        if (!Files.isRegularFile(src)) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        try {
            List<String> lines = Files.readAllLines(src, StandardCharsets.UTF_8);
            int cut = findCut(lines, atUuid);
            if (cut < 0) {
                throw new NotFoundException("message not found: " + atUuid);
            }
            cut = extendOverDanglingToolResults(lines, cut);

            String newId = java.util.UUID.randomUUID().toString();
            Path dst = resolveSessionFile(projectId, newId);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i <= cut; i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                out.append(rewriteSessionId(line, newId)).append('\n');
            }
            Files.writeString(dst, out.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);

            String title = "⑂ " + scanSessionMeta(src).getTitle();
            return renameSession(projectId, newId, title);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Index of the first parseable object line whose {@code uuid} equals {@code atUuid}, or -1. */
    private int findCut(List<String> lines, String atUuid) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || !line.contains(atUuid)) {
                continue;
            }
            try {
                JsonNode root = mapper.readTree(line);
                if (root != null && root.isObject() && atUuid.equals(Json.str(root, "uuid"))) {
                    return i;
                }
            } catch (Exception ignore) {
                // unparseable line — keep scanning
            }
        }
        return -1;
    }

    /**
     * When the cut line is an assistant message ending in tool_use blocks, pull the immediately
     * following user tool_result lines (matching those ids) into the copy — a fork must not end on
     * an unanswered tool call or resuming it breaks the API conversation.
     */
    private int extendOverDanglingToolResults(List<String> lines, int cut) {
        java.util.Set<String> pending = new java.util.HashSet<>();
        try {
            JsonNode root = mapper.readTree(lines.get(cut));
            if (!"assistant".equals(Json.str(root, "type"))) {
                return cut;
            }
            JsonNode content = root.path("message").path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("tool_use".equals(Json.str(block, "type"))) {
                        String id = Json.str(block, "id");
                        if (id != null) {
                            pending.add(id);
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            return cut;
        }
        while (!pending.isEmpty() && cut + 1 < lines.size()) {
            String next = lines.get(cut + 1);
            boolean matched = false;
            try {
                JsonNode root = mapper.readTree(next);
                if ("user".equals(Json.str(root, "type"))) {
                    JsonNode content = root.path("message").path("content");
                    if (content.isArray()) {
                        for (JsonNode block : content) {
                            if ("tool_result".equals(Json.str(block, "type"))
                                    && pending.remove(Json.str(block, "tool_use_id"))) {
                                matched = true;
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
                // unparseable line — stop extending
            }
            if (!matched) {
                break;
            }
            cut++;
        }
        return cut;
    }

    /** Rewrite the {@code sessionId} field of one jsonl object line; unparseable lines pass through. */
    private String rewriteSessionId(String line, String newId) {
        try {
            JsonNode root = mapper.readTree(line);
            if (root instanceof tools.jackson.databind.node.ObjectNode obj && obj.has("sessionId")) {
                obj.put("sessionId", newId);
                return mapper.writeValueAsString(obj);
            }
        } catch (Exception ignore) {
            // copy verbatim
        }
        return line;
    }

    // ----------------------------------------------------------------- os helpers

    /** Absolute path of a session's {@code .jsonl} file (validated, must exist). */
    public Path sessionPath(String projectId, String sessionId) {
        Path f = resolveSessionFile(projectId, sessionId);
        if (!Files.isRegularFile(f)) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        return f;
    }

    /** Working dir to launch the CLI in for a session: its {@code cwd}, else the project's. */
    public String sessionWorkingDir(String projectId, String sessionId) {
        SessionInfo s = getSession(projectId, sessionId);
        if (s.getCwd() != null && !s.getCwd().isBlank()) {
            return s.getCwd();
        }
        return projectWorkingDir(projectId);
    }

    /** Working dir for a project: a session {@code cwd} if any, else the decoded folder name. */
    public String projectWorkingDir(String projectId) {
        Path dir = resolveProjectDir(projectId);
        if (!Files.isDirectory(dir)) {
            throw new NotFoundException("project not found: " + projectId);
        }
        try {
            String cwd = tryGetCwd(listJsonl(dir));
            if (cwd != null) {
                return cwd;
            }
        } catch (IOException ignore) {
            // fall back to decode
        }
        return FolderNameCodec.decodeFolderName(projectId);
    }

    // ------------------------------------------------------------- conversation

    public List<ChatMessage> loadConversation(String projectId, String sessionId) {
        Path file = resolveSessionFile(projectId, sessionId);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        List<ChatMessage> msgs = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode root;
                try {
                    root = mapper.readTree(line);
                } catch (Exception e) {
                    continue;
                }
                if (root == null) {
                    continue;
                }
                String type = Json.str(root, "type");
                if (!"user".equals(type) && !"assistant".equals(type)) {
                    continue;
                }
                JsonNode msg = root.get("message");
                if (msg == null || !msg.isObject()) {
                    continue;
                }

                ChatMessage cm = new ChatMessage();
                String role = Json.str(msg, "role");
                cm.setRole(role != null ? role : type);
                cm.setUuid(Json.str(root, "uuid"));
                cm.setTimestamp(Json.time(root, "timestamp"));
                if ("assistant".equals(type)) {
                    cm.setModel(Json.str(msg, "model"));
                    JsonNode usage = msg.get("usage");
                    if (usage != null && usage.isObject()) {
                        cm.setUsage(Json.lng(usage, "input_tokens"), Json.lng(usage, "output_tokens"),
                                Json.lng(usage, "cache_creation_input_tokens"),
                                Json.lng(usage, "cache_read_input_tokens"));
                    }
                }

                StringBuilder sb = new StringBuilder();
                JsonNode content = msg.get("content");
                if (content != null) {
                    if (content.isTextual()) {
                        sb.append(content.asText());
                    } else if (content.isArray()) {
                        for (JsonNode block : content) {
                            appendBlock(block, cm, sb);
                        }
                    }
                }
                cm.setText(CommandTagCleaner.clean(sb.toString()).strip());

                // A user turn that only carries tool_result is really tool output.
                if ("user".equals(cm.getRole()) && !cm.isHasText() && !cm.isHasThinking()
                        && !cm.getTools().isEmpty()
                        && cm.getTools().stream().noneMatch(t -> "use".equals(t.kind()))) {
                    cm.setRole("tool");
                }

                if (cm.isHasText() || cm.isHasThinking() || cm.isHasTools()) {
                    msgs.add(cm);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return msgs;
    }

    private void appendBlock(JsonNode block, ChatMessage cm, StringBuilder sb) {
        String type = Json.str(block, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "text" -> {
                String t = Json.str(block, "text");
                if (t != null && !t.isEmpty()) {
                    sb.append(t).append('\n');
                }
            }
            case "thinking" -> {
                String th = Json.str(block, "thinking");
                if (th != null && !th.isBlank()) {
                    String prev = cm.getThinking();
                    cm.setThinking(prev == null || prev.isEmpty() ? th : prev + "\n" + th);
                }
            }
            case "tool_use" -> {
                String name = Json.str(block, "name");
                if (name == null) {
                    name = "tool";
                }
                JsonNode inp = block.get("input");
                String body = "AskUserQuestion".equals(name)
                        ? questionBody(inp)
                        : (inp != null ? prettyJson(inp) : "");
                ToolBlock tb = switch (name) {
                    case "Edit" -> ToolBlock.edit(name, body, Json.str(inp, "file_path"),
                            capDiffText(Json.str(inp, "old_string")), capDiffText(Json.str(inp, "new_string")));
                    case "Write" -> ToolBlock.edit(name, body, Json.str(inp, "file_path"),
                            null, capDiffText(Json.str(inp, "content")));
                    default -> ToolBlock.use(name, body);
                };
                cm.getTools().add(tb);
            }
            case "tool_result" -> {
                JsonNode rc = block.get("content");
                String body = rc != null ? flattenContent(rc) : "";
                cm.getTools().add(ToolBlock.result(Format.truncate(body, 4000)));
            }
            case "image" -> sb.append("🖼 [image]").append('\n');
            default -> {
                // ignore unknown block types
            }
        }
    }

    // ----------------------------------------------------------------- search

    public List<SearchHit> searchAll(String query, int maxResults) {
        List<SearchHit> hits = new ArrayList<>();
        Path root = props.projectsRoot();
        if (query == null || query.isBlank() || !Files.isDirectory(root)) {
            return hits;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                for (Path file : listJsonl(dir)) {
                    SearchHit hit = searchSessionFile(dir, file, query);
                    if (hit == null) {
                        continue;
                    }
                    hits.add(hit);
                    if (hits.size() >= maxResults) {
                        hits.sort(byHitLastActivityDesc());
                        return hits;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        hits.sort(byHitLastActivityDesc());
        return hits;
    }

    /**
     * Brute-force scan of ONE session file: case-insensitive substring match over the per-line
     * text, returning the hit (match count + first snippet) or null when nothing matches. The
     * index path reuses this per candidate, which keeps its results byte-identical to a full scan.
     */
    public SearchHit searchSessionFile(Path projectDir, Path file, String query) {
        int count = 0;
        String snippet = null;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                // Cheap prefilter before the (more expensive) JSON parse.
                if (indexOfIgnoreCase(line, query) < 0) {
                    continue;
                }
                String text = extractLineText(line);
                if (text == null) {
                    continue;
                }
                int idx = indexOfIgnoreCase(text, query);
                if (idx < 0) {
                    continue;
                }
                count++;
                if (snippet == null) {
                    snippet = makeSnippet(text, idx, query.length());
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (count == 0) {
            return null;
        }
        SessionInfo s;
        try {
            s = scanSessionMeta(file);
        } catch (IOException e) {
            return null;
        }
        String projectName = FolderNameCodec.segmentOf(s.getCwd());
        if (projectName == null) {
            projectName = projectDir.getFileName().toString();
        }
        return new SearchHit(s, projectDir.getFileName().toString(), projectName,
                snippet != null ? snippet : "", count);
    }

    /** The concatenated searchable text of a whole session file (for the search indexer). */
    public String extractSessionText(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String text = extractLineText(line);
                if (text != null && !text.isBlank()) {
                    sb.append(text).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static Comparator<SearchHit> byHitLastActivityDesc() {
        return Comparator.comparing(h -> h.getSession().getLastActivity(),
                Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /** Human-readable text of a user/assistant line (or null for non-message lines). */
    private String extractLineText(String line) {
        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (Exception e) {
            return null;
        }
        if (root == null) {
            return null;
        }
        String type = Json.str(root, "type");
        if (!"user".equals(type) && !"assistant".equals(type)) {
            return null;
        }
        JsonNode msg = root.get("message");
        if (msg == null || !msg.isObject()) {
            return null;
        }
        JsonNode content = msg.get("content");
        if (content == null) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : content) {
                String bt = Json.str(b, "type");
                if ("text".equals(bt)) {
                    String t = Json.str(b, "text");
                    if (t != null) {
                        sb.append(t);
                    }
                    sb.append('\n');
                } else if ("thinking".equals(bt)) {
                    String t = Json.str(b, "thinking");
                    if (t != null) {
                        sb.append(t);
                    }
                    sb.append('\n');
                } else if ("tool_use".equals(bt)) {
                    JsonNode inp = b.get("input");
                    if (inp != null) {
                        sb.append(inp.toString()).append('\n');
                    }
                } else if ("tool_result".equals(bt)) {
                    JsonNode rc = b.get("content");
                    if (rc != null) {
                        sb.append(flattenContent(rc)).append('\n');
                    }
                }
            }
            return sb.toString();
        }
        return null;
    }

    private static String makeSnippet(String text, int idx, int qlen) {
        int pad = 60;
        int start = Math.max(0, idx - pad);
        int end = Math.min(text.length(), idx + qlen + pad);
        String slice = text.substring(start, end).replace('\n', ' ').replace('\r', ' ');
        slice = slice.replaceAll("\\s+", " ").strip();
        return (start > 0 ? "…" : "") + slice + (end < text.length() ? "…" : "");
    }

    // ----------------------------------------------------------------- helpers

    private String extractUserText(JsonNode root) {
        JsonNode msg = root.get("message");
        if (msg == null || !msg.isObject()) {
            return null;
        }
        JsonNode content = msg.get("content");
        if (content == null) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            for (JsonNode b : content) {
                if ("text".equals(Json.str(b, "type"))) {
                    return Json.str(b, "text");
                }
            }
        }
        return null;
    }

    private String flattenContent(JsonNode el) {
        if (el == null) {
            return "";
        }
        if (el.isTextual()) {
            return el.asText();
        }
        if (el.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : el) {
                if ("text".equals(Json.str(b, "type"))) {
                    String t = Json.str(b, "text");
                    if (t != null) {
                        sb.append(t);
                    }
                    sb.append('\n');
                } else if (b.isTextual()) {
                    sb.append(b.asText()).append('\n');
                }
            }
            return sb.toString().strip();
        }
        return el.toString();
    }

    /**
     * Render AskUserQuestion's input as readable question/options text (with the picked answer when
     * the turn continued past it) instead of the raw JSON blob.
     */
    private String questionBody(JsonNode input) {
        JsonNode questions = input != null ? input.get("questions") : null;
        if (questions == null || !questions.isArray() || questions.isEmpty()) {
            return input != null ? prettyJson(input) : "";
        }
        JsonNode answers = input.get("answers");
        StringBuilder sb = new StringBuilder();
        for (JsonNode q : questions) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            String header = Json.str(q, "header");
            if (header != null && !header.isBlank()) {
                sb.append('[').append(header).append("] ");
            }
            String question = Json.str(q, "question");
            sb.append(question != null ? question : "").append('\n');
            JsonNode options = q.get("options");
            if (options != null && options.isArray()) {
                for (JsonNode o : options) {
                    String label = Json.str(o, "label");
                    sb.append("  ○ ").append(label != null ? label : "");
                    String desc = Json.str(o, "description");
                    if (desc != null && !desc.isBlank()) {
                        sb.append(" — ").append(desc);
                    }
                    sb.append('\n');
                }
            }
            String picked = question != null ? Json.str(answers, question) : null;
            if (picked != null && !picked.isBlank()) {
                sb.append("  ✔ ").append(picked).append('\n');
            }
        }
        return Format.truncate(sb.toString().stripTrailing(), 2000);
    }

    /**
     * Bound the structured diff payload. Unlike {@link Format#truncate} this preserves leading
     * whitespace (indentation is part of a diff) and keeps null as null.
     */
    private static String capDiffText(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 20_000 ? s : s.substring(0, 20_000) + "…";
    }

    private String prettyJson(JsonNode el) {
        try {
            // Short scalars/objects inline; long ones get truncated for the chip body.
            String raw = el.toString();
            return Format.truncate(raw.replace("\\n", "\n"), 2000);
        } catch (Exception e) {
            return "";
        }
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        int n = haystack.length();
        int m = needle.length();
        if (m == 0) {
            return 0;
        }
        for (int i = 0; i + m <= n; i++) {
            if (haystack.regionMatches(true, i, needle, 0, m)) {
                return i;
            }
        }
        return -1;
    }

    /** The session files of one project dir (top-level {@code *.jsonl} only — matches the views). */
    public List<Path> listSessionFiles(Path dir) throws IOException {
        return listJsonl(dir);
    }

    private List<Path> listJsonl(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        }
    }

    private Instant mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private static String fileNameNoExt(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(0, dot) : n;
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ----------------------------------------------------------- path resolution

    private Path resolveProjectDir(String projectId) {
        Path projects = props.projectsRoot().toAbsolutePath().normalize();
        if (projectId == null || projectId.isBlank() || projectId.contains("/")
                || projectId.contains("\\") || projectId.contains("..")) {
            throw new BadRequestException("invalid projectId");
        }
        Path dir = projects.resolve(projectId).normalize();
        if (!dir.startsWith(projects)) {
            throw new BadRequestException("invalid projectId");
        }
        return dir;
    }

    private Path resolveSessionFile(String projectId, String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("/")
                || sessionId.contains("\\") || sessionId.contains("..")) {
            throw new BadRequestException("invalid sessionId");
        }
        Path projects = props.projectsRoot().toAbsolutePath().normalize();
        Path file = resolveProjectDir(projectId).resolve(sessionId + ".jsonl").normalize();
        if (!file.startsWith(projects)) {
            throw new BadRequestException("invalid session path");
        }
        return file;
    }
}
