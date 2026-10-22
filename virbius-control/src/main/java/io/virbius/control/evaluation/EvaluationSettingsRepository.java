package io.virbius.control.evaluation;

import java.util.Optional;

/** 评估集成设置（全局单行）。{@code apiTokenCt} 为加密信封，永不明文落库。 */
public interface EvaluationSettingsRepository {

    record Row(boolean enabled, String baseUrl, String apiTokenCt, boolean promoEnabled,
               String promoUrl, String updatedBy) {
    }

    Optional<Row> find();

    void upsert(Row row);
}
