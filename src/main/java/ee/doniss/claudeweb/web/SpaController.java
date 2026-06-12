package ee.doniss.claudeweb.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback: forward unknown single-segment, extension-less GET paths to index.html so a
 * bookmarked/refreshed deep link still loads the app. {@code /api/**}, {@code /sse/**} and asset
 * paths (which contain a dot) are unaffected.
 */
@Controller
public class SpaController {

    @GetMapping("/{path:[^.]*}")
    public String forward() {
        return "forward:/index.html";
    }
}
