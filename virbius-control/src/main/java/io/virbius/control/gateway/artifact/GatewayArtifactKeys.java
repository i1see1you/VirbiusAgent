package io.virbius.control.gateway.artifact;

public final class GatewayArtifactKeys {

    private GatewayArtifactKeys() {}

    public static String seqKey(String pointerPrefix, String tenantId) {
        return pointerPrefix + ":" + tenantId + ":seq";
    }

    public static String pointerKey(String pointerPrefix, String tenantId) {
        return pointerPrefix + ":" + tenantId;
    }

    public static String historyKey(String blobPrefix, String tenantId) {
        return blobPrefix + ":" + tenantId + ":history";
    }

    public static String blobKey(String blobPrefix, String tenantId, long revision, GatewayArtifactPart part) {
        return blobPrefix + ":" + tenantId + ":r" + revision + ":" + part.suffix();
    }

    public static String blobStagingKey(String blobPrefix, String tenantId, long revision, GatewayArtifactPart part) {
        return blobKey(blobPrefix, tenantId, revision, part) + ":staging";
    }

    public static String ackKeyPrefix(String ackPrefix, String tenantId) {
        return ackPrefix + ":" + tenantId + ":";
    }

    public static String ackKey(String ackPrefix, String tenantId, String nodeId) {
        return ackPrefix + ":" + tenantId + ":" + nodeId;
    }
}
