package ee.doniss.claudeweb.service.notify;

import ee.doniss.claudeweb.config.ClaudeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationDispatcherTest {

    private static final class FakeSender implements PushSender {
        final List<WaitingEvent> sent = new CopyOnWriteArrayList<>();
        boolean enabled = true;
        boolean explode;

        @Override
        public void send(WaitingEvent event) {
            if (explode) {
                throw new IllegalStateException("boom");
            }
            sent.add(event);
        }

        @Override
        public boolean enabled() {
            return enabled;
        }
    }

    private final ClaudeProperties props = new ClaudeProperties();
    private final AtomicLong clock = new AtomicLong();
    private final FakeSender sender = new FakeSender();
    private final NotificationDispatcher dispatcher =
            new NotificationDispatcher(List.of(sender), props, clock::get);

    private static Map<String, Object> permissionCard(String requestId) {
        return Map.of("type", "permission", "requestId", requestId, "toolName", "Bash");
    }

    /** The dispatcher delivers on its own thread — wait for the expected count. */
    private void awaitSent(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (sender.sent.size() < count) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("expected " + count + " sends, got " + sender.sent.size());
            }
            Thread.sleep(10);
        }
    }

    @Test
    void oneCardPushesOnceAndBuildsTheEvent() throws Exception {
        dispatcher.onWaiting("proj", "sess", "permission", permissionCard("r1"));
        dispatcher.onWaiting("proj", "sess", "permission", permissionCard("r1")); // duplicate card

        awaitSent(1);
        Thread.sleep(50);
        assertEquals(1, sender.sent.size(), "the same requestId never pushes twice");
        WaitingEvent e = sender.sent.get(0);
        assertEquals("Claude needs permission", e.title());
        assertEquals("Allow Bash?", e.body());
        assertEquals("/p/proj/s/sess", e.path());
    }

    @Test
    void burstOfCardsForOneSessionCollapsesWithinCooldown() throws Exception {
        props.getPush().setCooldown(Duration.ofSeconds(30));
        dispatcher.onWaiting("p", "s", "permission", permissionCard("r1"));
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        dispatcher.onWaiting("p", "s", "permission", permissionCard("r2")); // within cooldown

        awaitSent(1);
        Thread.sleep(50);
        assertEquals(1, sender.sent.size(), "a burst is one ping");

        clock.addAndGet(Duration.ofSeconds(60).toNanos());
        dispatcher.onWaiting("p", "s", "permission", permissionCard("r3"));
        awaitSent(2);
    }

    @Test
    void differentSessionsDoNotShareTheCooldown() throws Exception {
        dispatcher.onWaiting("p", "s1", "permission", permissionCard("r1"));
        dispatcher.onWaiting("p", "s2", "permission", permissionCard("r2"));
        awaitSent(2);
    }

    @Test
    void questionCardsUseTheFirstQuestionAsBody() throws Exception {
        dispatcher.onWaiting("p", "s", "question", Map.of(
                "type", "question", "requestId", "q1",
                "questions", List.of(Map.of("question", "Which DB?", "header", "DB"))));
        awaitSent(1);
        assertEquals("Claude has a question", sender.sent.get(0).title());
        assertEquals("Which DB?", sender.sent.get(0).body());
    }

    @Test
    void disabledSendersAreSkippedAndFailuresNeverPropagate() throws Exception {
        sender.explode = true;
        dispatcher.onWaiting("p", "s", "permission", permissionCard("r1")); // must not throw
        Thread.sleep(100);
        assertTrue(sender.sent.isEmpty());

        sender.explode = false;
        sender.enabled = false;
        clock.addAndGet(Duration.ofMinutes(5).toNanos());
        dispatcher.onWaiting("p", "s", "permission", permissionCard("r2"));
        Thread.sleep(100);
        assertTrue(sender.sent.isEmpty(), "a disabled sender is never invoked");
    }
}
