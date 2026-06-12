package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.os.OsIntegration;
import ee.doniss.claudeweb.service.ClaudeDataService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * OS actions: launch / fork / new CLI sessions, open folder, reveal file. On platforms without OS
 * integration these return HTTP 501 (via {@link ee.doniss.claudeweb.support.OsActionUnsupportedException}).
 */
@RestController
@RequestMapping("/api/actions")
public class ActionController {

    private final ClaudeDataService data;
    private final OsIntegration os;

    public ActionController(ClaudeDataService data, OsIntegration os) {
        this.data = data;
        this.os = os;
    }

    public record ActionRequest(String projectId, String sessionId, Boolean fork) {
    }

    public record CommandResponse(String command) {
    }

    public record Capabilities(boolean actions, boolean scheduling) {
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(os.isSupported(), os.supportsScheduling());
    }

    @PostMapping("/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(@RequestBody ActionRequest r) {
        os.resume(r.sessionId(), data.sessionWorkingDir(r.projectId(), r.sessionId()),
                Boolean.TRUE.equals(r.fork()));
    }

    @PostMapping("/new-session")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void newSession(@RequestBody ActionRequest r) {
        os.newSession(data.projectWorkingDir(r.projectId()));
    }

    @PostMapping("/open-folder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void openFolder(@RequestBody ActionRequest r) {
        String path = r.sessionId() != null && !r.sessionId().isBlank()
                ? data.sessionWorkingDir(r.projectId(), r.sessionId())
                : data.projectWorkingDir(r.projectId());
        os.openFolder(path);
    }

    @PostMapping("/reveal-file")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revealFile(@RequestBody ActionRequest r) {
        os.revealFile(data.sessionPath(r.projectId(), r.sessionId()).toString());
    }

    @GetMapping("/resume-command")
    public CommandResponse resumeCommand(@RequestParam String sessionId,
                                         @RequestParam(defaultValue = "false") boolean fork) {
        return new CommandResponse(os.resumeCommand(sessionId, fork));
    }
}
