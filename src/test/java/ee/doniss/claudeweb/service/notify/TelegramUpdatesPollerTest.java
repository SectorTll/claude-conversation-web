package ee.doniss.claudeweb.service.notify;

import ee.doniss.claudeweb.config.ClaudeProperties;
import ee.doniss.claudeweb.domain.LiveSession;
import ee.doniss.claudeweb.service.chat.ChatEngine;
import ee.doniss.claudeweb.web.dto.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramUpdatesPollerTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final ClaudeProperties props = new ClaudeProperties();
    private final TelegramCallbacks callbacks = new TelegramCallbacks();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final Deque<String> responses = new ArrayDeque<>();
    private final Function<HttpRequest, TelegramSender.HttpResult> http = request -> {
        requests.add(request);
        return new TelegramSender.HttpResult(200, responses.isEmpty() ? "{}" : responses.poll());
    };

    /** Records decide/answer calls; everything else is unused by the poller. */
    private static final class RecordingEngine implements ChatEngine {
        String decided; // "sid/req/allow"
        String answered; // "sid/req/option"
        RuntimeException failWith;

        @Override
        public void decide(String sessionId, String requestId, boolean allow, String message, boolean always) {
            if (failWith != null) {
                throw failWith;
            }
            decided = sessionId + "/" + requestId + "/" + allow;
        }

        @Override
        public void answer(String sessionId, String requestId, List<List<String>> answers) {
            answered = sessionId + "/" + requestId + "/" + answers.get(0).get(0);
        }

        @Override
        public ResponseBodyEmitter sendToSession(String p, String s, String t, String m, String mo,
                                                 List<ChatRequest.Attachment> a) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ResponseBodyEmitter createSessionAndSend(String p, String t, String m, String mo,
                                                        List<ChatRequest.Attachment> a) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancel(String sessionId) {
            return false;
        }

        @Override
        public List<LiveSession> runningChatSessions() {
            return List.of();
        }

        @Override
        public String engineName() {
            return "stub";
        }
    }

    private final RecordingEngine engine = new RecordingEngine();

    @BeforeEach
    void setUp() {
        props.getTelegram().setEnabled(true);
        props.getTelegram().setBotToken("123:abc");
        props.getTelegram().setChatId("42");
    }

    private TelegramUpdatesPoller poller() {
        return new TelegramUpdatesPoller(props, MAPPER, callbacks, engine, http);
    }

    private String update(long updateId, String token, String chatId) {
        return """
                {"ok":true,"result":[{"update_id":%d,"callback_query":{
                  "id":"cb-1","data":"%s",
                  "message":{"message_id":7,"chat":{"id":%s}}}}]}
                """.formatted(updateId, token, chatId);
    }

    @Test
    void allowButtonRoutesToDecideAndAcknowledges() {
        String token = callbacks.register(
                new TelegramCallbacks.Action("sess", "req-1", "permission", true, null));
        responses.add(update(10, token, "42"));

        poller().pollOnce();

        assertEquals("sess/req-1/true", engine.decided);
        // getUpdates + editMessageReplyMarkup (buttons stripped) + answerCallbackQuery (toast).
        assertEquals(3, requests.size());
        assertTrue(requests.get(1).uri().toString().endsWith("/editMessageReplyMarkup"));
        assertTrue(requests.get(2).uri().toString().endsWith("/answerCallbackQuery"));
        assertTrue(bodyOf(requests.get(2)).contains("Allowed"));
    }

    @Test
    void questionButtonRoutesToAnswerWithThePickedOption() {
        String token = callbacks.register(
                new TelegramCallbacks.Action("sess", "req-2", "question", false, "Ship it"));
        responses.add(update(11, token, "42"));

        poller().pollOnce();

        assertEquals("sess/req-2/Ship it", engine.answered);
    }

    @Test
    void aCallbackFromAForeignChatIsDroppedSilently() {
        String token = callbacks.register(
                new TelegramCallbacks.Action("sess", "req-3", "permission", true, null));
        responses.add(update(12, token, "999"));

        poller().pollOnce();

        assertNull(engine.decided);
        assertEquals(1, requests.size(), "no acknowledgement for strangers — getUpdates only");
    }

    @Test
    void anExpiredTokenAcknowledgesWithoutTouchingTheEngine() {
        responses.add(update(13, "gone-token", "42"));

        poller().pollOnce();

        assertNull(engine.decided);
        assertEquals(2, requests.size());
        assertTrue(bodyOf(requests.get(1)).contains("Expired"));
    }

    @Test
    void anEngineFailureIsReportedBackToThePhone() {
        engine.failWith = new IllegalStateException("already decided");
        String token = callbacks.register(
                new TelegramCallbacks.Action("sess", "req-4", "permission", true, null));
        responses.add(update(14, token, "42"));

        poller().pollOnce();

        assertTrue(bodyOf(requests.get(1)).contains("already decided"));
    }

    @Test
    void offsetAdvancesPastProcessedUpdates() {
        String token = callbacks.register(
                new TelegramCallbacks.Action("sess", "req-5", "permission", false, null));
        responses.add(update(20, token, "42"));

        TelegramUpdatesPoller p = poller();
        p.pollOnce();
        p.pollOnce(); // second round gets "{}" — but must ask from update 21

        String secondGetUpdates = bodyOf(requests.get(requests.size() - 1));
        assertTrue(secondGetUpdates.contains("\"offset\":21"), secondGetUpdates);
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
