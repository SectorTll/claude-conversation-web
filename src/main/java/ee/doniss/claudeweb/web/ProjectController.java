package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.domain.ProjectInfo;
import ee.doniss.claudeweb.domain.SessionInfo;
import ee.doniss.claudeweb.service.ClaudeDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Browsing endpoints: the list of projects and a project's sessions. */
@RestController
@RequestMapping("/api")
public class ProjectController {

    private final ClaudeDataService data;

    public ProjectController(ClaudeDataService data) {
        this.data = data;
    }

    @GetMapping("/projects")
    public List<ProjectInfo> projects() {
        return data.loadProjects();
    }

    @GetMapping("/projects/{projectId}/sessions")
    public List<SessionInfo> sessions(@PathVariable String projectId) {
        return data.loadSessions(projectId);
    }
}
