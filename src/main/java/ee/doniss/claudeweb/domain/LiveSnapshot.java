package ee.doniss.claudeweb.domain;

import java.util.List;

/** A snapshot of all live (running) sessions and how many are waiting for input. */
public record LiveSnapshot(List<LiveSession> sessions, long waitingCount) {
}
