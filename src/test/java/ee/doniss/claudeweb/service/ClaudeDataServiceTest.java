package ee.doniss.claudeweb.service;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.ChatMessage;
import ee.doniss.claudeweb.domain.ProjectInfo;
import ee.doniss.claudeweb.domain.SearchHit;
import ee.doniss.claudeweb.domain.SessionInfo;
import ee.doniss.claudeweb.support.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeDataServiceTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @TempDir
    Path home;

    private ClaudeDataService data;
    private Path projectsRoot;

    @BeforeEach
    void setUp() {
        ClaudeProperties props = new ClaudeProperties();
        props.setHome(home);
        data = new ClaudeDataService(props, mapper);
        projectsRoot = home.resolve("projects");
    }

    private Path writeSession(String projectFolder, String sessionId, String... lines) throws IOException {
        Path dir = projectsRoot.resolve(projectFolder);
        Files.createDirectories(dir);
        Path file = dir.resolve(sessionId + ".jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void scanSessionMetaTitlePriorityAndCounts() throws IOException {
        writeSession("C--Work-demo", "s1",
                "{\"type\":\"user\",\"timestamp\":\"2026-06-01T10:00:00Z\",\"cwd\":\"C:/Work/demo\","
                        + "\"gitBranch\":\"main\",\"version\":\"2.1.168\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"first prompt here\"}}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}",
                "{\"type\":\"ai-title\",\"aiTitle\":\"AI generated\"}",
                "{\"type\":\"custom-title\",\"customTitle\":\"Custom Wins\"}");

        List<SessionInfo> sessions = data.loadSessions("C--Work-demo");
        assertEquals(1, sessions.size());
        SessionInfo s = sessions.get(0);
        assertEquals("Custom Wins", s.getTitle());
        assertTrue(s.isHasCustomTitle());
        assertEquals(1, s.getUserMessages());
        assertEquals(1, s.getAssistantMessages());
        assertEquals("C:/Work/demo", s.getCwd());
        assertEquals("main", s.getGitBranch());
        assertEquals("2.1.168", s.getVersion());
        assertEquals("first prompt here", s.getFirstPrompt());
    }

    @Test
    void aiTitleUsedWhenNoCustomTitle() throws IOException {
        writeSession("C--Work-demo", "s2",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}",
                "{\"type\":\"ai-title\",\"aiTitle\":\"Just AI\"}");
        SessionInfo s = data.getSession("C--Work-demo", "s2");
        assertEquals("Just AI", s.getTitle());
        assertFalse(s.isHasCustomTitle());
    }

    @Test
    void loadConversationParsesBlocksAndReclassifiesToolResult() throws IOException {
        writeSession("C--Work-demo", "conv",
                // user text
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"do a thing\"}}",
                // assistant: thinking + text + tool_use
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"thinking\",\"thinking\":\"let me think\"},"
                        + "{\"type\":\"text\",\"text\":\"Here is the plan\"},"
                        + "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}"
                        + "]}}",
                // user turn carrying only a tool_result -> reclassified as role=tool
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":["
                        + "{\"type\":\"tool_result\",\"content\":\"file1\\nfile2\"}"
                        + "]}}");

        List<ChatMessage> msgs = data.loadConversation("C--Work-demo", "conv");
        assertEquals(3, msgs.size());

        ChatMessage user = msgs.get(0);
        assertEquals("user", user.getRole());
        assertEquals("do a thing", user.getText());

        ChatMessage asst = msgs.get(1);
        assertEquals("assistant", asst.getRole());
        assertTrue(asst.isHasThinking());
        assertEquals("let me think", asst.getThinking());
        assertEquals("Here is the plan", asst.getText());
        assertEquals(1, asst.getTools().size());
        assertEquals("use", asst.getTools().get(0).kind());
        assertEquals("Bash", asst.getTools().get(0).title());

        ChatMessage tool = msgs.get(2);
        assertEquals("tool", tool.getRole(), "user turn with only tool_result becomes role=tool");
        assertEquals(1, tool.getTools().size());
        assertEquals("result", tool.getTools().get(0).kind());
        assertTrue(tool.getTools().get(0).body().contains("file1"));
    }

    @Test
    void editToolUseCarriesStructuredDiffFields() throws IOException {
        writeSession("C--Work-demo", "edits",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"tool_use\",\"name\":\"Edit\",\"input\":{\"file_path\":\"C:/x/a.ts\","
                        + "\"old_string\":\"  old line\",\"new_string\":\"  new line\",\"replace_all\":false}},"
                        + "{\"type\":\"tool_use\",\"name\":\"Write\",\"input\":{\"file_path\":\"C:/x/b.ts\","
                        + "\"content\":\"created\"}},"
                        + "{\"type\":\"tool_use\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}"
                        + "]}}");

        List<ChatMessage> msgs = data.loadConversation("C--Work-demo", "edits");
        var tools = msgs.get(0).getTools();

        assertEquals("C:/x/a.ts", tools.get(0).filePath());
        assertEquals("  old line", tools.get(0).oldText(), "indentation preserved");
        assertEquals("  new line", tools.get(0).newText());
        assertFalse(tools.get(0).body().isEmpty(), "generic body kept for the conversation filter");

        assertEquals("C:/x/b.ts", tools.get(1).filePath());
        assertEquals(null, tools.get(1).oldText(), "Write is a pure addition");
        assertEquals("created", tools.get(1).newText());

        assertEquals(null, tools.get(2).filePath(), "non-edit tools carry no diff fields");
        assertEquals(null, tools.get(2).oldText());
        assertEquals(null, tools.get(2).newText());
    }

    @Test
    void editDiffTextIsCappedAt20k() throws IOException {
        String big = "x".repeat(25_000);
        writeSession("C--Work-demo", "bigedit",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"tool_use\",\"name\":\"Write\",\"input\":{\"file_path\":\"f\",\"content\":\""
                        + big + "\"}}]}}");

        var tool = data.loadConversation("C--Work-demo", "bigedit").get(0).getTools().get(0);
        assertEquals(20_001, tool.newText().length(), "20k + ellipsis");
        assertTrue(tool.newText().endsWith("…"));
    }

    @Test
    void askUserQuestionToolUseRendersReadableBodyNotRawJson() throws IOException {
        writeSession("C--Work-demo", "ask",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"tool_use\",\"name\":\"AskUserQuestion\",\"input\":{"
                        + "\"questions\":[{\"question\":\"Which DB?\",\"header\":\"DB\",\"multiSelect\":false,"
                        + "\"options\":[{\"label\":\"Postgres\",\"description\":\"relational\"},"
                        + "{\"label\":\"Redis\",\"description\":\"kv\"}]}],"
                        + "\"answers\":{\"Which DB?\":\"Postgres\"}}}"
                        + "]}}");

        List<ChatMessage> msgs = data.loadConversation("C--Work-demo", "ask");
        assertEquals(1, msgs.get(0).getTools().size());
        assertEquals("AskUserQuestion", msgs.get(0).getTools().get(0).title());
        assertEquals(
                "[DB] Which DB?\n  ○ Postgres — relational\n  ○ Redis — kv\n  ✔ Postgres",
                msgs.get(0).getTools().get(0).body());
    }

    @Test
    void scanSessionMetaAggregatesUsageAndCost() throws IOException {
        writeSession("C--Work-demo", "usage",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"claude-haiku-4-5\","
                        + "\"usage\":{\"input_tokens\":100,\"output_tokens\":200,"
                        + "\"cache_creation_input_tokens\":1000,\"cache_read_input_tokens\":2000},"
                        + "\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"claude-haiku-4-5\","
                        + "\"usage\":{\"input_tokens\":50,\"output_tokens\":50},"
                        + "\"content\":[{\"type\":\"text\",\"text\":\"b\"}]}}");

        SessionInfo s = data.getSession("C--Work-demo", "usage");
        assertEquals(150, s.getInputTokens());
        assertEquals(250, s.getOutputTokens());
        assertEquals(1000, s.getCacheCreationTokens());
        assertEquals(2000, s.getCacheReadTokens());
        // haiku $1/$5 per MTok: 150*1 + 250*5 + 1000*1*1.25 + 2000*1*0.1 = 1400 + 1250 + 200 = 2850 / 1e6
        assertEquals(0.00285, s.getCostUsd(), 1e-9);
        assertTrue(s.getUsageLine().contains("out"));
        assertTrue(s.getUsageTooltip().contains("estimated"));
    }

    @Test
    void loadConversationExposesPerMessageUsageAndModel() throws IOException {
        writeSession("C--Work-demo", "perm",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\","
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":8,"
                        + "\"cache_creation_input_tokens\":3891,\"cache_read_input_tokens\":9302},"
                        + "\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}");

        ChatMessage m = data.loadConversation("C--Work-demo", "perm").get(0);
        assertEquals("claude-sonnet-4-6", m.getModel());
        assertEquals(3L, m.getInputTokens());
        assertEquals(8L, m.getOutputTokens());
        assertEquals(3891L, m.getCacheCreationTokens());
        assertEquals(9302L, m.getCacheReadTokens());
        assertTrue(m.getTokensDisplay().startsWith("claude-sonnet-4-6"));
    }

    @Test
    void usageAbsentLeavesNullsAndNoUsageLine() throws IOException {
        writeSession("C--Work-demo", "old",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}",
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}}");

        SessionInfo s = data.getSession("C--Work-demo", "old");
        assertEquals(0, s.getInputTokens());
        assertEquals(null, s.getUsageLine());
        ChatMessage asst = data.loadConversation("C--Work-demo", "old").get(1);
        assertEquals(null, asst.getInputTokens());
        assertEquals(null, asst.getTokensDisplay());
    }

    @Test
    void searchAllFindsMatchAndBuildsSnippet() throws IOException {
        writeSession("C--Work-demo", "hit",
                "{\"type\":\"user\",\"timestamp\":\"2026-06-01T10:00:00Z\",\"cwd\":\"C:/Work/demo\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"please fix the parser bug today\"}}");
        writeSession("C--Work-demo", "miss",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"unrelated chatter\"}}");

        List<SearchHit> hits = data.searchAll("parser", 400);
        assertEquals(1, hits.size());
        SearchHit h = hits.get(0);
        assertEquals(1, h.getCount());
        assertTrue(h.getSnippet().toLowerCase().contains("parser"));
        assertEquals("demo", h.getProjectName());
    }

    @Test
    void branchCopiesPrefixRewritesSessionIdsAndAppendsForkTitle() throws IOException {
        Path src = writeSession("C--Work-demo", "orig",
                "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"orig\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"first question\"}}",
                "{\"type\":\"assistant\",\"uuid\":\"a1\",\"sessionId\":\"orig\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"answer one\"}]}}",
                "{\"type\":\"user\",\"uuid\":\"u2\",\"sessionId\":\"orig\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"second question\"}}",
                "{\"type\":\"assistant\",\"uuid\":\"a2\",\"sessionId\":\"orig\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"answer two\"}]}}");
        String srcBefore = Files.readString(src, StandardCharsets.UTF_8);

        SessionInfo fork = data.branchSession("C--Work-demo", "orig", "a1");

        assertTrue(fork.getTitle().startsWith("⑂ "), "fork marker title");
        assertFalse("orig".equals(fork.getSessionId()));
        assertEquals(srcBefore, Files.readString(src, StandardCharsets.UTF_8), "source untouched");

        Path dst = projectsRoot.resolve("C--Work-demo").resolve(fork.getSessionId() + ".jsonl");
        List<String> lines = Files.readAllLines(dst, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).toList();
        assertEquals(4, lines.size(), "2 copied lines + custom-title + agent-name");
        assertTrue(lines.get(0).contains("\"sessionId\":\"" + fork.getSessionId() + "\""), "sessionId rewritten");
        assertTrue(lines.get(1).contains("\"sessionId\":\"" + fork.getSessionId() + "\""));
        assertFalse(lines.get(0).contains("\"sessionId\":\"orig\""));

        List<ChatMessage> msgs = data.loadConversation("C--Work-demo", fork.getSessionId());
        assertEquals(2, msgs.size(), "fork contains only the prefix");
        assertEquals("answer one", msgs.get(1).getText());
    }

    @Test
    void branchExtendsCutOverDanglingToolResults() throws IOException {
        writeSession("C--Work-demo", "tools",
                "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"tools\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"run things\"}}",
                "{\"type\":\"assistant\",\"uuid\":\"a1\",\"sessionId\":\"tools\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":["
                        + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}},"
                        + "{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"Bash\",\"input\":{\"command\":\"pwd\"}}]}}",
                "{\"type\":\"user\",\"uuid\":\"r1\",\"sessionId\":\"tools\","
                        + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"files\"}]}}",
                "{\"type\":\"user\",\"uuid\":\"r2\",\"sessionId\":\"tools\","
                        + "\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t2\",\"content\":\"/home\"}]}}",
                "{\"type\":\"user\",\"uuid\":\"u2\",\"sessionId\":\"tools\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"unrelated follow-up\"}}");

        SessionInfo fork = data.branchSession("C--Work-demo", "tools", "a1");

        Path dst = projectsRoot.resolve("C--Work-demo").resolve(fork.getSessionId() + ".jsonl");
        String raw = Files.readString(dst, StandardCharsets.UTF_8);
        assertTrue(raw.contains("\"uuid\":\"r1\""), "first tool_result pulled into the fork");
        assertTrue(raw.contains("\"uuid\":\"r2\""), "second tool_result pulled into the fork");
        assertFalse(raw.contains("unrelated follow-up"), "lines after the results are not copied");
    }

    @Test
    void branchAtUnknownUuidIsNotFound() throws IOException {
        writeSession("C--Work-demo", "nb",
                "{\"type\":\"user\",\"uuid\":\"u1\",\"sessionId\":\"nb\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"hi\"}}");

        assertThrows(NotFoundException.class, () -> data.branchSession("C--Work-demo", "nb", "nope"));
    }

    @Test
    void renameAppendsTwoRecordsAndPreservesCyrillicUnescaped() throws IOException {
        Path file = writeSession("C--Work-demo", "ren",
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}");
        long linesBefore = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).count();

        SessionInfo updated = data.renameSession("C--Work-demo", "ren", "Привет Мир");
        assertEquals("Привет Мир", updated.getTitle());

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        long linesAfter = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).count();
        assertEquals(linesBefore + 2, linesAfter, "custom-title + agent-name appended");
        assertTrue(raw.contains("\"customTitle\":\"Привет Мир\""), "cyrillic stored unescaped");
        assertTrue(raw.contains("\"agentName\":\"Привет Мир\""));
    }

    @Test
    void renameAddsMissingTrailingNewlineFirst() throws IOException {
        Path dir = projectsRoot.resolve("C--Work-demo");
        Files.createDirectories(dir);
        Path file = dir.resolve("nonewline.jsonl");
        // No trailing newline on purpose.
        Files.writeString(file, "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"x\"}}",
                StandardCharsets.UTF_8);

        data.renameSession("C--Work-demo", "nonewline", "Renamed");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.stream().filter(l -> !l.isBlank()).count());
    }

    @Test
    void loadProjectsPrefersCwdAndDecodesEmptyFolders() throws IOException {
        writeSession("C--Encoded-folder-name", "x",
                "{\"type\":\"user\",\"cwd\":\"C:/Real/place\",\"message\":{\"role\":\"user\",\"content\":\"y\"}}");
        // empty project (no sessions) -> falls back to decoded folder name
        Files.createDirectories(projectsRoot.resolve("D--Empty-Dir"));

        List<ProjectInfo> projects = data.loadProjects();
        assertEquals(2, projects.size());
        // has-sessions project sorts first
        ProjectInfo first = projects.get(0);
        assertEquals("C--Encoded-folder-name", first.getProjectId());
        assertEquals("C:/Real/place", first.getRealPath());
        assertEquals("place", first.getShortName());

        ProjectInfo empty = projects.get(1);
        assertEquals(0, empty.getSessionCount());
        assertEquals("D:\\Empty\\Dir", empty.getRealPath());
    }

    @Test
    void sessionWorkingDirRequiresExistingSession() throws IOException {
        Files.createDirectories(projectsRoot.resolve("C--Work-demo"));

        assertThrows(NotFoundException.class, () -> data.sessionWorkingDir("C--Work-demo", "missing-session"));
    }

    @Test
    void projectWorkingDirRequiresExistingProject() {
        assertThrows(NotFoundException.class, () -> data.projectWorkingDir("C--Not-real"));
    }

    @Test
    void projectWorkingDirStillDecodesExistingEmptyProject() throws IOException {
        Files.createDirectories(projectsRoot.resolve("D--Empty-Dir"));

        assertEquals("D:\\Empty\\Dir", data.projectWorkingDir("D--Empty-Dir"));
    }

    @Test
    void pathTraversalIsRejected() {
        assertThrows(RuntimeException.class, () -> data.loadConversation("C--Work-demo", "../secret"));
        assertThrows(RuntimeException.class, () -> data.loadConversation("..", "s1"));
    }
}
