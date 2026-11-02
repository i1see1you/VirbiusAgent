package io.virbius.control.evaluation;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.evaluation.EvaluationSuppressionsService.CreateRequest;
import io.virbius.control.evaluation.JdbcEvaluationSuppressionsRepository.Row;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评估抑制注册表（租户域）：列表 / 接受（新建）/ 撤销 / 导出 .ve-suppress.yaml。
 *
 * <p>路径在 /api/v1/admin/tenants/** 下 → ApiKeyRoutePolicy：GET=TENANT_VIEWER、
 * 写=TENANT_ADMIN。数据面本地自治：不依赖评估服务在线（表由首次访问惰性创建）；
 * 活跃规则在代理转发 /scans、/skills 时自动注入（请求体自带 suppress 时请求级优先）。
 *
 * <p>DELETE 为软撤销（status=withdrawn，乐观锁 version），历史接受记录保留可追溯。
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/evaluation/suppressions")
public class EvaluationSuppressionsController {

    private final EvaluationSuppressionsService suppressions;

    public EvaluationSuppressionsController(EvaluationSuppressionsService suppressions) {
        this.suppressions = suppressions;
    }

    @GetMapping
    public ApiResult<List<Row>> list(@PathVariable String tenantId,
            @RequestParam(value = "status", required = false) String status) {
        boolean all = "all".equalsIgnoreCase(status == null ? "" : status.trim());
        return ApiResult.ok(suppressions.list(tenantId, all));
    }

    @PostMapping
    public ApiResult<Row> create(@PathVariable String tenantId, @RequestBody CreateRequest req) {
        return ApiResult.ok(suppressions.create(tenantId, req));
    }

    /** 撤销（body 传 {\"version\": <列表里的 version>}；并发修改返回 409 语义 message）。 */
    @DeleteMapping("/{id}")
    public ApiResult<Row> withdraw(@PathVariable String tenantId, @PathVariable String id,
            @RequestBody(required = false) WithdrawRequest req) {
        long version = req == null || req.version() == null ? -1L : req.version();
        return ApiResult.ok(suppressions.withdraw(tenantId, id, version));
    }

    /** 导出活跃规则为标准 .ve-suppress.yaml（CLI --suppress / VE_SUPPRESS_FILE 可直接消费）。 */
    @GetMapping(value = "/export", produces = "text/yaml;charset=UTF-8")
    public ResponseEntity<String> export(@PathVariable String tenantId) {
        String yaml = suppressions.activeYaml(tenantId);
        if (yaml == null) {
            yaml = "# 无活跃抑制规则\n";
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/yaml;charset=UTF-8"))
                .body(yaml);
    }

    public record WithdrawRequest(Long version) {
    }
}
