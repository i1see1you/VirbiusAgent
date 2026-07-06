package io.virbius.control.domain.dto.request;

import java.util.List;

public record GatewayRouteInput(String uri, List<String> methods) {}
