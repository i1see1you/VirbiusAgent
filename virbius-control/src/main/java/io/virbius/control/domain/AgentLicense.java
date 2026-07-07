package io.virbius.control.domain;

import java.time.Instant;
import java.util.List;

public class AgentLicense {
    private String appId;
    private String tenantId;
    private List<String> allowedTools;
    private List<String> allowedScenes;
    private int riskQuota;
    private int toolRateLimit;
    private Instant expiry;
    private Instant issuedAt;
    private String status;
    private String signature;

    public AgentLicense() {}

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }

    public List<String> getAllowedScenes() { return allowedScenes; }
    public void setAllowedScenes(List<String> allowedScenes) { this.allowedScenes = allowedScenes; }

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

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
}
