package io.virbius.control.admin;

import io.virbius.control.service.PublishService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}")
public class RuntimeSnapshotController {

    private final PublishService publishService;

    public RuntimeSnapshotController(PublishService publishService) {
        this.publishService = publishService;
    }

    @GetMapping("/runtime-snapshot")
    public Map<String, Object> runtimeSnapshot(@PathVariable("tenantId") String tenantId) {
        return publishService.buildRuntimeSnapshotPayload(tenantId);
    }
}
