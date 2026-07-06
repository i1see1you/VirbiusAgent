package io.virbius.engine.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.eval.ScriptRuleRunner;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RuleCacheSeeder {

    private static final Logger log = LoggerFactory.getLogger(RuleCacheSeeder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RuleCache cache;
    private final PolicyDataCache policyData;
    private final HttpClient http;
    private final String controlBaseUrl;

    public RuleCacheSeeder(
            RuleCache cache,
            PolicyDataCache policyData,
            @Value("${virbius.control.base-url:http://127.0.0.1:8081}") String controlBaseUrl) {
        this.cache = cache;
        this.policyData = policyData;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.controlBaseUrl = controlBaseUrl.endsWith("/") ? controlBaseUrl.substring(0, controlBaseUrl.length() - 1) : controlBaseUrl;
    }

    @PostConstruct
    public void load() {
        if (cache.ruleCount() > 0) {
            return;
        }
        try {
            String url = controlBaseUrl + "/api/v1/admin/tenants/default/runtime-snapshot";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("failed to fetch runtime snapshot: status={}", resp.statusCode());
                return;
            }
            Map<String, Object> snapshot = JSON.readValue(resp.body(), new TypeReference<>() {});
            String policyVersion = snapshot.getOrDefault("policy_version", "recovered").toString();
            List<Map<String, Object>> rawRules = JSON.convertValue(snapshot.get("rules"), new TypeReference<>() {});
            List<RuleEntry> rules = rawRules.stream().map(RuleEntry::fromMap).toList();
            cache.replaceAll(policyVersion, rules);
            log.info("recovered {} rules from control runtime-snapshot (version={})", rules.size(), policyVersion);

            List<PolicyDataCache.ListBlock> rawLists = JSON.convertValue(
                    snapshot.get("lists"), new TypeReference<>() {});
            List<PolicyDataCache.RedisListIndexBlock> rawRedisIndex = JSON.convertValue(
                    snapshot.get("redis_list_index"), new TypeReference<>() {});
            List<PolicyDataCache.CumulativeBlock> rawCumulatives = JSON.convertValue(
                    snapshot.get("cumulatives"), new TypeReference<>() {});
            PolicyDataCache.TenantPolicyData data = ScriptRuleRunner.fromBlocks(
                    rawLists, rawRedisIndex, rawCumulatives);
            policyData.replace("default", data);
            log.info("recovered policy data (lists/cumulatives) for tenant default");
        } catch (Exception e) {
            log.warn("failed to load runtime snapshot from control: {}", e.getMessage());
        }
    }
}
