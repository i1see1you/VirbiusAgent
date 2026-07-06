package io.virbius.control.gateway;

import io.virbius.control.domain.dto.request.GatewayRouteInput;
import io.virbius.policy.BindScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GatewayRoutesHelper {

    private GatewayRoutesHelper() {}

    @SuppressWarnings("unchecked")
    public static List<GatewayRouteInput> parseRoutes(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }
        Object gateway = metadata.get("gateway");
        if (!(gateway instanceof Map<?, ?> gw)) {
            return List.of();
        }
        Object routes = gw.get("routes");
        if (!(routes instanceof List<?> list)) {
            return List.of();
        }
        List<GatewayRouteInput> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) row;
            rejectLegacyRouteFields(m);
            String uri = str(m.get("uri"));
            List<String> methods = stringList(m.get("methods"));
            out.add(new GatewayRouteInput(uri, methods));
        }
        return List.copyOf(out);
    }

    public static Map<String, Object> gatewayBlock(Map<String, Object> metadata) {
        if (metadata == null) {
            return Map.of();
        }
        Object gateway = metadata.get("gateway");
        if (!(gateway instanceof Map<?, ?> gw)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        gw.forEach((k, v) -> {
            if (k != null) {
                out.put(String.valueOf(k), v);
            }
        });
        return out;
    }

    public static void validateRoutes(List<GatewayRouteInput> routes) {
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("at least one gateway route required");
        }
        Set<String> uris = new LinkedHashSet<>();
        for (GatewayRouteInput r : routes) {
            if (r == null) {
                continue;
            }
            String uri = r.uri() != null ? r.uri().trim() : "";
            if (uri.isEmpty() || !uri.startsWith("/")) {
                throw new IllegalArgumentException("route uri must start with /");
            }
            BindScope.validateUriPattern(uri);
            if (uris.contains(uri)) {
                throw new IllegalArgumentException("duplicate gateway route uri: " + uri);
            }
            uris.add(uri);
        }
    }

    public static Map<String, Object> toGatewayMetadata(
            Map<String, Object> existingGateway,
            Boolean evaluate,
            String failMode,
            Map<String, Object> cloudScan,
            List<GatewayRouteInput> routes) {
        Map<String, Object> gateway = new LinkedHashMap<>(
                existingGateway != null ? existingGateway : Map.of());
        if (evaluate != null) {
            gateway.put("evaluate", evaluate);
        } else if (!gateway.containsKey("evaluate")) {
            gateway.put("evaluate", true);
        }
        if (failMode != null && !failMode.isBlank()) {
            gateway.put("fail_mode", failMode.trim().toLowerCase(Locale.ROOT));
        } else if (!gateway.containsKey("fail_mode")) {
            gateway.put("fail_mode", "open");
        }
        if (cloudScan != null && !cloudScan.isEmpty()) {
            gateway.put("cloud_scan", new LinkedHashMap<>(cloudScan));
        }
        List<Map<String, Object>> routeRows = new ArrayList<>();
        for (GatewayRouteInput r : routes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uri", r.uri().trim());
            List<String> methods = r.methods() != null && !r.methods().isEmpty() ? r.methods() : List.of("POST");
            row.put("methods", methods);
            routeRows.add(row);
        }
        gateway.put("routes", routeRows);
        return gateway;
    }

    private static void rejectLegacyRouteFields(Map<String, Object> m) {
        if (m.containsKey("scene") || m.containsKey("dynamic_scene")) {
            throw new IllegalArgumentException("gateway.routes use uri/methods only; scene via scene_registry");
        }
        Object match = m.get("match");
        if (match instanceof Map<?, ?> mm && !mm.isEmpty()) {
            throw new IllegalArgumentException("gateway.routes must not use match; use scene_registry");
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String s = String.valueOf(item).trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
