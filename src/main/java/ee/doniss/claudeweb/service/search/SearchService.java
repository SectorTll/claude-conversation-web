package ee.doniss.claudeweb.service.search;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.SearchHit;
import ee.doniss.claudeweb.service.ClaudeDataService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Global search: ask the Lucene index for candidate sessions, then verify/render each candidate
 * with the exact brute-force single-file scan (restores substring semantics and builds the
 * snippet/count). Falls back to the full brute-force scan whenever the index can't answer.
 */
@Service
public class SearchService {

    private final ClaudeDataService data;
    private final SearchIndexService index;
    private final ClaudeProperties props;

    public SearchService(ClaudeDataService data, SearchIndexService index, ClaudeProperties props) {
        this.data = data;
        this.index = index;
        this.props = props;
    }

    public List<SearchHit> search(String query, int max) {
        List<SearchIndexService.Candidate> candidates =
                index.enabled() ? index.search(query, max) : null;
        if (candidates == null) {
            return data.searchAll(query, max);
        }
        List<SearchHit> hits = new ArrayList<>();
        for (SearchIndexService.Candidate c : candidates) {
            Path dir = props.projectsRoot().resolve(c.projectId());
            Path file = dir.resolve(c.sessionId() + ".jsonl");
            if (!Files.isRegularFile(file)) {
                continue; // deleted since indexing — the watcher will drop it shortly
            }
            // Token-AND candidates can be false positives for the substring query — verify drops them.
            SearchHit hit = data.searchSessionFile(dir, file, query);
            if (hit != null) {
                hits.add(hit);
                if (hits.size() >= max) {
                    break;
                }
            }
        }
        hits.sort(Comparator.comparing(h -> h.getSession().getLastActivity(),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return hits;
    }
}
