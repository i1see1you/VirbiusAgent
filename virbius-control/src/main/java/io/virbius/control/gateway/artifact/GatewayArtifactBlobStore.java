package io.virbius.control.gateway.artifact;

public interface GatewayArtifactBlobStore {

    String storageType();

    void putBlob(String tenantId, long revision, GatewayArtifactPart part, byte[] body);

    byte[] getBlob(GatewayArtifactPointer pointer, GatewayArtifactPart part);

    String locatorFor(String tenantId, long revision, GatewayArtifactPart part);
}
