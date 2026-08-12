package io.virbius.control.service;

import io.virbius.control.repository.TenantRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class BootstrapSnapshotPublisher {

    private static final Logger log = LoggerFactory.getLogger(BootstrapSnapshotPublisher.class);

    private final TenantRepository tenantRepo;
    private final PublishService publishService;

    public BootstrapSnapshotPublisher(TenantRepository tenantRepo, PublishService publishService) {
        this.tenantRepo = tenantRepo;
        this.publishService = publishService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            var tenants = tenantRepo.listAll();
            if (tenants.isEmpty()) {
                log.info("bootstrap snapshot publish skipped: no tenants registered");
                return;
            }
            for (var tenant : tenants) {
                String tenantId = tenant.tenantId();
                try {
                    Map<String, Object> result = publishService.runtimeSnapshot(tenantId);
                    log.info("bootstrap snapshot published tenant={} result={}", tenantId, result);
                } catch (Exception e) {
                    log.warn("bootstrap snapshot publish failed for tenant={}: {}", tenantId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("bootstrap snapshot publish skipped: {}", e.getMessage());
        }
    }
}
