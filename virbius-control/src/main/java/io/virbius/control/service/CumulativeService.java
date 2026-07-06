package io.virbius.control.service;

import io.virbius.control.domain.CumulativeDef;
import io.virbius.control.repository.CumulativeRepository;
import io.virbius.policy.CounterStore;
import io.virbius.policy.CumulativeWindow;
import io.virbius.policy.MatchContext;
import io.virbius.policy.ValueResolver;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPool;

@Service
public class CumulativeService {

    private final CumulativeRepository cumulativeRepo;
    private final Optional<CounterStore> counterStore;

    public CumulativeService(
            CumulativeRepository cumulativeRepo,
            @Value("${virbius.redis.url:}") String redisUrl) {
        this.cumulativeRepo = cumulativeRepo;
        this.counterStore = CounterStore.createPool(redisUrl).map(CounterStore::new);
    }

    public List<CumulativeDef> list(String tenantId) {
        return cumulativeRepo.list(tenantId, null);
    }

    public CumulativeDef get(String tenantId, String cumulativeName) {
        return cumulativeRepo
                .get(tenantId, cumulativeName)
                .orElseThrow(() -> new IllegalArgumentException("cumulative not found: " + cumulativeName));
    }

    public CumulativeDef upsert(CumulativeDef def) {
        cumulativeRepo.upsert(def);
        return get(def.tenantId(), def.cumulativeName());
    }

    public void delete(String tenantId, String cumulativeName) {
        cumulativeRepo.delete(tenantId, cumulativeName);
    }

    public Map<String, Object> snapshot(String tenantId, String cumulativeName, MatchContext ctx) {
        CumulativeDef def = get(tenantId, cumulativeName);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cumulative_name", cumulativeName);
        Optional<String> value = ValueResolver.resolve(def.dimension(), null, ctx);
        if (value.isEmpty()) {
            out.put("skipped", true);
            out.put("reason", "empty_value");
            return out;
        }
        out.put("value", value.get());
        if (counterStore.isEmpty()) {
            out.put("degraded", true);
            out.put("count", 0);
            return out;
        }
        int wMin = CumulativeWindow.windowMinutes(def.windowKind(), def.windowMinutes(), def.windowHours());
        ZoneId zone = ZoneId.of(def.timezone() != null ? def.timezone() : "UTC");
        long count = counterStore.get().read(tenantId, cumulativeName, value.get(), wMin, def.windowKind(), zone);
        out.put("count", count);
        out.put("W_minutes", wMin);
        return out;
    }
}
