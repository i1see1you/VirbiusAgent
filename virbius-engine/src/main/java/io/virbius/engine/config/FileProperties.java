package io.virbius.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for multimodal file guard (image OCR + PDF/Word text extraction).
 */
@ConfigurationProperties(prefix = "virbius.file")
public record FileProperties(
        boolean enabled,
        long maxBytes,
        int maxFilesPerRequest,
        int maxPdfPages,
        int extractMaxChars,
        int chunkSize,
        int chunkOverlap,
        int maxChunks,
        int maxEmbeddedImagesPerFile,
        int minEmbeddedImageDim,
        String ocrModel,
        String ocrBaseUrl,
        int ocrTimeoutMs,
        double ocrBreakerFailureRate,
        long ocrBreakerWaitMs,
        int ocrCacheMaxEntries,
        long ocrCacheTtlMinutes,
        int attachmentWorkers,
        long attachmentTimeoutMs,
        boolean imageBlacklistEnabled,
        int blacklistRefreshSeconds) {

    public FileProperties {
        if (maxBytes <= 0) {
            maxBytes = 20L * 1024 * 1024;   // 20MB
        }
        if (maxFilesPerRequest <= 0) {
            maxFilesPerRequest = 5;
        }
        if (maxPdfPages <= 0) {
            maxPdfPages = 50;
        }
        if (extractMaxChars <= 0) {
            extractMaxChars = 8000;
        }
        if (chunkSize <= 0) {
            chunkSize = 3500;
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            chunkOverlap = chunkSize / 8;
        }
        if (maxChunks <= 0) {
            maxChunks = 32;
        }
        if (maxEmbeddedImagesPerFile <= 0) {
            maxEmbeddedImagesPerFile = 10;
        }
        if (minEmbeddedImageDim <= 0) {
            minEmbeddedImageDim = 64;
        }
        if (ocrModel == null || ocrModel.isBlank()) {
            ocrModel = "ovisocr2";
        }
        if (ocrBaseUrl == null || ocrBaseUrl.isBlank()) {
            ocrBaseUrl = "http://127.0.0.1:11434";
        }
        if (ocrTimeoutMs <= 0) {
            ocrTimeoutMs = 30000;
        }
        if (ocrBreakerFailureRate <= 0 || ocrBreakerFailureRate > 1) {
            ocrBreakerFailureRate = 0.5;
        }
        if (ocrBreakerWaitMs <= 0) {
            ocrBreakerWaitMs = 45_000;
        }
        if (ocrCacheMaxEntries <= 0) {
            ocrCacheMaxEntries = 1000;
        }
        if (ocrCacheTtlMinutes <= 0) {
            ocrCacheTtlMinutes = 60;
        }
        if (attachmentWorkers <= 0) {
            attachmentWorkers = 3;
        }
        if (attachmentTimeoutMs <= 0) {
            attachmentTimeoutMs = 12_000;
        }
        if (blacklistRefreshSeconds < 0) {
            blacklistRefreshSeconds = 60;
        }
    }
}
