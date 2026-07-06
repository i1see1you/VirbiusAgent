package io.virbius.engine.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "virbius.engine.tenant-pool")
public class TenantThreadPoolProperties {

    private int defaultSize = 10;
    private int queueCapacity = 200;
    private Map<String, Integer> sizes = new HashMap<>();

    public int getDefaultSize() {
        return defaultSize;
    }

    public void setDefaultSize(int defaultSize) {
        this.defaultSize = defaultSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Map<String, Integer> getSizes() {
        return sizes;
    }

    public void setSizes(Map<String, Integer> sizes) {
        this.sizes = sizes;
    }

    public int resolveSize(String tenantId) {
        return sizes.getOrDefault(tenantId, defaultSize);
    }
}
