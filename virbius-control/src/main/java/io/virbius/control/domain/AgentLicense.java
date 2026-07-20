package io.virbius.control.domain;

import java.time.Instant;
import java.util.List;

public class AgentLicense {
    private String appId;
    private String tenantId;
    private List<String> allowedTools;
    private int riskQuota;
    private int toolRateLimit;
    private Instant expiry;
    private Instant issuedAt;
    private String status;
    private String signatureHash;
    private String agentName;
    private String description;
    private String agentAid;

    public AgentLicense() {}

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }

    public int getRiskQuota() { return riskQuota; }
    public void setRiskQuota(int riskQuota) { this.riskQuota = riskQuota; }

    public int getToolRateLimit() { return toolRateLimit; }
    public void setToolRateLimit(int toolRateLimit) { this.toolRateLimit = toolRateLimit; }

    public Instant getExpiry() { return expiry; }
    public void setExpiry(Instant expiry) { this.expiry = expiry; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSignatureHash() { return signatureHash; }
    public void setSignatureHash(String signatureHash) { this.signatureHash = signatureHash; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAgentAid() { return agentAid; }
    public void setAgentAid(String agentAid) { this.agentAid = agentAid; }
}
