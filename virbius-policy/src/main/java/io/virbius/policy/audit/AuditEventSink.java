package io.virbius.policy.audit;

import java.util.List;
import java.util.Map;

public interface AuditEventSink extends AutoCloseable {

    void publish(List<Map<String, String>> events);

    @Override
    default void close() {}
}
