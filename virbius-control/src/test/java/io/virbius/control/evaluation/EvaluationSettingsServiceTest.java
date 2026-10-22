package io.virbius.control.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 设置单源（DB）+ env 一次性种子 + 热生效 + token 只写不读。 */
class EvaluationSettingsServiceTest {

    private static final String KEY = "settings-master-key-0123456789abcdef";

    static final class FakeRepo implements EvaluationSettingsRepository {
        Row row;

        @Override
        public Optional<Row> find() {
            return Optional.ofNullable(row);
        }

        @Override
        public void upsert(Row r) {
            row = r;
        }
    }

    private static EvaluationSettingsCipher cipher() {
        return new EvaluationSettingsCipher(KEY, "");
    }

    private static EvaluationSettingsService service(FakeRepo repo, String base, String token) {
        return new EvaluationSettingsService(repo, cipher(), true, base, token, true,
                "https://promo.example/x");
    }

    @Test
    void seedsFromEnvOnceAndEncryptsToken() {
        FakeRepo repo = new FakeRepo();
        EvaluationSettingsService svc = service(repo, "http://eval:8084", "env-token");

        assertTrue(repo.row != null, "首次启动应写入 DB 种子");
        assertTrue(repo.row.apiTokenCt().startsWith("esv1:1:"), "token 必须加密入库");
        assertFalse(repo.row.apiTokenCt().contains("env-token"));
        assertEquals("http://eval:8084", repo.row.baseUrl());

        assertEquals("env-token", svc.get().apiToken(), "读取时解密");
        assertTrue(svc.view().apiTokenSet());
        assertFalse(svc.view().toString().contains("env-token"), "视图不得泄漏 token");
    }

    @Test
    void existingDbRowWinsOverEnv() {
        FakeRepo repo = new FakeRepo();
        repo.row = new EvaluationSettingsRepository.Row(true, "http://db:8084",
                cipher().encrypt("db-token"), false, "", "admin");
        EvaluationSettingsService svc = service(repo, "http://env:8084", "env-token");
        assertEquals("http://db:8084", svc.get().baseUrl());
        assertEquals("db-token", svc.get().apiToken());
        assertEquals("http://env:8084", service(new FakeRepo(), "http://env:8084", "")
                .get().baseUrl(), "空 DB 时用 env 种子");
    }

    @Test
    void saveReplacesAndTakesEffectImmediately() {
        FakeRepo repo = new FakeRepo();
        EvaluationSettingsService svc = service(repo, "http://old:8084", "old-token");

        svc.save(new EvaluationSettingsService.Update(true, "http://new:9000", "fresh-token",
                false, ""), "tester");

        assertEquals("http://new:9000", svc.get().baseUrl(), "保存后立即生效（热加载）");
        assertEquals("fresh-token", svc.get().apiToken());
        assertFalse(repo.row.apiTokenCt().contains("fresh-token"));
        assertEquals("tester", repo.row.updatedBy());
        assertFalse(svc.view().promoEnabled());
    }

    @Test
    void nullTokenKeepsExistingAndEmptyClears() {
        FakeRepo repo = new FakeRepo();
        EvaluationSettingsService svc = service(repo, "http://old:8084", "keep-me");

        svc.save(new EvaluationSettingsService.Update(true, "http://old:8084", null, true, ""),
                "t");
        assertEquals("keep-me", svc.get().apiToken(), "apiToken=null 保持原值");

        svc.save(new EvaluationSettingsService.Update(true, "http://old:8084", "", true, ""), "t");
        assertTrue(svc.get().apiToken().isBlank(), "apiToken=\"\" 清空");
        assertNull(repo.row.apiTokenCt());
    }

    @Test
    void rejectsInvalidBaseUrl() {
        EvaluationSettingsService svc = service(new FakeRepo(), "", "");
        assertThrows(IllegalArgumentException.class, () -> svc.save(
                new EvaluationSettingsService.Update(true, "ftp://x", null, true, ""), "t"));
        assertThrows(IllegalArgumentException.class, () -> svc.save(
                new EvaluationSettingsService.Update(true, "http://", null, true, ""), "t"));
    }

    @Test
    void decryptFailureFailsFast() {
        FakeRepo repo = new FakeRepo();
        repo.row = new EvaluationSettingsRepository.Row(true, "http://db:8084",
                new EvaluationSettingsCipher("settings-master-key-aaaaaaaaaaaaaaaa", "")
                        .encrypt("x"), true, "", "admin");
        EvaluationSettingsService svc = new EvaluationSettingsService(repo, cipher(), true,
                "", "", true, "");
        assertThrows(IllegalStateException.class, svc::get,
                "密钥变更必须报错，不能静默当成未配置");
    }

    @Test
    void missingMasterKeyFailsWhenSeedingToken() {
        FakeRepo repo = new FakeRepo();
        assertThrows(IllegalStateException.class,
                () -> new EvaluationSettingsService(repo,
                        new EvaluationSettingsCipher("", ""), true, "http://x:1", "tok",
                        true, ""),
                "无密钥且需保存 token → 启动失败（提示配置 VIRBIUS_SETTINGS_MASTER_KEY）");
    }

    @Test
    void fixedSettingsAreReadOnly() {
        EvaluationSettingsService svc = new EvaluationSettingsService(
                new EvaluationSettingsService.Settings(true, "http://fixed:1", "t", true, ""));
        assertEquals("http://fixed:1", svc.get().baseUrl());
        assertThrows(UnsupportedOperationException.class, () -> svc.save(
                new EvaluationSettingsService.Update(true, "http://new:1", null, true, ""), "t"));
    }

    @Test
    void emptyTokenSetsApiTokenSetFalse() {
        FakeRepo repo = new FakeRepo();
        EvaluationSettingsService svc = service(repo, "http://eval:8084", "");
        assertFalse(svc.view().apiTokenSet());
        assertNotEquals(null, repo.row, "种子仍应写入（baseUrl 已配置）");
    }
}
