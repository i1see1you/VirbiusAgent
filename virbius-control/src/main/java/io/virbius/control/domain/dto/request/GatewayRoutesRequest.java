package io.virbius.control.domain.dto.request;

import java.util.List;
import java.util.Map;

public record GatewayRoutesRequest(
        Boolean evaluate,
        String failMode,
        Map<String, Object> cloudScan,
        List<GatewayRouteInput> routes) {}
