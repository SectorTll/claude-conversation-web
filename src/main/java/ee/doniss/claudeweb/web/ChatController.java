package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.service.chat.ChatEngine;
import ee.doniss.claudeweb.support.BadRequestException;
import ee.doniss.claudeweb.web.dto.ChatRequest;
import ee.doniss.claudeweb.web.dto.PermissionDecisionRequest;
import ee.doniss.claudeweb.web.dto.QuestionAnswerRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;
import java.util.Map;

/**
 * In-browser chat: drives the {@code claude} CLI headlessly for one turn and streams the reply back
 * as NDJSON, one JSON event per line ({@code session} | {@code text-delta} | {@code tool} |
 * {@code question} | {@code done} | {@code error}). The CLI persists the canonical {@code .jsonl}
 * itself, so the browse
 * views pick up the new messages on their next load.
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ChatController {

    private static final String NDJSON = "application/x-ndjson";

    private final ChatEngine chat;

    public ChatController(ChatEngine chat) {
        this.chat = chat;
    }

    /** Send a message to an existing session and stream the reply. */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = NDJSON)
    public ResponseBodyEmitter send(@PathVariable String projectId, @PathVariable String sessionId,
                                    @RequestBody ChatRequest req) {
        return chat.sendToSession(projectId, sessionId, requireContent(req), modeOf(req), modelOf(req),
                attachmentsOf(req));
    }

    /** Create a new session and send its first message. The first NDJSON line is {@code {type:session,sessionId}}. */
    @PostMapping(value = "/sessions", produces = NDJSON)
    public ResponseBodyEmitter create(@PathVariable String projectId, @RequestBody ChatRequest req) {
        return chat.createSessionAndSend(projectId, requireContent(req), modeOf(req), modelOf(req),
                attachmentsOf(req));
    }

    /** Stop an in-flight turn (kills/interrupts the underlying process). Idempotent. */
    @PostMapping("/sessions/{sessionId}/stop")
    public ResponseEntity<Void> stop(@PathVariable String projectId, @PathVariable String sessionId) {
        chat.cancel(sessionId);
        return ResponseEntity.noContent().build();
    }

    /** Allow/deny a pending tool-permission card of an in-flight turn ({@code sdk} engine). */
    @PostMapping("/sessions/{sessionId}/permission")
    public ResponseEntity<Void> permission(@PathVariable String projectId, @PathVariable String sessionId,
                                           @RequestBody PermissionDecisionRequest req) {
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BadRequestException("requestId is required");
        }
        boolean allow = "allow".equals(req.behavior());
        if (!allow && !"deny".equals(req.behavior())) {
            throw new BadRequestException("behavior must be allow or deny");
        }
        chat.decide(sessionId, req.requestId(), allow, req.message(), req.always());
        return ResponseEntity.noContent().build();
    }

    /**
     * The unanswered permission/question cards of an in-flight turn — lets a reloaded page or a
     * second tab render cards it never saw streamed and answer them via the endpoints above.
     */
    @GetMapping("/sessions/{sessionId}/pending")
    public List<Map<String, Object>> pending(@PathVariable String projectId, @PathVariable String sessionId) {
        return chat.pendingAsks(sessionId);
    }

    /** Answer a pending in-turn {@code AskUserQuestion} card ({@code sdk} engine). */
    @PostMapping("/sessions/{sessionId}/answer")
    public ResponseEntity<Void> answer(@PathVariable String projectId, @PathVariable String sessionId,
                                       @RequestBody QuestionAnswerRequest req) {
        if (req == null || req.requestId() == null || req.requestId().isBlank()) {
            throw new BadRequestException("requestId is required");
        }
        if (req.answers() == null || req.answers().isEmpty()) {
            throw new BadRequestException("answers are required");
        }
        chat.answer(sessionId, req.requestId(), req.answers());
        return ResponseEntity.noContent().build();
    }

    /** Text or at least one attachment — a pasted screenshot needs no comment. */
    private static String requireContent(ChatRequest req) {
        boolean hasText = req != null && req.text() != null && !req.text().isBlank();
        boolean hasImages = req != null && req.attachments() != null && !req.attachments().isEmpty();
        if (!hasText && !hasImages) {
            throw new BadRequestException("text or an image attachment is required");
        }
        return hasText ? req.text() : "";
    }

    private static String modeOf(ChatRequest req) {
        return req == null ? null : req.permissionMode();
    }

    private static String modelOf(ChatRequest req) {
        return req == null ? null : req.model();
    }

    private static java.util.List<ChatRequest.Attachment> attachmentsOf(ChatRequest req) {
        return req == null ? null : req.attachments();
    }
}
