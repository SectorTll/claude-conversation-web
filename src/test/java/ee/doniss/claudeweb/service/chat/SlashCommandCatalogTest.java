package ee.doniss.claudeweb.service.chat;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.SlashCommandInfo;
import ee.doniss.claudeweb.service.ClaudeDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandCatalogTest {

    @TempDir
    Path home;

    @TempDir
    Path projectCwd;

    private SlashCommandCatalog catalog;
    private String projectId;

    @BeforeEach
    void setUp() throws IOException {
        ClaudeProperties props = new ClaudeProperties();
        props.setHome(home);
        // A real project in the store whose cwd points at projectCwd, so projectWorkingDir resolves.
        projectId = "proj";
        Path dir = home.resolve("projects").resolve(projectId);
        Files.createDirectories(dir);
        String cwdJson = projectCwd.toString().replace("\\", "\\\\");
        Files.writeString(dir.resolve("s1.jsonl"),
                "{\"type\":\"user\",\"cwd\":\"" + cwdJson + "\"}\n", StandardCharsets.UTF_8);
        catalog = new SlashCommandCatalog(props, new ClaudeDataService(props, JsonMapper.builder().build()));
    }

    private void writeSkill(Path root, String dirName, String frontmatter) throws IOException {
        Path dir = root.resolve("skills").resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), frontmatter + "\n# body\n", StandardCharsets.UTF_8);
    }

    @Test
    void listsUserSkillsWithFrontmatterFields() throws IOException {
        writeSkill(home, "review", """
                ---
                name: review
                description: Review the diff
                argument-hint: "[pr-number]"
                user-invocable: true
                ---""");

        List<SlashCommandInfo> list = catalog.list(null);

        assertEquals(1, list.size());
        SlashCommandInfo c = list.get(0);
        assertEquals("review", c.name());
        assertEquals("Review the diff", c.description());
        assertEquals("[pr-number]", c.argumentHint());
        assertEquals("user", c.source());
        assertEquals("skill", c.kind());
    }

    @Test
    void skipsModelOnlySkillsAndFallsBackToTheDirectoryName() throws IOException {
        writeSkill(home, "hidden", """
                ---
                user-invocable: false
                ---""");
        writeSkill(home, "no-name", """
                ---
                description: frontmatter without a name
                ---""");

        List<SlashCommandInfo> list = catalog.list(null);

        assertEquals(1, list.size());
        assertEquals("no-name", list.get(0).name());
        assertNull(list.get(0).argumentHint());
    }

    @Test
    void listsCustomCommandsAndNamespacesSubdirectories() throws IOException {
        Path commands = home.resolve("commands");
        Files.createDirectories(commands.resolve("git"));
        Files.writeString(commands.resolve("deploy.md"), """
                ---
                description: Deploy it
                argument-hint: [env]
                ---
                body""", StandardCharsets.UTF_8);
        Files.writeString(commands.resolve("git").resolve("commit.md"), "no frontmatter", StandardCharsets.UTF_8);

        List<SlashCommandInfo> list = catalog.list(null);

        assertEquals(2, list.size());
        assertEquals("deploy", list.get(0).name());
        assertEquals("Deploy it", list.get(0).description());
        assertEquals("[env]", list.get(0).argumentHint());
        assertEquals("command", list.get(0).kind());
        assertEquals("git:commit", list.get(1).name());
        assertEquals("", list.get(1).description());
    }

    @Test
    void mergesProjectCommandsAndProjectWinsANameClash() throws IOException {
        writeSkill(home, "review", """
                ---
                name: review
                description: user-level
                ---""");
        writeSkill(projectCwd.resolve(".claude"), "review", """
                ---
                name: review
                description: project-level
                ---""");
        writeSkill(projectCwd.resolve(".claude"), "only-here", """
                ---
                name: only-here
                description: project only
                ---""");

        List<SlashCommandInfo> list = catalog.list(projectId);

        assertEquals(2, list.size());
        assertEquals("only-here", list.get(0).name());
        assertEquals("project", list.get(0).source());
        assertEquals("review", list.get(1).name());
        assertEquals("project-level", list.get(1).description());
        assertEquals("project", list.get(1).source());
    }

    @Test
    void unknownProjectStillReturnsUserCommands() throws IOException {
        writeSkill(home, "review", """
                ---
                name: review
                ---""");

        List<SlashCommandInfo> list = catalog.list("no-such-project");

        assertEquals(1, list.size());
        assertEquals("user", list.get(0).source());
    }

    @Test
    void emptyStoreYieldsAnEmptyList() {
        assertTrue(catalog.list(null).isEmpty());
    }
}
