package io.virbius.control.service;

import java.util.Map;

public interface CacheReloadNotifier {

    Map<String, Object> publish(String tenantId, String policyVersion, Map<String, Object> payload);
}
