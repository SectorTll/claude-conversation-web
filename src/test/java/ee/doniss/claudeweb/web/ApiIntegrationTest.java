package ee.doniss.claudeweb.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    static Path testHome;

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void claudeProps(DynamicPropertyRegistry registry) throws IOException {
        testHome = Files.createTempDirectory("claude-home-it");
        Path proj = testHome.resolve("projects").resolve("C--Work-demo");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve("sess-1234.jsonl"), String.join("\n",
                "{\"type\":\"user\",\"timestamp\":\"2026-06-01T10:00:00Z\",\"cwd\":\"C:/Work/demo\","
                        + "\"gitBranch\":\"main\",\"version\":\"2.1.168\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"find the bug in parser\"}}",
                "{\"type\":\"assistant\",\"timestamp\":\"2026-06-01T10:00:05Z\","
                        + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Looking now\"}]}}",
                "{\"type\":\"ai-title\",\"aiTitle\":\"Parser bug hunt\"}") + "\n",
                StandardCharsets.UTF_8);

        registry.add("claude.home", () -> testHome.toString());
        registry.add("claude.os-integration", () -> "noop");
        registry.add("claude.auto-open-browser", () -> "false");
        // These tests exercise plain API behavior, not the security layer — turn the wall off.
        registry.add("claude.security.enabled", () -> "false");
    }

    @Test
    void listsProjects() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value("C--Work-demo"))
                .andExpect(jsonPath("$[0].shortName").value("demo"))
                .andExpect(jsonPath("$[0].sessionCount").value(1));
    }

    @Test
    void listsSessionsWithAiTitle() throws Exception {
        mvc.perform(get("/api/projects/C--Work-demo/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value("sess-1234"))
                .andExpect(jsonPath("$[0].title").value("Parser bug hunt"));
    }

    @Test
    void loadsConversation() throws Exception {
        mvc.perform(get("/api/projects/C--Work-demo/sessions/sess-1234/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].text").value("find the bug in parser"))
                .andExpect(jsonPath("$[1].text").value("Looking now"));
    }

    @Test
    void globalSearchFindsHit() throws Exception {
        mvc.perform(get("/api/search").param("q", "parser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").value(1))
                .andExpect(jsonPath("$[0].projectName").value("demo"));
    }

    @Test
    void renameThenSeeNewTitle() throws Exception {
        mvc.perform(post("/api/projects/C--Work-demo/sessions/sess-1234/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed Session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed Session"))
                .andExpect(jsonPath("$.hasCustomTitle").value(true));

        mvc.perform(get("/api/projects/C--Work-demo/sessions/sess-1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed Session"));
    }

    @Test
    void missingSessionReturns404() throws Exception {
        mvc.perform(get("/api/projects/C--Work-demo/sessions/does-not-exist/messages"))
                .andExpect(status().isNotFound());
    }

    @Test
    void liveSnapshotOk() throws Exception {
        mvc.perform(get("/api/live"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void capabilitiesReportNoopUnsupported() throws Exception {
        mvc.perform(get("/api/actions/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions").value(false))
                .andExpect(jsonPath("$.scheduling").value(false));
    }

    @Test
    void resumeOnNoopReturns501() throws Exception {
        mvc.perform(post("/api/actions/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"C--Work-demo\",\"sessionId\":\"sess-1234\",\"fork\":false}"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void resumeWithMissingSessionReturns404BeforeOsIntegration() throws Exception {
        mvc.perform(post("/api/actions/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"C--Work-demo\",\"sessionId\":\"missing-session\",\"fork\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void newSessionWithMissingProjectReturns404BeforeOsIntegration() throws Exception {
        mvc.perform(post("/api/actions/new-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"C--Not-real\"}"))
                .andExpect(status().isNotFound());
    }
}
