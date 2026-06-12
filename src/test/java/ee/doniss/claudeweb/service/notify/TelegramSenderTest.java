package ee.doniss.claudeweb.service.notify;

import ee.doniss.claudeweb.config.ClaudeProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramSenderTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final WaitingEvent EVENT =
            new WaitingEvent("p", "s", "permission", "Claude needs permission", "Allow Bash?", "/p/p/s/s");

    private final ClaudeProperties props = new ClaudeProperties();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final Function<HttpRequest, TelegramSender.HttpResult> http = request -> {
        requests.add(request);
        return new TelegramSender.HttpResult(200, "ok");
    };

    private TelegramSender sender() {
        return new TelegramSender(props, MAPPER, http);
    }

    @Test
    void disabledUntilTokenAndChatIdConfigured() {
        assertFalse(sender().enabled());
        props.getTelegram().setEnabled(true);
        assertFalse(sender().enabled(), "still needs token + chat id");
        props.getTelegram().setBotToken("123:abc");
        props.getTelegram().setChatId("42");
        assertTrue(sender().enabled());
    }

    @Test
    void sendPostsSendMessageWithChatIdAndText() {
        props.getTelegram().setEnabled(true);
        props.getTelegram().setBotToken("123:abc");
        props.getTelegram().setChatId("42");

        sender().send(EVENT);

        assertEquals(1, requests.size());
        HttpRequest r = requests.get(0);
        assertEquals("https://api.telegram.org/bot123:abc/sendMessage", r.uri().toString());
        JsonNode body = MAPPER.readTree(bodyOf(r));
        assertEquals("42", body.get("chat_id").asText());
        assertTrue(body.get("text").asText().contains("Allow Bash?"));
        assertFalse(body.get("text").asText().contains("/p/p/s/s"),
                "no link without a public base url");
    }

    @Test
    void publicBaseUrlAppendsADeepLink() {
        props.getTelegram().setEnabled(true);
        props.getTelegram().setBotToken("123:abc");
        props.getTelegram().setChatId("42");
        props.getPush().setPublicBaseUrl("https://192.168.1.10:8443/");

        sender().send(EVENT);

        JsonNode body = MAPPER.readTree(bodyOf(requests.get(0)));
        assertTrue(body.get("text").asText().contains("https://192.168.1.10:8443/#/p/p/s/s"),
                "absolute deep link appended: " + body.get("text").asText());
    }

    /** Drain the request's BodyPublisher (java.net.http keeps the body as a publisher). */
    private static String bodyOf(HttpRequest request) {
        var publisher = request.bodyPublisher().orElseThrow();
        StringBuilder sb = new StringBuilder();
        var done = new java.util.concurrent.CompletableFuture<Void>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                sb.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.join();
        return sb.toString();
    }
}
