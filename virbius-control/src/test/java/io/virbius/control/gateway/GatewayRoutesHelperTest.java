package io.virbius.control.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.virbius.control.domain.dto.request.GatewayRouteInput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GatewayRoutesHelperTest {

    @Test
    void parseUriMethodsRoute() {
        Map<String, Object> metadata = Map.of(
                "gateway",
                Map.of(
                        "routes",
                        List.of(Map.of("uri", "/v1/chat/completions", "methods", List.of("POST")))));
        List<GatewayRouteInput> routes = GatewayRoutesHelper.parseRoutes(metadata);
        assertEquals(1, routes.size());
        assertEquals("/v1/chat/completions", routes.get(0).uri());
        assertEquals(List.of("POST"), routes.get(0).methods());
    }

    @Test
    void rejectsLegacyDynamicSceneField() {
        Map<String, Object> metadata = Map.of(
                "gateway",
                Map.of(
                        "routes",
                        List.of(Map.of("uri", "/v1/chat/completions", "dynamic_scene", true))));
        assertThrows(IllegalArgumentException.class, () -> GatewayRoutesHelper.parseRoutes(metadata));
    }

    @Test
    void rejectsDuplicateUri() {
        List<GatewayRouteInput> routes = List.of(
                new GatewayRouteInput("/v1/chat/completions", List.of("POST")),
                new GatewayRouteInput("/v1/chat/completions", List.of("POST")));
        assertThrows(IllegalArgumentException.class, () -> GatewayRoutesHelper.validateRoutes(routes));
    }

    @Test
    void rejectsMidPathWildcard() {
        List<GatewayRouteInput> routes = List.of(new GatewayRouteInput("/v1/*/chat", List.of("POST")));
        assertThrows(IllegalArgumentException.class, () -> GatewayRoutesHelper.validateRoutes(routes));
    }
}
