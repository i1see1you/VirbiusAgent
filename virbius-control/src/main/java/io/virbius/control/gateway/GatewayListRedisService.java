package io.virbius.control.gateway;

import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.policy.GatewayListRedisKeys;
import io.virbius.policy.GatewayListRedisPublisher;
import io.virbius.policy.ListStorageKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;

@Service
public class GatewayListRedisService {

    private static final Logger log = LoggerFactory.getLogger(GatewayListRedisService.class);

    private final Optional<JedisPool> pool;
    private final Optional<GatewayListRedisPublisher> publisher;
    private final ListMetaRepository listMetaRepo;
    private final String keyPrefix;
    private final boolean enabled;

    public GatewayListRedisService(
            ControlJedisPools jedisPools,
            ListMetaRepository listMetaRepo,
            @Value("${virbius.lists.redis.enabled:true}") boolean enabled,
            @Value("${virbius.lists.redis.key-prefix:virbius:lists}") String keyPrefix) {
        Optional<JedisPool> controlJedisPool = jedisPools.pool();
        this.pool = controlJedisPool;
        this.listMetaRepo = listMetaRepo;
        this.keyPrefix = keyPrefix;
        this.enabled = enabled;
        this.publisher = enabled && controlJedisPool.isPresent()
                ? Optional.of(new GatewayListRedisPublisher(controlJedisPool.get(), keyPrefix))
                : Optional.empty();
    }

    public Map<String, Object> redisIndexEntry(String tenantId, AccessListMeta meta) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("list_name", meta.listName());
        block.put("dimension", meta.dimension());
        block.put("storage", "redis");
        block.put("redis_key", GatewayListRedisKeys.listKey(keyPrefix, tenantId, meta.listName()));
        if (meta.remark() != null && !meta.remark().isBlank()) {
            block.put("remark", meta.remark());
        }
        return block;
    }

    public List<Map<String, Object>> publishRedisLists(String tenantId) {
        List<Map<String, Object>> index = new ArrayList<>();
        if (!enabled || publisher.isEmpty()) {
            return index;
        }
        GatewayListRedisPublisher pub = publisher.get();
        for (AccessListMeta meta : listMetaRepo.listMeta(tenantId)) {
            if (ListStorageKind.fromDimension(meta.dimension()) != ListStorageKind.REDIS) {
                continue;
            }
            List<GatewayListRedisPublisher.Entry> entries = listMetaRepo.listEntries(tenantId, meta.listName()).stream()
                    .map(e -> new GatewayListRedisPublisher.Entry(e.value(), e.expiresAt()))
                    .toList();
            pub.publishList(tenantId, meta.listName(), entries);
            index.add(redisIndexEntry(tenantId, meta));
        }
        return index;
    }

    public void syncEntry(String tenantId, String listName, String value, java.time.Instant expiresAt) {
        if (publisher.isEmpty()) {
            return;
        }
        publisher.get().addEntry(tenantId, listName, value, expiresAt);
    }

    public void removeEntry(String tenantId, String listName, String value) {
        if (publisher.isEmpty()) {
            return;
        }
        publisher.get().removeEntry(tenantId, listName, value);
    }

    /** Daily sweep of expired ZSET members (score 1..now). */
    @Scheduled(cron = "${virbius.lists.redis.sweep-cron:0 0 3 * * *}")
    public void sweepExpiredDaily() {
        if (publisher.isEmpty()) {
            return;
        }
        GatewayListRedisPublisher pub = publisher.get();
        long total = 0;
        for (String tenantId : List.of("default")) {
            for (AccessListMeta meta : listMetaRepo.listMeta(tenantId)) {
                if (ListStorageKind.fromDimension(meta.dimension()) != ListStorageKind.REDIS) {
                    continue;
                }
                total += pub.sweepExpired(tenantId, meta.listName());
            }
        }
        if (total > 0) {
            log.info("redis list sweep removed {} expired members", total);
        }
    }
}
