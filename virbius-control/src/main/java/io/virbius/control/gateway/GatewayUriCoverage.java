package io.virbius.control.gateway;

import io.virbius.policy.BindScope;
import io.virbius.policy.SceneRegistry;
import io.virbius.policy.SceneRegistry.SceneEntry;
import java.util.List;

public final class GatewayUriCoverage {

    private GatewayUriCoverage() {}

    public static void validateSceneRegistryUris(List<String> gatewayUris, SceneRegistry registry) {
        if (registry == null || registry.scenes().isEmpty()) {
            return;
        }
        if (gatewayUris == null || gatewayUris.isEmpty()) {
            throw new IllegalArgumentException("gateway.routes required when scene_registry declares uris");
        }
        for (SceneEntry entry : registry.scenes()) {
            for (String sceneUri : entry.uris()) {
                BindScope.validateUriPattern(sceneUri);
                if (!BindScope.coveredByAny(sceneUri, gatewayUris)) {
                    throw new IllegalArgumentException("scene_registry uri not covered by gateway.routes: "
                            + sceneUri
                            + " (scene "
                            + entry.sceneId()
                            + ")");
                }
            }
        }
    }
}
