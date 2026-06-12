package ee.doniss.claudeweb.service.notify;

import ee.doniss.claudeweb.config.ClaudeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Telegram bot delivery: one {@code sendMessage} per WAITING event. Configure
 * {@code claude.telegram.bot-token} (via {@code CLAUDE_TELEGRAM_BOT_TOKEN}) + {@code chat-id} and
 * set {@code enabled: true}. A deep link is appended when {@code claude.push.public-base-url} is
 * set (Telegram can't resolve in-app relative paths).
 */
@Component
public class TelegramSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramSender.class);

    /** Minimal HTTP seam so tests can capture requests without a real client. */
    record HttpResult(int statusCode, String body) {
    }

    private final ClaudeProperties props;
    private final ObjectMapper mapper;
    private final TelegramCallbacks callbacks;
    private final Function<HttpRequest, HttpResult> http;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramSender(ClaudeProperties props, ObjectMapper mapper, TelegramCallbacks callbacks) {
        this(props, mapper, callbacks, defaultHttp());
    }

    TelegramSender(ClaudeProperties props, ObjectMapper mapper, TelegramCallbacks callbacks,
                   Function<HttpRequest, HttpResult> http) {
        this.props = props;
        this.mapper = mapper;
        this.callbacks = callbacks;
        this.http = http;
    }

    @Override
    public boolean enabled() {
        ClaudeProperties.Telegram t = props.getTelegram();
        return t.isEnabled() && !t.getBotToken().isBlank() && !t.getChatId().isBlank();
    }

    @Override
    public void send(WaitingEvent event) {
        try {
            HttpResult response = sendForResult(event);
            if (response.statusCode() / 100 != 2) {
                log.warn("telegram sendMessage returned {}: {}", response.statusCode(), response.body());
            }
        } catch (RuntimeException e) {
            log.warn("telegram sendMessage failed: {}", e.toString());
        }
    }

    /**
     * Posts the {@code sendMessage} and returns the raw Telegram API result (or throws on a transport
     * failure). Unlike {@link #send} this does NOT swallow the outcome — the settings dialog's
     * "send test" path needs to surface a bad token / chat id back to the user.
     */
    public HttpResult sendForResult(WaitingEvent event) {
        StringBuilder text = new StringBuilder("⏳ ").append(event.title()).append('\n').append(event.body());
        String base = props.getPush().getPublicBaseUrl();
        if (base != null && !base.isBlank()) {
            text.append('\n').append(base.replaceAll("/$", "")).append("/#").append(event.path());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", props.getTelegram().getChatId());
        body.put("text", text.toString());
        List<List<Map<String, String>>> keyboard = keyboardFor(event);
        if (!keyboard.isEmpty()) {
            body.put("reply_markup", Map.of("inline_keyboard", keyboard));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + props.getTelegram().getBotToken() + "/sendMessage"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        return http.apply(request);
    }

    /**
     * Inline buttons so the card can be answered straight from Telegram: Allow/Deny for a
     * permission, one button per option for a single-question card. Buttons carry short registry
     * tokens (Telegram caps {@code callback_data} at 64 bytes); {@link TelegramUpdatesPoller}
     * resolves them back into engine calls.
     */
    private List<List<Map<String, String>>> keyboardFor(WaitingEvent event) {
        if (event.requestId() == null) {
            return List.of();
        }
        List<List<Map<String, String>>> rows = new java.util.ArrayList<>();
        if ("permission".equals(event.kind())) {
            rows.add(List.of(
                    button("✅ Allow", new TelegramCallbacks.Action(
                            event.sessionId(), event.requestId(), "permission", true, null)),
                    button("❌ Deny", new TelegramCallbacks.Action(
                            event.sessionId(), event.requestId(), "permission", false, null))));
        } else if ("question".equals(event.kind())) {
            for (String option : event.options()) {
                rows.add(List.of(button(truncateLabel(option), new TelegramCallbacks.Action(
                        event.sessionId(), event.requestId(), "question", false, option))));
            }
        }
        return rows;
    }

    private Map<String, String> button(String label, TelegramCallbacks.Action action) {
        return Map.of("text", label, "callback_data", callbacks.register(action));
    }

    private static String truncateLabel(String label) {
        return label.length() <= 60 ? label : label.substring(0, 59) + "…";
    }

    private static Function<HttpRequest, HttpResult> defaultHttp() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return request -> {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return new HttpResult(response.statusCode(), response.body());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
