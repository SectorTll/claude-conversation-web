package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.domain.SlashCommandInfo;
import ee.doniss.claudeweb.service.ClaudeDataService;
import ee.doniss.claudeweb.service.chat.CwdFileLister;
import ee.doniss.claudeweb.service.chat.SlashCommandCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The composer's autocomplete sources: slash commands (user-invocable skills + custom commands
 * from the Claude home, merged with the selected project's {@code .claude} directory) and the
 * {@code @}-mention file listing of the chat target's working directory.
 */
@RestController
public class SlashCommandController {

    private final SlashCommandCatalog catalog;
    private final CwdFileLister files;
    private final ClaudeDataService data;

    public SlashCommandController(SlashCommandCatalog catalog, CwdFileLister files, ClaudeDataService data) {
        this.catalog = catalog;
        this.files = files;
        this.data = data;
    }

    @GetMapping("/api/chat/commands")
    public List<SlashCommandInfo> commands(@RequestParam(name = "projectId", required = false) String projectId) {
        return catalog.list(projectId);
    }

    /** Files under the chat target's cwd (session's, else the project's) for @-mentions. */
    @GetMapping("/api/chat/files")
    public Map<String, Object> cwdFiles(@RequestParam(name = "projectId") String projectId,
                                        @RequestParam(name = "sessionId", required = false) String sessionId) {
        String cwd = sessionId == null || sessionId.isBlank()
                ? data.projectWorkingDir(projectId)
                : data.sessionWorkingDir(projectId, sessionId);
        List<String> list;
        try {
            list = files.list(Path.of(cwd));
        } catch (InvalidPathException e) {
            list = List.of(); // undecodable cwd — autocomplete just stays empty
        }
        return Map.of("cwd", cwd, "files", list, "truncated", list.size() >= CwdFileLister.MAX_FILES);
    }
}
