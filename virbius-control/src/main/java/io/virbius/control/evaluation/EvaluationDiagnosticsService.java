package io.virbius.control.evaluation;

import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 评估服务接入诊断：分步执行并逐项返回（url → dns → tcp → health → auth → license）。
 * 只探测已保存的配置，不接收前端任意地址（防 SSRF）。每步失败即停止后续步骤并标注"未执行"。
 */
@Service
public class EvaluationDiagnosticsService {

    public record Step(String step, boolean ok, String detail, long ms) {
    }

    public record Result(boolean ok, List<Step> steps) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    public Result run(EvaluationSettingsService.Settings cfg) {
        List<Step> steps = new ArrayList<>();
        boolean ok = true;

        long t0 = System.nanoTime();
        URI uri;
        try {
            uri = URI.create(cfg.baseUrl());
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("缺少主机名");
            }
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new IllegalArgumentException("仅支持 http/https");
            }
            steps.add(step("url", true, cfg.baseUrl(), t0));
        } catch (Exception e) {
            steps.add(step("url", false, "baseUrl 非法: " + e.getMessage(), t0));
            return new Result(false, steps);
        }

        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort()
                : ("https".equals(uri.getScheme()) ? 443 : 80);

        t0 = System.nanoTime();
        try {
            InetAddress addr = InetAddress.getByName(host);
            steps.add(step("dns", true, addr.getHostAddress(), t0));
        } catch (Exception e) {
            steps.add(step("dns", false, "解析失败: " + e.getMessage(), t0));
            return new Result(false, steps);
        }

        t0 = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 1500);
            steps.add(step("tcp", true, host + ":" + port, t0));
        } catch (Exception e) {
            steps.add(step("tcp", false, "连接失败: " + e.getMessage(), t0));
            return new Result(false, steps);
        }

        t0 = System.nanoTime();
        try {
            HttpResponse<String> health = http.send(get(uri, "/api/v1/health"),
                    HttpResponse.BodyHandlers.ofString());
            if (health.statusCode() != 200) {
                steps.add(step("health", false, "HTTP " + health.statusCode(), t0));
                return new Result(false, steps);
            }
            steps.add(step("health", true, "HTTP 200", t0));
        } catch (Exception e) {
            steps.add(step("health", false, "请求失败: " + e.getMessage(), t0));
            return new Result(false, steps);
        }

        t0 = System.nanoTime();
        if (cfg.apiToken().isBlank()) {
            steps.add(step("auth", false, "未配置 API token（评估服务启用了 Bearer 时必然 401）", t0));
            return new Result(false, steps);
        }
        HttpResponse<String> lic;
        try {
            lic = http.send(get(uri, "/api/v1/license", cfg.apiToken()),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            steps.add(step("auth", false, "请求失败: " + e.getMessage(), t0));
            return new Result(false, steps);
        }
        if (lic.statusCode() == 401) {
            steps.add(step("auth", false, "HTTP 401：API token 不匹配", t0));
            return new Result(false, steps);
        }
        if (lic.statusCode() == 404) {
            steps.add(step("auth", false, "HTTP 404：评估服务未提供 /license（版本过旧）", t0));
            return new Result(false, steps);
        }
        if (lic.statusCode() != 200) {
            steps.add(step("auth", false, "HTTP " + lic.statusCode(), t0));
            return new Result(false, steps);
        }
        steps.add(step("auth", true, "HTTP 200（token 有效）", t0));

        t0 = System.nanoTime();
        try {
            var data = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(lic.body()).path("data");
            String mode = data.path("mode").asText("unknown");
            boolean active = data.path("active").asBoolean(false);
            String detail = mode + (data.hasNonNull("days_left")
                    ? " · 剩余 " + data.get("days_left").asLong() + " 天" : "");
            boolean good = active || "grace".equals(mode);
            steps.add(step("license", good, detail, t0));
            ok = good;
        } catch (Exception e) {
            steps.add(step("license", false, "许可状态解析失败: " + e.getMessage(), t0));
            ok = false;
        }
        return new Result(ok, steps);
    }

    private HttpRequest get(URI base, String path) {
        return get(base, path, null);
    }

    /** 带凭据的 GET：auth/license 步必须带 Bearer（health 保持无头——该端点豁免鉴权）。 */
    private HttpRequest get(URI base, String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(base.toString().replaceAll("/+$", "") + path))
                .timeout(Duration.ofSeconds(2))
                .GET();
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return b.build();
    }

    private static Step step(String name, boolean ok, String detail, long startNanos) {
        return new Step(name, ok, detail, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
