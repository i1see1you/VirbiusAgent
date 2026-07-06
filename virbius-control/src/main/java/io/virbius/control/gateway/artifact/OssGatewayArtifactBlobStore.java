package io.virbius.control.gateway.artifact;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OssGatewayArtifactBlobStore implements GatewayArtifactBlobStore {

    private static final Logger log = LoggerFactory.getLogger(OssGatewayArtifactBlobStore.class);

    private final GatewayArtifactProperties properties;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OssGatewayArtifactBlobStore(GatewayArtifactProperties properties) {
        this.properties = properties;
    }

    @Override
    public String storageType() {
        return "oss";
    }

    @Override
    public void putBlob(String tenantId, long revision, GatewayArtifactPart part, byte[] body) {
        if (!properties.getOss().configured()) {
            throw new IllegalStateException("virbius.gateway.artifact.oss.base-url not configured");
        }
        String url = objectUrl(tenantId, revision, part);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
            applyAuth(builder);
            HttpResponse<Void> resp = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("OSS PUT failed status=" + resp.statusCode() + " url=" + url);
            }
        } catch (Exception ex) {
            log.warn("oss blob publish failed: {}", ex.getMessage());
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("OSS PUT failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public byte[] getBlob(GatewayArtifactPointer pointer, GatewayArtifactPart part) {
        String url = part == GatewayArtifactPart.ACCESS_LISTS ? pointer.accessListsUrl() : pointer.sceneRegistryUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("missing oss url for " + part);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            applyAuth(builder);
            HttpResponse<byte[]> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 300) {
                throw new IllegalStateException("OSS GET failed status=" + resp.statusCode());
            }
            return resp.body();
        } catch (Exception ex) {
            if (ex instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("OSS GET failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public String locatorFor(String tenantId, long revision, GatewayArtifactPart part) {
        return publicUrl(tenantId, revision, part);
    }

    public String objectPath(String tenantId, long revision, GatewayArtifactPart part) {
        return tenantId + "/gateway/r" + revision + "/" + part.suffix() + ".json";
    }

    public String objectUrl(String tenantId, long revision, GatewayArtifactPart part) {
        String base = trimSlash(properties.getOss().getBaseUrl());
        return base + "/" + objectPath(tenantId, revision, part);
    }

    public String publicUrl(String tenantId, long revision, GatewayArtifactPart part) {
        String pub = properties.getOss().getPublicBaseUrl();
        if (pub != null && !pub.isBlank()) {
            return trimSlash(pub) + "/" + objectPath(tenantId, revision, part);
        }
        return objectUrl(tenantId, revision, part);
    }

    private void applyAuth(HttpRequest.Builder builder) {
        GatewayArtifactProperties.Oss oss = properties.getOss();
        if (oss.getAuthorizationHeader() != null && !oss.getAuthorizationHeader().isBlank()) {
            builder.header("Authorization", oss.getAuthorizationHeader());
            return;
        }
        if (oss.getAccessKey() != null
                && !oss.getAccessKey().isBlank()
                && oss.getSecretKey() != null
                && !oss.getSecretKey().isBlank()) {
            String token = Base64.getEncoder()
                    .encodeToString((oss.getAccessKey() + ":" + oss.getSecretKey()).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }
    }

    private static String trimSlash(String base) {
        if (base == null) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
