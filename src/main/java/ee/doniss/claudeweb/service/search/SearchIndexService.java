package ee.doniss.claudeweb.service.search;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.service.ClaudeDataService;
import ee.doniss.claudeweb.service.live.SessionWatchService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Lucene full-text index over the session store — one document per session file. Built in the
 * background on startup (mtime reconcile), kept fresh by the file watcher's {@code SessionsChanged}
 * events, and queried by {@link SearchService}. Any open/corruption failure wipes the index dir and
 * rebuilds — the store on disk is always the source of truth.
 */
@Component
public class SearchIndexService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);

    private static final String F_SESSION = "sessionId";
    private static final String F_PROJECT = "projectId";
    private static final String F_BODY = "body";
    private static final String F_MTIME = "mtime";
    private static final String F_MTIME_SORT = "mtimeSort";

    /** A matching session file, to be verified/rendered by the brute-force single-file scan. */
    public record Candidate(String projectId, String sessionId) {
    }

    private final ClaudeProperties props;
    private final ClaudeDataService data;
    private final Analyzer analyzer = new StandardAnalyzer();
    private final ExecutorService indexer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "claude-search-index");
        t.setDaemon(true);
        return t;
    });

    private volatile IndexWriter writer;
    private volatile SearcherManager searcherManager;
    private volatile boolean ready;

    public SearchIndexService(ClaudeProperties props, ClaudeDataService data) {
        this.props = props;
        this.data = data;
    }

    public boolean enabled() {
        return props.getSearch().isEnabled();
    }

    /** True once the initial reconcile finished — before that, search falls back to brute force. */
    public boolean isReady() {
        return ready && writer != null;
    }

    @PostConstruct
    void start() {
        if (!enabled()) {
            return;
        }
        indexer.execute(() -> {
            try {
                openWriter();
                reconcile();
                ready = true;
            } catch (Exception e) {
                log.warn("search index unavailable (brute-force search stays active): {}", e.toString());
            }
        });
    }

    @PreDestroy
    public void stop() {
        // Let queued index work finish BEFORE closing the writer — closing mid-commit corrupts the
        // shutdown with "prepareCommit was already called" instead of a clean close.
        indexer.shutdown();
        try {
            if (!indexer.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                indexer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            indexer.shutdownNow();
        }
        closeQuietly();
    }

    /** Synchronous open+reconcile — used by tests. */
    public void rebuildNow() {
        openWriter();
        reconcile();
        ready = true;
    }

    /** The file watcher saw session files change — refresh just those documents. */
    @EventListener
    public void onSessionsChanged(SessionWatchService.SessionsChanged event) {
        if (!enabled()) {
            return;
        }
        indexer.execute(() -> {
            if (writer == null) {
                return;
            }
            try {
                for (SessionWatchService.ChangedSession change : event.changes()) {
                    Path file = props.projectsRoot().resolve(change.projectId())
                            .resolve(change.sessionId() + ".jsonl");
                    if (Files.isRegularFile(file)) {
                        updateDocument(change.projectId(), change.sessionId(), file);
                    } else {
                        writer.deleteDocuments(new Term(F_SESSION, change.sessionId()));
                    }
                }
                writer.commit();
                searcherManager.maybeRefresh();
            } catch (Exception e) {
                log.warn("incremental index update failed: {}", e.toString());
            }
        });
    }

    /**
     * Index candidates for {@code query} (word/prefix matching, newest first), or null when the
     * index can't answer (disabled, not ready, or an unusable query) — callers then fall back.
     */
    public List<Candidate> search(String query, int max) {
        if (!isReady() || query == null || query.isBlank()) {
            return null;
        }
        Query q = buildQuery(query);
        if (q == null) {
            return null;
        }
        try {
            searcherManager.maybeRefreshBlocking();
            IndexSearcher searcher = searcherManager.acquire();
            try {
                TopDocs top = searcher.search(q, Math.max(1, max),
                        new Sort(new SortField(F_MTIME_SORT, SortField.Type.LONG, true)));
                List<Candidate> out = new ArrayList<>();
                StoredFields stored = searcher.storedFields();
                for (ScoreDoc sd : top.scoreDocs) {
                    Document doc = stored.document(sd.doc);
                    out.add(new Candidate(doc.get(F_PROJECT), doc.get(F_SESSION)));
                }
                return out;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (Exception e) {
            log.warn("index search failed — falling back to scan: {}", e.toString());
            return null;
        }
    }

    // ----------------------------------------------------------------- internals

    /** AND of analyzed terms, the last one as a prefix; null when no terms survive analysis. */
    private Query buildQuery(String query) {
        List<String> terms = analyze(query);
        if (terms.isEmpty()) {
            return null;
        }
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (int i = 0; i < terms.size(); i++) {
            Term term = new Term(F_BODY, terms.get(i));
            b.add(i == terms.size() - 1 ? new PrefixQuery(term) : new TermQuery(term),
                    BooleanClause.Occur.MUST);
        }
        return b.build();
    }

    private List<String> analyze(String query) {
        List<String> terms = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream(F_BODY, query.toLowerCase(Locale.ROOT))) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                terms.add(attr.toString());
            }
            ts.end();
        } catch (IOException e) {
            return List.of();
        }
        return terms;
    }

    private synchronized void openWriter() {
        if (writer != null) {
            return;
        }
        Path dir = props.resolvedSearchIndexDir();
        try {
            writer = openAt(dir);
        } catch (Exception first) {
            // Corrupt / incompatible index — wipe and start over (the .jsonl store is authoritative).
            log.warn("search index at {} unusable ({}) — rebuilding from scratch", dir, first.toString());
            deleteRecursively(dir);
            try {
                writer = openAt(dir);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        try {
            searcherManager = new SearcherManager(writer, null);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private IndexWriter openAt(Path dir) throws IOException {
        Files.createDirectories(dir);
        IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        return new IndexWriter(FSDirectory.open(dir), config);
    }

    /** Bring the index in line with the store: index new/changed files, drop vanished ones. */
    private void reconcile() {
        try {
            Map<String, Long> indexedMtimes = readIndexedMtimes();
            Set<String> liveSessionIds = new HashSet<>();
            Path root = props.projectsRoot();
            if (Files.isDirectory(root)) {
                try (Stream<Path> dirs = Files.list(root)) {
                    for (Path dir : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                        String projectId = dir.getFileName().toString();
                        for (Path file : data.listSessionFiles(dir)) {
                            String sessionId = fileNameNoExt(file);
                            liveSessionIds.add(sessionId);
                            long mtime = Files.getLastModifiedTime(file).toMillis();
                            Long indexed = indexedMtimes.get(sessionId);
                            if (indexed == null || indexed != mtime) {
                                updateDocument(projectId, sessionId, file);
                            }
                        }
                    }
                }
            }
            for (String sessionId : indexedMtimes.keySet()) {
                if (!liveSessionIds.contains(sessionId)) {
                    writer.deleteDocuments(new Term(F_SESSION, sessionId));
                }
            }
            writer.commit();
            searcherManager.maybeRefresh();
            log.info("search index ready: {} sessions", liveSessionIds.size());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Map<String, Long> readIndexedMtimes() throws IOException {
        Map<String, Long> out = new HashMap<>();
        searcherManager.maybeRefreshBlocking();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            int total = searcher.getIndexReader().maxDoc();
            if (total == 0) {
                return out;
            }
            TopDocs all = searcher.search(new MatchAllDocsQuery(), total);
            StoredFields stored = searcher.storedFields();
            for (ScoreDoc sd : all.scoreDocs) {
                Document doc = stored.document(sd.doc);
                String sid = doc.get(F_SESSION);
                String mtime = doc.get(F_MTIME);
                if (sid != null && mtime != null) {
                    out.put(sid, Long.parseLong(mtime));
                }
            }
            return out;
        } finally {
            searcherManager.release(searcher);
        }
    }

    private void updateDocument(String projectId, String sessionId, Path file) {
        try {
            String body = data.extractSessionText(file);
            long mtime = Files.getLastModifiedTime(file).toMillis();
            Document doc = new Document();
            doc.add(new StringField(F_SESSION, sessionId, Field.Store.YES));
            doc.add(new StringField(F_PROJECT, projectId, Field.Store.YES));
            doc.add(new TextField(F_BODY, body, Field.Store.NO));
            doc.add(new StoredField(F_MTIME, Long.toString(mtime)));
            doc.add(new NumericDocValuesField(F_MTIME_SORT, mtime));
            writer.updateDocument(new Term(F_SESSION, sessionId), doc);
        } catch (Exception e) {
            log.debug("could not index {}: {}", file, e.toString());
        }
    }

    private void closeQuietly() {
        try {
            if (searcherManager != null) {
                searcherManager.close();
            }
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignore) {
            // shutting down anyway
        } finally {
            searcherManager = null;
            writer = null;
            ready = false;
        }
    }

    private static String fileNameNoExt(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(0, dot) : n;
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignore) {
                    // best effort — a locked file just means the open below fails and logs
                }
            });
        } catch (IOException ignore) {
            // same
        }
    }
}
