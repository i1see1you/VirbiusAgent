package io.virbius.control.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评估集成设置（平台管理员）：配置读写、连接诊断、许可证上传。
 *
 * <p>路径在 {@code /api/v1/admin/platform/} 下 → ApiKeyRoutePolicy 要求 PLATFORM_ADMIN。
 * 设置保存后立即热生效（无需重启控制面）；token 只写不读。
 */
@RestController
@RequestMapping("/api/v1/admin/platform/evaluation")
public class EvaluationSettingsAdminController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EvaluationSettingsService settings;
    private final EvaluationDiagnosticsService diagnostics;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public EvaluationSettingsAdminController(EvaluationSettingsService settings,
                                            EvaluationDiagnosticsService diagnostics) {
        this.settings = settings;
        this.diagnostics = diagnostics;
    }

    public record LicenseUpload(String jwt) {
    }

    @GetMapping("/settings")
    public EvaluationSettingsService.View view() {
        return settings.view();
    }

    @PutMapping("/settings")
    public EvaluationSettingsService.View save(@RequestBody EvaluationSettingsService.Update update) {
        return settings.save(update, actor());
    }

    @PostMapping("/settings/test")
    public EvaluationDiagnosticsService.Result test() {
        return diagnostics.run(settings.get());
    }

    /** 上传许可证：转发到评估服务 POST /api/v1/license（先验签后落盘），状态码/信封透传。 */
    @PostMapping("/license")
    public ResponseEntity<String> uploadLicense(@RequestBody LicenseUpload upload) {
        if (upload == null || upload.jwt() == null || upload.jwt().isBlank()) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":400,\"message\":\"jwt 不能为空\",\"data\":null}");
        }
        EvaluationSettingsService.Settings cfg = settings.get();
        if (!cfg.enabled()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, 5033, "评估集成已关闭");
        }
        if (!cfg.configured() || cfg.apiToken().isBlank()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, 5031, "评估服务未配置（baseUrl/token）");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.baseUrl().replaceAll("/+$", "") + "/api/v1/license"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain")
                    .header("Authorization", "Bearer " + cfg.apiToken())
                    .POST(HttpRequest.BodyPublishers.ofString(upload.jwt())).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(resp.statusCode())
                    .contentType(MediaType.APPLICATION_JSON).body(resp.body());
        } catch (Exception e) {
            return error(HttpStatus.BAD_GATEWAY, 5032, "评估服务不可达: " + e.getMessage());
        }
    }

    private static String actor() {
        try {
            var p = io.virbius.control.security.ApiKeyAuthContext.current();
            return p == null ? "unknown" : p.credentialId();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static ResponseEntity<String> error(HttpStatus status, int code, String message) {
        String body;
        try {
            body = "{\"code\":" + code + ",\"message\":" + JSON.writeValueAsString(message)
                    + ",\"data\":null}";
        } catch (Exception e) {
            body = "{\"code\":" + code + ",\"message\":\"error\",\"data\":null}";
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
