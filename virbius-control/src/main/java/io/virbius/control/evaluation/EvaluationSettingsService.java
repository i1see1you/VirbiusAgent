package io.virbius.control.evaluation;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 评估集成设置（全局单源 = DB）。
 *
 * <p>启动时若 DB 无行，用 {@code VIRBIUS_EVALUATION_*} env 做一次性种子（之后 env 不再生效，
 * 避免"改了 env 却没生效"的困惑）；运行期读 DB（5s 缓存），保存后立即生效（无需重启）。
 * token 在 DB 中只存 AES-GCM 密文，对外只暴露 {@code apiTokenSet}。
 */
@Service
public class EvaluationSettingsService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationSettingsService.class);
    private static final long CACHE_TTL_MS = 5_000;

    /** 生效配置（token 明文，仅内存）。 */
    public record Settings(boolean enabled, String baseUrl, String apiToken,
                           boolean promoEnabled, String promoUrl) {
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    /** 对外视图（token 永不回传）。 */
    public record View(boolean enabled, String baseUrl, boolean apiTokenSet, boolean promoEnabled,
                       String promoUrl, String updatedBy) {
    }

    /** 保存请求：apiToken=null 表示保持不变，"" 表示清空。 */
    public record Update(boolean enabled, String baseUrl, String apiToken,
                         boolean promoEnabled, String promoUrl) {
    }

    private record Cache(Settings settings, long at) {
    }

    private final EvaluationSettingsRepository repository;
    private final EvaluationSettingsCipher cipher;
    private final Settings seed;
    private final AtomicReference<Cache> cache = new AtomicReference<>();
    /** 测试直构：固定配置（无持久化）。 */
    private final boolean fixed;

    @Autowired
    public EvaluationSettingsService(
            EvaluationSettingsRepository repository,
            EvaluationSettingsCipher cipher,
            @Value("${virbius.evaluation.enabled:true}") boolean enabled,
            @Value("${virbius.evaluation.base-url:}") String baseUrl,
            @Value("${virbius.evaluation.api-token:}") String apiToken,
            @Value("${virbius.evaluation.promo-enabled:true}") boolean promoEnabled,
            @Value("${virbius.evaluation.promo-url:}") String promoUrl) {
        this.repository = repository;
        this.cipher = cipher;
        this.seed = new Settings(enabled, baseUrl == null ? "" : baseUrl.trim(),
                apiToken == null ? "" : apiToken.trim(), promoEnabled,
                promoUrl == null ? "" : promoUrl.trim());
        this.fixed = false;
        seedIfEmpty();
    }

    /** 测试/固定配置（无 DB）。 */
    public EvaluationSettingsService(Settings settings) {
        this.repository = null;
        this.cipher = null;
        this.seed = settings;
        this.fixed = true;
        this.cache.set(new Cache(settings, System.currentTimeMillis()));
    }

    /** 首次启动种子：DB 空行时写入 env 配置（一次性）。 */
    private void seedIfEmpty() {
        try {
            if (repository.find().isPresent()) {
                return;
            }
            String ct = seed.apiToken().isBlank() ? null : cipher.encrypt(seed.apiToken());
            repository.upsert(new EvaluationSettingsRepository.Row(seed.enabled(),
                    seed.baseUrl(), ct, seed.promoEnabled(), seed.promoUrl(), "env-seed"));
            log.info("evaluation settings seeded from env: enabled={} baseUrl={} token={}",
                    seed.enabled(), seed.baseUrl(), seed.apiToken().isBlank() ? "none" : "set");
        } catch (Exception e) {
            throw new IllegalStateException(
                    "评估集成设置初始化失败（检查 DB 与 VIRBIUS_SETTINGS_MASTER_KEY）: " + e.getMessage(), e);
        }
    }

    /** 生效配置（DB 单源，5s 缓存；保存后立即刷新）。 */
    public Settings get() {
        if (fixed) {
            return cache.get().settings();
        }
        Cache c = cache.get();
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < CACHE_TTL_MS) {
            return c.settings();
        }
        Settings s = loadFromDb();
        cache.set(new Cache(s, now));
        return s;
    }

    public View view() {
        Settings s = get();
        return new View(s.enabled(), s.baseUrl(), !s.apiToken().isBlank(), s.promoEnabled(),
                s.promoUrl(), updatedBy());
    }

    /** 保存（全量替换；apiToken=null 保持原值，"" 清空）。热生效。 */
    public View save(Update u, String actor) {
        if (fixed) {
            throw new UnsupportedOperationException("fixed settings are read-only");
        }
        String baseUrl = u.baseUrl() == null ? "" : u.baseUrl().trim();
        validate(baseUrl);
        String existing = get().apiToken();
        String token = u.apiToken() == null ? existing : u.apiToken().trim();
        String ct = token.isBlank() ? null : cipher.encrypt(token);
        repository.upsert(new EvaluationSettingsRepository.Row(u.enabled(), baseUrl, ct,
                u.promoEnabled(), u.promoUrl() == null ? "" : u.promoUrl().trim(),
                actor == null || actor.isBlank() ? "unknown" : actor));
        cache.set(new Cache(new Settings(u.enabled(), baseUrl, token, u.promoEnabled(),
                u.promoUrl() == null ? "" : u.promoUrl().trim()), System.currentTimeMillis()));
        log.info("evaluation settings updated by {}: enabled={} baseUrl={} token={} promo={}",
                actor, u.enabled(), baseUrl, token.isBlank() ? "none" : "set", u.promoEnabled());
        return view();
    }

    private Settings loadFromDb() {
        return repository.find().map(row -> new Settings(row.enabled(), row.baseUrl(),
                        row.apiTokenCt() == null || row.apiTokenCt().isBlank()
                                ? "" : cipher.decrypt(row.apiTokenCt()),
                        row.promoEnabled(), row.promoUrl()))
                .orElse(new Settings(true, "", "", true, ""));
    }

    private String updatedBy() {
        try {
            var principal = io.virbius.control.security.ApiKeyAuthContext.current();
            return principal == null ? "unknown" : principal.credentialId();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void validate(String baseUrl) {
        if (baseUrl.isBlank()) {
            return;
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("baseUrl 必须是 http(s) URL: " + baseUrl);
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl);
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("baseUrl 缺少主机名: " + baseUrl);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl 非法: " + e.getMessage());
        }
    }
}
