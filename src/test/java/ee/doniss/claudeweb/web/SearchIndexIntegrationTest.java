package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.service.search.SearchIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/search backed by a real Lucene index in a temp dir (other tests run with search disabled). */
@SpringBootTest
@AutoConfigureMockMvc
class SearchIndexIntegrationTest {

    static Path testHome;
    static Path indexDir;

    @Autowired
    MockMvc mvc;

    @Autowired
    SearchIndexService index;

    @DynamicPropertySource
    static void claudeProps(DynamicPropertyRegistry registry) throws IOException {
        testHome = Files.createTempDirectory("claude-home-search-it");
        indexDir = Files.createTempDirectory("claude-search-index-it");
        Path proj = testHome.resolve("projects").resolve("C--Work-demo");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve("hit.jsonl"),
                "{\"type\":\"user\",\"cwd\":\"C:/Work/demo\","
                        + "\"message\":{\"role\":\"user\",\"content\":\"please fix the parser bug\"}}\n",
                StandardCharsets.UTF_8);

        registry.add("claude.home", () -> testHome.toString());
        registry.add("claude.search.enabled", () -> "true");
        registry.add("claude.search.index-dir", () -> indexDir.toString());
        registry.add("claude.os-integration", () -> "noop");
        registry.add("claude.auto-open-browser", () -> "false");
        registry.add("claude.security.enabled", () -> "false");
    }

    @Test
    void searchAnswersFromTheIndexOnceReady() throws Exception {
        index.rebuildNow(); // deterministic instead of racing the background build

        mvc.perform(get("/api/search").param("q", "parser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].count").value(1))
                .andExpect(jsonPath("$[0].projectName").value("demo"))
                .andExpect(jsonPath("$[0].session.sessionId").value("hit"));
    }

    @Test
    void unindexableQueryFallsBackToBruteForce() throws Exception {
        // Punctuation-only queries produce no tokens — the index returns null and the scan answers.
        mvc.perform(get("/api/search").param("q", "..."))
                .andExpect(status().isOk());
    }
}
