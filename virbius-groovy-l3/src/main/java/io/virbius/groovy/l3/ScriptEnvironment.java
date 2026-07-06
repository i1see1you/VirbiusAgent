package io.virbius.groovy.l3;

import io.virbius.policy.ListMatcher;
import io.virbius.policy.MatchContext;
import io.virbius.policy.ValueResolver;
import io.virbius.policy.ValueSource;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * List / cumulative helpers for {@code decide(ctx)} scripts.
 * Injected into {@link PolicyContext} at evaluation time.
 */
public final class ScriptEnvironment {

    private final String tenantId;
    private final MatchContext matchCtx;
    private final Map<String, ListDefinition> memoryLists;
    private final Map<String, RedisListDefinition> redisLists;
    private final Map<String, CumulativeDefinition> cumulatives;
    private final CumulativeReader cumulativeReader;
    private final RedisListReader redisListReader;

    public ScriptEnvironment(
            String tenantId,
            MatchContext matchCtx,
            Map<String, ListDefinition> memoryLists,
            Map<String, RedisListDefinition> redisLists,
            Map<String, CumulativeDefinition> cumulatives,
            CumulativeReader cumulativeReader,
            RedisListReader redisListReader) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.matchCtx = matchCtx;
        this.memoryLists = memoryLists != null ? Map.copyOf(memoryLists) : Map.of();
        this.redisLists = redisLists != null ? Map.copyOf(redisLists) : Map.of();
        this.cumulatives = cumulatives != null ? Map.copyOf(cumulatives) : Map.of();
        this.cumulativeReader = cumulativeReader;
        this.redisListReader = redisListReader;
    }

    /** Backward-compatible constructor (memory lists only). */
    public ScriptEnvironment(
            String tenantId,
            MatchContext matchCtx,
            Map<String, ListDefinition> lists,
            Map<String, CumulativeDefinition> cumulatives,
            CumulativeReader cumulativeReader) {
        this(tenantId, matchCtx, lists, Map.of(), cumulatives, cumulativeReader, null);
    }

    public boolean listMatch(String listName) {
        if (listName == null || listName.isBlank()) {
            return false;
        }
        String key = listName.trim();
        ListDefinition mem = memoryLists.get(key);
        if (mem != null && mem.entries() != null && !mem.entries().isEmpty()) {
            Optional<String> value = ValueResolver.resolve(mem.dimension(), mem.valueSource(), matchCtx);
            if (value.isEmpty()) {
                return false;
            }
            return listMatch(listName, value.get());
        }
        RedisListDefinition redis = redisLists.get(key);
        if (redis != null && redisListReader != null) {
            return matchRedisList(redis, null);
        }
        return false;
    }

    public boolean listMatch(String listName, String value) {
        if (listName == null || listName.isBlank() || value == null || value.isBlank()) {
            return false;
        }
        String key = listName.trim();
        ListDefinition mem = memoryLists.get(key);
        if (mem != null && mem.entries() != null && !mem.entries().isEmpty()) {
            String content = matchCtx.content() != null ? matchCtx.content() : "";
            return ListMatcher.match(mem.dimension(), value, content, mem.entries());
        }
        RedisListDefinition redis = redisLists.get(key);
        if (redis != null && redisListReader != null) {
            return matchRedisList(redis, value);
        }
        return false;
    }

    private boolean matchRedisList(RedisListDefinition def, String explicitValue) {
        if (def.redisKey() == null || def.redisKey().isBlank()) {
            return false;
        }
        String dim = def.dimension() != null ? def.dimension() : "";
        if (explicitValue != null && !explicitValue.isBlank()) {
            return redisListReader.matches(tenantId, def.listName(), def.redisKey(), explicitValue);
        }
        if (dim.startsWith("var:")) {
            String logical = dim.substring(4);
            String val = matchCtx.varsOrEmpty().get(logical);
            if (val == null || val.isBlank()) {
                return false;
            }
            return redisListReader.matches(tenantId, def.listName(), def.redisKey(), val);
        }
        Optional<String> resolved = ValueResolver.resolve(dim, null, matchCtx);
        return resolved
                .filter(v -> !v.isBlank())
                .map(v -> redisListReader.matches(tenantId, def.listName(), def.redisKey(), v))
                .orElse(false);
    }

    public long getCumulative(String cumulativeName) {
        if (cumulativeName == null || cumulativeName.isBlank() || cumulativeReader == null) {
            return 0;
        }
        CumulativeDefinition def = cumulatives.get(cumulativeName.trim());
        if (def == null) {
            return 0;
        }
        Optional<String> value = ValueResolver.resolve(def.dimension(), def.valueSource(), matchCtx);
        if (value.isEmpty()) {
            return 0;
        }
        ZoneId zone = ZoneId.of(def.timezone() != null ? def.timezone() : "UTC");
        return cumulativeReader.read(
                tenantId,
                def.cumulativeName(),
                value.get(),
                def.windowMinutes(),
                def.windowKind(),
                zone);
    }

    public record ListDefinition(String listName, String dimension, List<String> entries, ValueSource valueSource) {}

    public record RedisListDefinition(String listName, String dimension, String redisKey) {}

    public record CumulativeDefinition(
            String cumulativeName,
            String dimension,
            int windowMinutes,
            String windowKind,
            String timezone,
            ValueSource valueSource) {}

    @FunctionalInterface
    public interface CumulativeReader {
        long read(
                String tenantId,
                String cumulativeName,
                String value,
                int windowMinutes,
                String windowKind,
                ZoneId zone);
    }

    @FunctionalInterface
    public interface RedisListReader {
        boolean matches(String tenantId, String listName, String redisKey, String lookupValue);
    }
}
