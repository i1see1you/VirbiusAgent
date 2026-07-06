package io.virbius.engine.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.virbius.engine.cache.PolicyDataCache;
import io.virbius.engine.cache.RuleEntry;
import java.util.List;

public record CacheReloadRequest(
        String policyVersion,
        List<RuleEntry> rules,
        List<PolicyDataCache.ListBlock> lists,
        @JsonProperty("redis_list_index") List<PolicyDataCache.RedisListIndexBlock> redisListIndex,
        List<PolicyDataCache.CumulativeBlock> cumulatives) {}
