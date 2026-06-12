package ee.doniss.claudeweb.service.search;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.SearchHit;
import ee.doniss.claudeweb.service.ClaudeDataService;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path home;

    @TempDir
    Path indexDir;

    private ClaudeProperties props;
    private ClaudeDataService data;
    private SearchIndexService index;
    private SearchService search;

    @BeforeEach
    void setUp() {
        props = new ClaudeProperties();
        props.setHome(home);
        props.getSearch().setIndexDir(indexDir);
        data = new ClaudeDataService(props, MAPPER);
        index = new SearchIndexService(props, data);
        search = new SearchService(data, index, props);
    }

    @AfterEach
    void tearDown() {
        index.stop();
    }

    private void writeSession(String sessionId, String text) throws IOException {
        Path dir = home.resolve("projects").resolve("C--Work-demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(sessionId + ".jsonl"),
                "{\"type\":\"user\",\"cwd\":\"C:/Work/demo\",\"message\":{\"role\":\"user\",\"content\":\""
                        + text + "\"}}\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void fallsBackToBruteForceWhileIndexNotReady() throws IOException {
        writeSession("s1", "find the parser bug");

        List<SearchHit> hits = search.search("parser", 10);
        assertEquals(1, hits.size(), "brute force answers before the index is built");
        assertEquals(1, hits.get(0).getCount());
    }

    @Test
    void fallsBackWhenIndexDisabled() throws IOException {
        props.getSearch().setEnabled(false);
        writeSession("s1", "find the parser bug");

        assertEquals(1, search.search("parser", 10).size());
    }

    @Test
    void indexedSearchVerifiesCandidatesAndBuildsSnippets() throws IOException {
        writeSession("s1", "please fix the parser bug today");
        writeSession("s2", "nothing relevant here");
        index.rebuildNow();

        List<SearchHit> hits = search.search("parser", 10);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).getSnippet().toLowerCase().contains("parser"), "snippet from the verify scan");
        assertEquals("demo", hits.get(0).getProjectName());
    }

    @Test
    void tokenAndFalsePositivesAreDroppedByVerification() throws IOException {
        // Both words occur, but never as the contiguous substring the user typed.
        writeSession("s1", "the parser handles a bug list");
        index.rebuildNow();

        assertTrue(search.search("parser bug today", 10).isEmpty(),
                "AND-of-terms candidate fails the exact-substring verify");
    }
}
