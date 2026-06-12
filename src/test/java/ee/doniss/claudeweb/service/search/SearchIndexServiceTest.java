package ee.doniss.claudeweb.service.search;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.service.ClaudeDataService;
import ee.doniss.claudeweb.service.live.SessionWatchService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchIndexServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path home;

    @TempDir
    Path indexDir;

    private ClaudeProperties props;
    private SearchIndexService index;
    private Path projectsRoot;

    @BeforeEach
    void setUp() {
        props = new ClaudeProperties();
        props.setHome(home);
        props.getSearch().setIndexDir(indexDir);
        index = new SearchIndexService(props, new ClaudeDataService(props, MAPPER));
        projectsRoot = home.resolve("projects");
    }

    @AfterEach
    void tearDown() {
        index.stop();
    }

    private Path writeSession(String projectFolder, String sessionId, String text) throws IOException {
        Path dir = projectsRoot.resolve(projectFolder);
        Files.createDirectories(dir);
        Path file = dir.resolve(sessionId + ".jsonl");
        Files.writeString(file,
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}}\n",
                StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void findsWordsAndPrefixesCaseInsensitively() throws IOException {
        writeSession("C--Work-demo", "s1", "please fix the Parser bug today");
        writeSession("C--Work-demo", "s2", "unrelated chatter");
        index.rebuildNow();

        assertEquals(List.of(new SearchIndexService.Candidate("C--Work-demo", "s1")),
                index.search("parser", 10), "case-insensitive word match");
        assertEquals(1, index.search("pars", 10).size(), "last token matches as a prefix");
        assertEquals(1, index.search("parser bug", 10).size(), "multi-word = AND of terms");
        assertTrue(index.search("parser missing", 10).isEmpty(), "AND semantics drop non-matches");
    }

    @Test
    void notReadyReturnsNullForFallback() {
        assertNull(index.search("anything", 10), "before rebuild the index can't answer");
    }

    @Test
    void unchangedFilesAreSkippedOnReconcileAndChangesAreApplied() throws Exception {
        Path file = writeSession("C--Work-demo", "s1", "alpha topic");
        index.rebuildNow();
        assertEquals(1, index.search("alpha", 10).size());

        // Change the content (bump mtime so the reconcile notices it).
        Files.writeString(file,
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"beta topic\"}}\n",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 5_000));
        index.rebuildNow();

        assertTrue(index.search("alpha", 10).isEmpty(), "stale content replaced");
        assertEquals(1, index.search("beta", 10).size());
    }

    @Test
    void watcherEventUpdatesAndDeletesDocuments() throws Exception {
        Path file = writeSession("C--Work-demo", "s1", "gamma content");
        index.rebuildNow();
        assertEquals(1, index.search("gamma", 10).size());

        Files.delete(file);
        index.onSessionsChanged(new SessionWatchService.SessionsChanged(
                List.of(new SessionWatchService.ChangedSession("C--Work-demo", "s1"))));
        awaitEmpty("gamma");

        writeSession("C--Work-demo", "s2", "delta content");
        index.onSessionsChanged(new SessionWatchService.SessionsChanged(
                List.of(new SessionWatchService.ChangedSession("C--Work-demo", "s2"))));
        awaitHit("delta");
    }

    @Test
    void corruptIndexIsWipedAndRebuilt() throws Exception {
        writeSession("C--Work-demo", "s1", "epsilon content");
        index.rebuildNow();
        index.stop();

        // Trash the index directory, then start a fresh service over it.
        try (var walk = Files.walk(indexDir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Files.writeString(p, "garbage", StandardCharsets.UTF_8);
            }
        }
        SearchIndexService fresh = new SearchIndexService(props, new ClaudeDataService(props, MAPPER));
        try {
            fresh.rebuildNow();
            assertEquals(1, fresh.search("epsilon", 10).size(), "rebuilt from the store after corruption");
        } finally {
            fresh.stop();
        }
    }

    private void awaitHit(String term) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (true) {
            List<SearchIndexService.Candidate> hits = index.search(term, 10);
            if (hits != null && !hits.isEmpty()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("no hit for " + term);
            }
            Thread.sleep(20);
        }
    }

    private void awaitEmpty(String term) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (true) {
            List<SearchIndexService.Candidate> hits = index.search(term, 10);
            if (hits != null && hits.isEmpty()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(term + " still indexed");
            }
            Thread.sleep(20);
        }
    }
}
