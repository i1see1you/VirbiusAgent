package io.virbius.control.repository;

import io.virbius.control.domain.ConstitutionRule;
import io.virbius.control.domain.ConstitutionTemplate;
import java.util.List;
import java.util.Optional;

public interface ConstitutionRepository {

    // ---- Constitution Rule CRUD ----

    void saveRule(ConstitutionRule rule);

    Optional<ConstitutionRule> findRule(String tenantId, String ruleId, String version);

    Optional<ConstitutionRule> findLatestRule(String tenantId, String ruleId);

    List<ConstitutionRule> listRules(String tenantId, String status);

    List<ConstitutionRule> listActiveRules(String tenantId);

    void updateRuleStatus(String tenantId, String ruleId, String version, String status);

    void deleteRule(String tenantId, String ruleId, String version);

    // ---- Compiled Template CRUD ----

    void saveTemplate(ConstitutionTemplate tmpl);

    Optional<ConstitutionTemplate> findTemplate(String tenantId, String constitutionVersion);

    List<ConstitutionTemplate> listTemplates(String tenantId);

    List<ConstitutionTemplate> listTemplatesByVersion(String tenantId, String constitutionVersion);

    void deleteTemplatesByVersion(String tenantId, String constitutionVersion);
}
