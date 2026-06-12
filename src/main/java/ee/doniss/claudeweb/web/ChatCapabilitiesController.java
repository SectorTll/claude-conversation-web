package ee.doniss.claudeweb.web;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.service.chat.ChatEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Tells the browser which chat engine is active so the UI can adapt — the composer only offers
 * the interactive {@code default} mode when the {@code sdk} engine (which can actually pause for
 * a permission card) is driving turns.
 */
@RestController
public class ChatCapabilitiesController {

    private final ChatEngine chat;
    private final ClaudeProperties props;

    public ChatCapabilitiesController(ChatEngine chat, ClaudeProperties props) {
        this.chat = chat;
        this.props = props;
    }

    @GetMapping("/api/chat/capabilities")
    public Map<String, Object> capabilities() {
        boolean sdk = "sdk".equals(chat.engineName());
        return Map.of(
                "engine", chat.engineName(),
                "defaultPermissionMode", props.getChat().getPermissionMode(),
                "allowPerRequestPermissionMode", props.getChat().isAllowPerRequestPermissionMode(),
                // Pasted images ride the sdk protocol; the headless cli engine has no image channel.
                "images", sdk,
                "imageMaxCount", props.getChat().getImageMaxCount(),
                "imageMaxBytes", props.getChat().getImageMaxBytes());
    }
}
