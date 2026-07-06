package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/api/v1/health")
    public ApiResult<Map<String, String>> health() {
        return ApiResult.ok(Map.of("status", "ok", "service", "virbius-control"));
    }
}