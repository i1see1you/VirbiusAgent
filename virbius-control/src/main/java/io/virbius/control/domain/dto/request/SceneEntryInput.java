package io.virbius.control.domain.dto.request;

import java.util.List;
import java.util.Map;

public record SceneEntryInput(
        String sceneId,
        String appId,
        Boolean defaultScene,
        List<String> uris,
        Integer priority,
        Map<String, String> matchQuery,
        Map<String, String> matchHeaders) {}
