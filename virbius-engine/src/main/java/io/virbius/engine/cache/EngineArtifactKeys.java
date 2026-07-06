package io.virbius.engine.cache;

public final class EngineArtifactKeys {

    private static final String PREFIX = "virbius:engine";

    private EngineArtifactKeys() {}

    public static String pointerKey(String tenantId) {
        return PREFIX + ":" + tenantId + ":pointer";
    }

    public static String blobKey(String tenantId, long revision) {
        return PREFIX + ":" + tenantId + ":r" + revision + ":snapshot";
    }
}
