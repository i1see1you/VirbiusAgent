package io.virbius.control.policy;

import io.virbius.control.domain.RuleRevision;
import io.virbius.control.repository.CumulativeRepository;
import io.virbius.control.repository.ListMetaRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** Enrich rules for engine cache (pass-through; script rules carry body as-is). */
@Component
public class PolicyMaterializer {

    private final ListMetaRepository listMetaRepo;
    private final CumulativeRepository cumulativeRepo;

    public PolicyMaterializer(ListMetaRepository listMetaRepo, CumulativeRepository cumulativeRepo) {
        this.listMetaRepo = listMetaRepo;
        this.cumulativeRepo = cumulativeRepo;
    }

    public RuleRevision materialize(String tenantId, RuleRevision rule) {
        return rule;
    }

    public List<RuleRevision> materializeAll(String tenantId, List<RuleRevision> rules) {
        return rules.stream().map(r -> materialize(tenantId, r)).toList();
    }
}
