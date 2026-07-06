package io.virbius.engine.config;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TenantAwareTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareTaskExecutor.class);

    private final TenantThreadPoolProperties properties;
    private final ConcurrentHashMap<String, ThreadPoolExecutor> pools = new ConcurrentHashMap<>();

    public TenantAwareTaskExecutor(TenantThreadPoolProperties properties) {
        this.properties = properties;
    }

    public void submit(String tenantId, Runnable task) {
        poolFor(tenantId).submit(task);
    }

    public void removeTenant(String tenantId) {
        ThreadPoolExecutor pool = pools.remove(tenantId);
        if (pool != null) {
            pool.shutdownNow();
            log.info("shut down thread pool for tenant={}", tenantId);
        }
    }

    @PreDestroy
    public void shutdown() {
        pools.forEach((tenantId, pool) -> {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        pools.clear();
    }

    // visible for testing
    int poolSize(String tenantId) {
        ThreadPoolExecutor pool = pools.get(tenantId);
        return pool != null ? pool.getPoolSize() : 0;
    }

    private ThreadPoolExecutor poolFor(String tenantId) {
        return pools.computeIfAbsent(tenantId, this::createPool);
    }

    private ThreadPoolExecutor createPool(String tenantId) {
        int size = properties.resolveSize(tenantId);
        AtomicInteger counter = new AtomicInteger(0);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                size,
                size,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(properties.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, tenantId + "-rule-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        log.info("created thread pool for tenant={} size={}", tenantId, size);
        return executor;
    }
}
