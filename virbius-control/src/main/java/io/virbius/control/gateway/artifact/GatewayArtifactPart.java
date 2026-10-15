package io.virbius.control.gateway.artifact;

public enum GatewayArtifactPart {
    ACCESS_LISTS("access-lists");

    private final String suffix;

    GatewayArtifactPart(String suffix) {
        this.suffix = suffix;
    }

    public String suffix() {
        return suffix;
    }
}
