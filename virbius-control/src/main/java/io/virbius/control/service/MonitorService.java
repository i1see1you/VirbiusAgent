package io.virbius.control.service;

import io.virbius.control.repository.MonitorRepository;
import io.virbius.control.repository.MonitorRepository.DegradationRow;
import io.virbius.control.repository.MonitorRepository.EventTimelineRow;
import io.virbius.control.repository.MonitorRepository.RuleRankingRow;
import io.virbius.control.repository.MonitorRepository.SceneTrafficRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MonitorService {

    private final MonitorRepository repository;

    public MonitorService(MonitorRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> ruleRanking(String tenantId, int hours, int limit) {
        List<RuleRankingRow> rows = repository.findRuleRanking(tenantId, hours, limit);
        List<Map<String, Object>> ranking = rows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule_id", row.ruleId());
            m.put("total_hits", row.totalHits());
            m.put("block", row.block());
            m.put("review", row.review());
            m.put("captcha", row.captcha());
            m.put("allow", row.allow());
            m.put("total_requests", row.totalRequests());
            if (row.totalRequests() > 0) {
                m.put("hit_rate", row.totalHits() / (double) row.totalRequests());
                m.put("block_rate", row.block() / (double) row.totalRequests());
            }
            m.put("cnt_degraded", row.degraded());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ranking", ranking);
        return out;
    }

    public Map<String, Object> sceneTraffic(String tenantId, int hours) {
        List<SceneTrafficRow> rows = repository.findSceneTraffic(tenantId, hours);
        List<Map<String, Object>> scenes = rows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scene", row.scene());
            m.put("layer", row.layer());
            m.put("total_requests", row.totalRequests());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenes", scenes);
        return out;
    }

    public Map<String, Object> degradation(String tenantId, int hours) {
        List<DegradationRow> rows = repository.findDegradation(tenantId, hours);
        List<Map<String, Object>> series = rows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", row.bucket());
            m.put("degraded", row.degraded());
            m.put("total_requests", row.totalRequests());
            m.put("degraded_rate", row.totalRequests() > 0
                    ? row.degraded() / (double) row.totalRequests()
                    : 0.0);
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series", series);
        return out;
    }

    public Map<String, Object> eventTimeline(String tenantId, int hours, int limit) {
        List<EventTimelineRow> rows = repository.findEventTimeline(tenantId, hours, limit);
        List<Map<String, Object>> events = rows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule_id", row.ruleId());
            m.put("rule_revision", row.ruleRevision());
            m.put("rollout_state", row.rolloutState());
            m.put("canary_percent", row.canaryPercent());
            m.put("trigger", row.trigger());
            m.put("operator", row.operator());
            m.put("effective_at", row.effectiveAt());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", events);
        return out;
    }
}
