package io.virbius.control.domain.dto.request;

import java.util.List;
import java.util.Map;

public record AuditEventsBatchRequest(List<Map<String, Object>> events) {}
