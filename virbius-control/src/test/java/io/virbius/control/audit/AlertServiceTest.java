package io.virbius.control.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private AlertService service() {
        return new AlertService(jdbc, 80);
    }

    private static Map<String, Object> event(String traceId, String eventId, String action, int risk) {
        Map<String, Object> e = new HashMap<>();
        e.put("trace_id", traceId);
        e.put("event_id", eventId);
        e.put("session_id", "sess-1");
        e.put("effective_action", action);
        e.put("max_risk_score", risk);
        e.put("scene", "output");
        e.put("rule_id", "r");
        e.put("reason_code", "OUTPUT_XSS");
        return e;
    }

    // -- alert id: one alert per answer (trace), not per event ----------------

    @Test
    void alertIdKeysOnTenantAndTrace() {
        assertEquals("alert-default-tr-1", AlertService.alertIdFor("default", "tr-1", "evt-1"));
    }

    @Test
    void alertIdFallsBackToEventWhenTraceBlank() {
        assertEquals("alert-evt-1", AlertService.alertIdFor("default", "", "evt-1"));
        assertEquals("alert-evt-1", AlertService.alertIdFor("default", null, "evt-1"));
    }

    @Test
    void alertIdIncludesTenantToAvoidCrossTenantTraceCollision() {
        // trace ids are caller-provided: same trace string in two tenants
        // must map to two distinct alert ids.
        assertEquals(AlertService.alertIdFor("t-a", "tr-1", "e"), "alert-t-a-tr-1");
        assertEquals(AlertService.alertIdFor("t-b", "tr-1", "e"), "alert-t-b-tr-1");
    }

    @Test
    void alertIdRandomFallbackWhenNothingToKeyOn() {
        assertTrue(AlertService.alertIdFor("default", "", "").startsWith("alert-"));
    }

    // -- onAuditEvent: streaming windows of one answer collapse to one alert ---

    @Test
    void windowsOfOneTraceProduceOneAlertId() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        AlertService svc = service();

        // A blocked streaming answer: three deny window events, one trace.
        for (int i = 1; i <= 3; i++) {
            svc.onAuditEvent("default", event("tr-agg", "evt-w" + i, "deny", 80));
        }

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("WHERE NOT EXISTS"));

        // Every window computes the SAME (tenant, trace) alert id — the
        // NOT-EXISTS insert then keeps only the first row: one alert per answer.
        for (Object[] a : args.getAllValues()) {
            assertEquals("alert-default-tr-agg", a[0]);
            assertEquals("alert-default-tr-agg", a[9]);
        }
    }

    @Test
    void alertBindsTheSessionIdFromTheEvent() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        service().onAuditEvent("default", event("tr-1", "e1", "deny", 90));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertEquals("sess-1", args.getValue()[3]);
    }

    @Test
    void blankSessionIdBindsNull() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        Map<String, Object> e = event("tr-1", "e1", "deny", 90);
        e.put("session_id", "");
        service().onAuditEvent("default", e);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertNull(args.getValue()[3]);
    }

    @Test
    void differentTracesProduceDifferentAlerts() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        AlertService svc = service();
        svc.onAuditEvent("default", event("tr-1", "e1", "deny", 90));
        svc.onAuditEvent("default", event("tr-2", "e2", "deny", 90));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(anyString(), args.capture());
        assertEquals("alert-default-tr-1", args.getAllValues().get(0)[0]);
        assertEquals("alert-default-tr-2", args.getAllValues().get(1)[0]);
    }

    @Test
    void blankTraceFallsBackToPerEventAlert() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        service().onAuditEvent("default", event("", "evt-9", "deny", 90));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertEquals("alert-evt-9", args.getValue()[0]);
    }

    @Test
    void belowThresholdOrNonEnforcingNeverInserts() {
        AlertService svc = service();
        svc.onAuditEvent("default", event("tr-1", "e1", "deny", 79));
        svc.onAuditEvent("default", event("tr-2", "e2", "allow", 95));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void insertFailureIsSwallowed() {
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("db down"));
        service().onAuditEvent("default", event("tr-1", "e1", "deny", 90)); // must not throw
        verify(jdbc).update(anyString(), any(Object[].class));
    }
}
