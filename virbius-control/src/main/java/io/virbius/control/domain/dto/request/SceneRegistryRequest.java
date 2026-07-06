package io.virbius.control.domain.dto.request;

import java.util.List;

public record SceneRegistryRequest(
        Boolean failOnUnknownApp,
        Boolean failOnUnresolvedScene,
        List<SceneEntryInput> scenes) {}
