package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.domain.ChatMessage;
import ee.doniss.claudeweb.domain.SessionInfo;
import ee.doniss.claudeweb.service.ClaudeDataService;
import ee.doniss.claudeweb.web.dto.BranchRequest;
import ee.doniss.claudeweb.web.dto.RenameRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** A single session: metadata, its conversation, and renaming. */
@RestController
@RequestMapping("/api/projects/{projectId}/sessions/{sessionId}")
public class SessionController {

    private final ClaudeDataService data;

    public SessionController(ClaudeDataService data) {
        this.data = data;
    }

    @GetMapping
    public SessionInfo get(@PathVariable String projectId, @PathVariable String sessionId) {
        return data.getSession(projectId, sessionId);
    }

    @GetMapping("/messages")
    public List<ChatMessage> messages(@PathVariable String projectId, @PathVariable String sessionId) {
        return data.loadConversation(projectId, sessionId);
    }

    @PostMapping("/rename")
    public SessionInfo rename(@PathVariable String projectId, @PathVariable String sessionId,
                              @RequestBody RenameRequest request) {
        return data.renameSession(projectId, sessionId, request == null ? null : request.title());
    }

    /** Fork this session at a message: a new session containing the prefix up to that message. */
    @PostMapping("/branch")
    public SessionInfo branch(@PathVariable String projectId, @PathVariable String sessionId,
                              @RequestBody BranchRequest request) {
        return data.branchSession(projectId, sessionId, request == null ? null : request.uuid());
    }
}
