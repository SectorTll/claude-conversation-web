package ee.doniss.claudeweb.service.notify;

import ee.doniss.claudeweb.config.ClaudeProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * Fans a WAITING event out to the configured {@link PushSender}s. Owns its own single daemon
 * thread: the caller is the sidecar's stdout reader thread (and must never block on HTTP), and the
 * shared {@code claudeChatScheduler} is a small timer pool whose delay would stall watchdogs.
 *
 * <p>Noise control: one card ({@code requestId}) never pushes twice, and pushes for the same
 * session are collapsed within {@code claude.push.cooldown} (a burst of cards = one ping).
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int SEEN_CAP = 1000;

    private final List<PushSender> senders;
    private final ClaudeProperties props;
    private final LongSupplier nanoTime;

    /** Bounded insertion-ordered set of already-pushed requestIds. */
    private final Set<String> seen = Collections.newSetFromMap(Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_CAP;
                }
            }));
    private final ConcurrentHashMap<String, Long> lastPushNanosBySession = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "claude-push-dispatch");
        t.setDaemon(true);
        return t;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationDispatcher(List<PushSender> senders, ClaudeProperties props) {
        this(senders, props, System::nanoTime);
    }

    NotificationDispatcher(List<PushSender> senders, ClaudeProperties props, LongSupplier nanoTime) {
        this.senders = senders;
        this.props = props;
        this.nanoTime = nanoTime;
    }

    /**
     * A chat turn just parked on an interactive card. Never throws and never blocks beyond map
     * bookkeeping — delivery happens on the dispatcher thread.
     */
    public void onWaiting(String projectId, String sessionId, String kind, Map<String, Object> card) {
        try {
            if (!props.getPush().isEnabled() && senders.stream().noneMatch(PushSender::enabled)) {
                return;
            }
            Object requestId = card.get("requestId");
            if (requestId == null || !seen.add(requestId.toString())) {
                return; // already pushed for this card
            }
            long now = nanoTime.getAsLong();
            long cooldown = props.getPush().getCooldown().toNanos();
            Long last = lastPushNanosBySession.get(sessionId);
            if (last != null && now - last < cooldown) {
                return; // a recent ping for this session already covers the burst
            }
            lastPushNanosBySession.put(sessionId, now);

            WaitingEvent event = buildEvent(projectId, sessionId, kind, card);
            executor.execute(() -> deliver(event));
        } catch (RuntimeException e) {
            log.warn("push dispatch failed: {}", e.toString());
        }
    }

    private void deliver(WaitingEvent event) {
        for (PushSender sender : senders) {
            try {
                if (sender.enabled()) {
                    sender.send(event);
                }
            } catch (RuntimeException e) {
                log.warn("push sender {} failed: {}", sender.getClass().getSimpleName(), e.toString());
            }
        }
    }

    private static WaitingEvent buildEvent(String projectId, String sessionId, String kind,
                                           Map<String, Object> card) {
        String title;
        String body;
        if ("question".equals(kind)) {
            title = "Claude has a question";
            body = firstQuestion(card);
        } else {
            title = "Claude needs permission";
            Object tool = card.get("toolName");
            body = tool != null ? "Allow " + tool + "?" : "A tool call is waiting for approval";
        }
        String path = "/p/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8)
                + "/s/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8);
        String requestId = card.get("requestId") instanceof String r ? r : null;
        List<String> options = "question".equals(kind) ? singleQuestionOptions(card) : List.of();
        return new WaitingEvent(projectId, sessionId, kind, title, body, path, requestId, options);
    }

    /**
     * The answer labels of a SINGLE-question card. A card with several questions returns no options
     * — a remote answer must cover every question, which buttons can't, so those cards stay
     * display-only (the deep link still works).
     */
    @SuppressWarnings("unchecked")
    private static List<String> singleQuestionOptions(Map<String, Object> card) {
        if (!(card.get("questions") instanceof List<?> qs) || qs.size() != 1
                || !(qs.get(0) instanceof Map<?, ?> q)) {
            return List.of();
        }
        Object opts = ((Map<String, Object>) q).get("options");
        if (!(opts instanceof List<?> list)) {
            return List.of();
        }
        List<String> labels = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("label") != null) {
                labels.add(m.get("label").toString());
            }
        }
        return labels;
    }

    @SuppressWarnings("unchecked")
    private static String firstQuestion(Map<String, Object> card) {
        Object questions = card.get("questions");
        if (questions instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> q) {
            Object text = ((Map<String, Object>) q).get("question");
            if (text != null) {
                return text.toString();
            }
        }
        return "Pick an answer in the browser";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
