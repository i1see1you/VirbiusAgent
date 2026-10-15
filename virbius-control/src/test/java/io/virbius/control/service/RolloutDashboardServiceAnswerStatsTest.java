package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.virbius.control.service.RolloutDashboardService.AnswerRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RolloutDashboardServiceAnswerStatsTest {

    private static Map<String, Object> agg(AnswerRow... rows) {
        return RolloutDashboardService.aggregateAnswerStats(List.of(rows));
    }

    @Test
    void denyOnlyAnswersAreExact() {
        // 3 windows of one denied answer + 1 whole denied answer = 2 answers,
        // 4 events. Deny events are never sampled → no estimation involved.
        Map<String, Object> out = agg(
                new AnswerRow(true, 3, 1.0),
                new AnswerRow(true, 1, 1.0));
        assertEquals(2L, out.get("answers"));
        assertEquals(2L, out.get("denied_answers"));
        assertEquals(0L, out.get("sampled_allow_answers"));
        assertEquals(4L, out.get("window_events"));
        assertEquals(0.0, out.get("allowed_answers"));
    }

    @Test
    void sampledAllowAnswersAreReweighted() {
        // Engine default sample-rate-allow = 0.1: a single-event allow answer
        // has inclusion probability 0.1 → weight 10. Raw counting would report
        // total=1 (understating true traffic ~10x and inflating deny_rate).
        Map<String, Object> out = agg(new AnswerRow(false, 1, 0.1));
        assertEquals(10L, out.get("answers"));
        assertEquals(0L, out.get("denied_answers"));
        assertEquals(1L, out.get("sampled_allow_answers"));
        assertEquals(10.0, (double) out.get("allowed_answers"), 1e-9);
    }

    @Test
    void multiEventAllowAnswerHasHigherInclusion() {
        // 3 observed events at rate 0.1 → p = 1 - 0.9^3 = 0.271, weight ~3.690.
        Map<String, Object> out = agg(new AnswerRow(false, 3, 0.1));
        double expected = 1.0 / (1.0 - Math.pow(0.9, 3));
        assertEquals(expected, (double) out.get("allowed_answers"), 1e-9);
        assertEquals(Math.round(expected), out.get("answers"));
        assertEquals(3L, out.get("window_events"));
    }

    @Test
    void fullRateMeansNoReweighting() {
        Map<String, Object> out = agg(new AnswerRow(false, 2, 1.0));
        assertEquals(1L, out.get("answers"));
        assertEquals(1.0, (double) out.get("allowed_answers"), 1e-9);
    }

    @Test
    void invalidRateIsTreatedAsFullyObserved() {
        Map<String, Object> out = agg(new AnswerRow(false, 1, 0.0));
        assertEquals(1L, out.get("answers"));
        assertEquals(1.0, (double) out.get("allowed_answers"), 1e-9);
    }

    @Test
    void mixedAnswerCountsOnceAsDenied() {
        // One trace with 2 deny windows + 1 sampled allow event is ONE denied
        // answer with 3 events (weight 1 — deny events are complete).
        Map<String, Object> out = agg(new AnswerRow(true, 3, 0.1));
        assertEquals(1L, out.get("answers"));
        assertEquals(1L, out.get("denied_answers"));
        assertEquals(0L, out.get("sampled_allow_answers"));
        assertEquals(3L, out.get("window_events"));
    }

    @Test
    void denyRateUsesTheWeightedTotal() {
        // 1 denied answer + 1 sampled allow answer (weight 10) → deny_rate
        // ~1/11 instead of the raw 1/2.
        Map<String, Object> out = agg(
                new AnswerRow(true, 1, 1.0),
                new AnswerRow(false, 1, 0.1));
        assertEquals(11L, out.get("answers"));
        assertEquals(1L, out.get("denied_answers"));
    }

    @Test
    void emptyInputGivesZeros() {
        Map<String, Object> out = agg();
        assertEquals(0L, out.get("answers"));
        assertEquals(0L, out.get("denied_answers"));
        assertEquals(0L, out.get("window_events"));
        assertEquals(0.0, (double) out.get("allowed_answers"), 1e-9);
    }
}
