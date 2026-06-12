package ee.doniss.claudeweb.service.notify;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.service.chat.ChatEngine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
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
 * The receive side of Telegram notifications: long-polls {@code getUpdates} for
 * {@code callback_query} events (the inline buttons {@link TelegramSender} attaches) and routes
 * them into the chat engine — Allow/Deny for permissions, the picked option for questions. This is
 * what makes a phone ping ANSWERABLE without opening the browser.
 *
 * <p>SECURITY: only callbacks arriving in the configured {@code chat-id} are honoured; anything
 * else is dropped silently. Tokens are single-use and expire with the registry. The poller thread
 * is a daemon that idles (no HTTP at all) while Telegram is disabled — the in-app settings dialog
 * can enable it live, no restart needed.
 */
@Component
public class TelegramUpdatesPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdatesPoller.class);
    private static final int POLL_SECONDS = 25;
    private static final long DISABLED_SLEEP_MS = 5_000;
    private static final long ERROR_SLEEP_MS = 10_000;

    private final ClaudeProperties props;
    private final ObjectMapper mapper;
    private final TelegramCallbacks callbacks;
    private final ChatEngine chat;
    private final Function<HttpRequest, TelegramSender.HttpResult> http;

    private volatile boolean running = true;
    private Thread thread;
    private long offset;

    @org.springframework.beans.factory.annotation.Autowired
    public TelegramUpdatesPoller(ClaudeProperties props, ObjectMapper mapper,
                                 TelegramCallbacks callbacks, ChatEngine chat) {
        this(props, mapper, callbacks, chat, defaultHttp());
    }

    TelegramUpdatesPoller(ClaudeProperties props, ObjectMapper mapper, TelegramCallbacks callbacks,
                          ChatEngine chat, Function<HttpRequest, TelegramSender.HttpResult> http) {
        this.props = props;
        this.mapper = mapper;
        this.callbacks = callbacks;
        this.chat = chat;
        this.http = http;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        thread = new Thread(this::loop, "claude-telegram-poll");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                if (!configured()) {
                    Thread.sleep(DISABLED_SLEEP_MS);
                    continue;
                }
                pollOnce();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                log.debug("telegram poll failed: {}", e.toString());
                try {
                    Thread.sleep(ERROR_SLEEP_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private boolean configured() {
        ClaudeProperties.Telegram t = props.getTelegram();
        return t.isEnabled() && !t.getBotToken().isBlank() && !t.getChatId().isBlank();
    }

    /** One getUpdates round (the long poll blocks server-side up to {@value POLL_SECONDS}s). */
    void pollOnce() {
        TelegramSender.HttpResult res = http.apply(post("getUpdates", Map.of(
                "timeout", POLL_SECONDS,
                "offset", offset,
                "allowed_updates", List.of("callback_query"))));
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("getUpdates returned " + res.statusCode());
        }
        JsonNode root = mapper.readTree(res.body());
        for (JsonNode update : root.path("result")) {
            offset = Math.max(offset, update.path("update_id").asLong() + 1);
            JsonNode cq = update.path("callback_query");
            if (!cq.isMissingNode()) {
                handleCallback(cq);
            }
        }
    }

    private void handleCallback(JsonNode cq) {
        String chatId = cq.path("message").path("chat").path("id").asText();
        if (!chatId.equals(props.getTelegram().getChatId())) {
            return; // not our configured chat — drop silently, no oracle for strangers
        }
        String callbackId = cq.path("id").asText();
        TelegramCallbacks.Action action = callbacks.take(cq.path("data").asText());
        if (action == null) {
            answerCallback(callbackId, "Expired — open the app to decide");
            return;
        }
        String note;
        try {
            if ("permission".equals(action.kind())) {
                chat.decide(action.sessionId(), action.requestId(), action.allow(), null, false);
                note = action.allow() ? "✅ Allowed" : "❌ Denied";
            } else {
                chat.answer(action.sessionId(), action.requestId(), List.of(List.of(action.option())));
                note = "✅ " + action.option();
            }
            stripButtons(cq);
        } catch (RuntimeException e) {
            note = "Failed: " + e.getMessage(); // already decided / turn gone — tell the phone why
        }
        answerCallback(callbackId, note);
    }

    /** The toast on the phone acknowledging the tap. */
    private void answerCallback(String callbackId, String text) {
        try {
            http.apply(post("answerCallbackQuery", Map.of(
                    "callback_query_id", callbackId,
                    "text", text)));
        } catch (RuntimeException e) {
            log.debug("answerCallbackQuery failed: {}", e.toString());
        }
    }

    /** Remove the buttons from the answered message so a stale card can't be tapped twice. */
    private void stripButtons(JsonNode cq) {
        JsonNode message = cq.path("message");
        if (message.isMissingNode()) {
            return;
        }
        try {
            http.apply(post("editMessageReplyMarkup", Map.of(
                    "chat_id", message.path("chat").path("id").asLong(),
                    "message_id", message.path("message_id").asLong(),
                    "reply_markup", Map.of("inline_keyboard", List.of()))));
        } catch (RuntimeException e) {
            log.debug("editMessageReplyMarkup failed: {}", e.toString());
        }
    }

    private HttpRequest post(String method, Map<String, Object> body) {
        Map<String, Object> ordered = new LinkedHashMap<>(body);
        return HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + props.getTelegram().getBotToken()
                        + "/" + method))
                .timeout(Duration.ofSeconds(POLL_SECONDS + 10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(ordered)))
                .build();
    }

    private static Function<HttpRequest, TelegramSender.HttpResult> defaultHttp() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return request -> {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return new TelegramSender.HttpResult(response.statusCode(), response.body());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @PreDestroy
    void shutdown() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
