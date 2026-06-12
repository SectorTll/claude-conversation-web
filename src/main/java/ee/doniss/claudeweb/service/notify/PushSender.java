package ee.doniss.claudeweb.service.notify;

/** One delivery channel for WAITING notifications (web push, Telegram, …). */
public interface PushSender {

    /** Deliver the event. Called off the request/reader threads; may block on HTTP. */
    void send(WaitingEvent event);

    /** False = skipped by the dispatcher (not configured / turned off). */
    boolean enabled();
}
