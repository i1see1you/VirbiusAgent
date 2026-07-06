package io.virbius.control.gateway.artifact;

public enum GatewayArtifactPart {
    ACCESS_LISTS("access-lists"),
    SCENE_REGISTRY("scene-registry");

    private final String suffix;

    GatewayArtifactPart(String suffix) {
        this.suffix = suffix;
    }

    public String suffix() {
        return suffix;
    }
}
