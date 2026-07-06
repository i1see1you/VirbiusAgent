package io.virbius.control.service;

public final class EngineArtifactKeys {

    private static final String PREFIX = "virbius:engine";

    private EngineArtifactKeys() {}

    public static String seqKey(String tenantId) {
        return PREFIX + ":" + tenantId + ":seq";
    }

    public static String pointerKey(String tenantId) {
        return PREFIX + ":" + tenantId + ":pointer";
    }

    public static String blobKey(String tenantId, long revision) {
        return PREFIX + ":" + tenantId + ":r" + revision + ":snapshot";
    }

    public static String blobStagingKey(String tenantId, long revision) {
        return blobKey(tenantId, revision) + ":staging";
    }

    public static String historyKey(String tenantId) {
        return PREFIX + ":" + tenantId + ":history";
    }

    public static String streamKey() {
        return "virbius:engine:cache-reload";
    }
}
