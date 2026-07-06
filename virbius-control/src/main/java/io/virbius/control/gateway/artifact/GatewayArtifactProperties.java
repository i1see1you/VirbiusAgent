package io.virbius.control.gateway.artifact;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "virbius.gateway.artifact")
public class GatewayArtifactProperties {

    private boolean enabled = true;
    /** redis | oss */
    private String storage = "redis";
    private boolean localFallback = false;
    private Redis redis = new Redis();
    private Oss oss = new Oss();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStorage() {
        return storage;
    }

    public void setStorage(String storage) {
        this.storage = storage;
    }

    public boolean isLocalFallback() {
        return localFallback;
    }

    public void setLocalFallback(boolean localFallback) {
        this.localFallback = localFallback;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }

    public Oss getOss() {
        return oss;
    }

    public void setOss(Oss oss) {
        this.oss = oss;
    }

    public boolean usesRedisBlobs() {
        return storage == null || storage.isBlank() || "redis".equalsIgnoreCase(storage.trim());
    }

    public boolean usesOssBlobs() {
        return "oss".equalsIgnoreCase(storage != null ? storage.trim() : "");
    }

    public static class Redis {
        private String blobPrefix = "virbius:artifacts:gateway";
        private String pointerPrefix = "virbius:config:gateway";
        private String ackPrefix = "virbius:ack:gateway";
        private String eventsStream = "virbius:config:events";
        private int retainRevisions = 10;
        private int ackTtlSeconds = 86400;

        public String getBlobPrefix() {
            return blobPrefix;
        }

        public void setBlobPrefix(String blobPrefix) {
            this.blobPrefix = blobPrefix;
        }

        public String getPointerPrefix() {
            return pointerPrefix;
        }

        public void setPointerPrefix(String pointerPrefix) {
            this.pointerPrefix = pointerPrefix;
        }

        public String getAckPrefix() {
            return ackPrefix;
        }

        public void setAckPrefix(String ackPrefix) {
            this.ackPrefix = ackPrefix;
        }

        public String getEventsStream() {
            return eventsStream;
        }

        public void setEventsStream(String eventsStream) {
            this.eventsStream = eventsStream;
        }

        public int getRetainRevisions() {
            return retainRevisions;
        }

        public void setRetainRevisions(int retainRevisions) {
            this.retainRevisions = retainRevisions;
        }

        public int getAckTtlSeconds() {
            return ackTtlSeconds;
        }

        public void setAckTtlSeconds(int ackTtlSeconds) {
            this.ackTtlSeconds = ackTtlSeconds;
        }
    }

    public static class Oss {
        /** PUT target base, e.g. http://127.0.0.1:9000/virbius */
        private String baseUrl = "";
        /** Public URL prefix for pointer, e.g. https://cdn.example.com/virbius */
        private String publicBaseUrl = "";
        private String accessKey = "";
        private String secretKey = "";
        private String authorizationHeader = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getAuthorizationHeader() {
            return authorizationHeader;
        }

        public void setAuthorizationHeader(String authorizationHeader) {
            this.authorizationHeader = authorizationHeader;
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }
}
