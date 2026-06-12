package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.domain.SearchHit;
import ee.doniss.claudeweb.service.search.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Global (all-projects) search — index-accelerated with a brute-force fallback. */
@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam("q") String query,
                                  @RequestParam(value = "max", defaultValue = "400") int max) {
        return search.search(query, max);
    }
}
