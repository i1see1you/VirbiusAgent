package io.virbius.engine.eval;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sliding-window circuit breaker protecting the OCR endpoint.
 *
 * <p>CLOSED --(failure rate over window >= threshold, min samples)--&gt; OPEN
 * --(wait elapsed)--&gt; HALF_OPEN (single probe) --success--&gt; CLOSED / --failure--&gt; OPEN.
 *
 * <p>When OPEN, callers skip the HTTP call and degrade to a review signal —
 * never a silent allow.
 */
public class OcrCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(OcrCircuitBreaker.class);
    private static final int WINDOW_SIZE = 20;
    private static final int MIN_SAMPLES = 10;

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final double failureRateThreshold;
    private final long waitInOpenMs;
    private final Clock clock;

    private State state = State.CLOSED;
    private long openedAt;
    private final Deque<Boolean> window = new ArrayDeque<>();

    public OcrCircuitBreaker(double failureRateThreshold, long waitInOpenMs) {
        this(failureRateThreshold, waitInOpenMs, Clock.systemUTC());
    }

    OcrCircuitBreaker(double failureRateThreshold, long waitInOpenMs, Clock clock) {
        this.failureRateThreshold = failureRateThreshold;
        this.waitInOpenMs = waitInOpenMs;
        this.clock = clock;
    }

    public synchronized boolean allowRequest() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.millis() - openedAt >= waitInOpenMs) {
                    state = State.HALF_OPEN;
                    return true;
                }
                return false;
            case HALF_OPEN:
            default:
                return false;
        }
    }

    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            log.info("ocr circuit breaker: HALF_OPEN -> CLOSED (probe succeeded)");
        }
        state = State.CLOSED;
        push(true);
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            trip("probe failed");
            return;
        }
        push(false);
        if (state == State.CLOSED && window.size() >= MIN_SAMPLES && failureRate() >= failureRateThreshold) {
            trip("failure rate " + String.format("%.0f%%", failureRate() * 100)
                    + " over last " + window.size() + " calls");
        }
    }

    public synchronized boolean isOpen() {
        return state != State.CLOSED;
    }

    private void trip(String why) {
        state = State.OPEN;
        openedAt = clock.millis();
        window.clear();
        log.warn("ocr circuit breaker OPEN: {}", why);
    }

    private void push(boolean success) {
        window.addLast(success);
        while (window.size() > WINDOW_SIZE) {
            window.removeFirst();
        }
    }

    private double failureRate() {
        if (window.isEmpty()) {
            return 0;
        }
        long failures = window.stream().filter(b -> !b).count();
        return (double) failures / window.size();
    }
}
