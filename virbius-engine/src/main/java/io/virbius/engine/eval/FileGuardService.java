package io.virbius.engine.eval;

import io.virbius.engine.config.FileProperties;
import io.virbius.engine.eval.DocumentTextExtractor.ExtractResult;
import io.virbius.engine.eval.FileTypeDetector.FileType;
import io.virbius.engine.eval.PromptInjectionDetector.InjectionDetectionResult;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static io.virbius.engine.eval.FileSignals.FILE_EMBEDDED_LIMIT;
import static io.virbius.engine.eval.FileSignals.FILE_EMPTY;
import static io.virbius.engine.eval.FileSignals.FILE_INVALID;
import static io.virbius.engine.eval.FileSignals.FILE_INTERNAL_ERROR;
import static io.virbius.engine.eval.FileSignals.FILE_LEGACY_DOC;
import static io.virbius.engine.eval.FileSignals.FILE_LIMIT;
import static io.virbius.engine.eval.FileSignals.FILE_NO_TEXT;
import static io.virbius.engine.eval.FileSignals.FILE_OCR_TIMEOUT;
import static io.virbius.engine.eval.FileSignals.FILE_OCR_UNAVAILABLE;
import static io.virbius.engine.eval.FileSignals.FILE_TOO_LARGE;
import static io.virbius.engine.eval.FileSignals.FILE_TRUNCATED;
import static io.virbius.engine.eval.FileSignals.FILE_UNKNOWN;
import static io.virbius.engine.eval.FileSignals.block;
import static io.virbius.engine.eval.FileSignals.injection;
import static io.virbius.engine.eval.FileSignals.review;

/**
 * Multimodal attachment guard: extract text (OCR for images, text layer +
 * embedded-image OCR for PDF/Word), chunk it (full coverage, no head-only
 * truncation), and feed each chunk as bare content to the existing
 * PromptInjectionDetector.
 *
 * <p>It also collects image-blacklist match EVIDENCE (best hit per list,
 * merged across all images incl. document-embedded ones) and hands it to the
 * orchestrator, which exposes it to groovy rules via
 * {@code imageMatch(listName)} — thresholds and actions stay in rule
 * scripts, exactly like keyword lists.
 *
 * <p>Attachments are checked in parallel on a dedicated bounded executor with
 * a per-attachment timeout budget, so attachment detection cannot stall the
 * evaluate path. Every degraded path (unsupported type, no text, OCR outage,
 * timeout, truncation) emits a review signal — never a silent allow.
 */
@Component
public class FileGuardService {

    private static final Logger log = LoggerFactory.getLogger(FileGuardService.class);

    private final FileProperties props;
    private final OcrClient ocrClient;
    private final DocumentTextExtractor docExtractor;
    private final PromptInjectionDetector injectionDetector;
    private final ExecutorService attachmentExecutor;
    private final ImageBlacklistService imageBlacklist;

    public FileGuardService(FileProperties props, OcrClient ocrClient,
                            DocumentTextExtractor docExtractor,
                            PromptInjectionDetector injectionDetector,
                            ExecutorService attachmentExecutor,
                            ImageBlacklistService imageBlacklist) {
        this.props = props;
        this.ocrClient = ocrClient;
        this.docExtractor = docExtractor;
        this.injectionDetector = injectionDetector;
        this.attachmentExecutor = attachmentExecutor;
        this.imageBlacklist = imageBlacklist;
    }

    /** Attachment-phase outcome: degraded-path signals + image match evidence for rules. */
    public record FileGuardResult(List<SignalDto> signals,
                                  Map<String, ImageBlacklistService.BlacklistHit> imageEvidence) {
        public FileGuardResult {
            signals = signals != null ? List.copyOf(signals) : List.of();
            imageEvidence = imageEvidence != null ? Map.copyOf(imageEvidence) : Map.of();
        }
    }

    /**
     * Process attachments (in parallel): file-guard signals now, blacklist
     * evidence for the rule phase.
     */
    public FileGuardResult check(String tenantId, List<AttachmentDto> attachments) {
        List<SignalDto> signals = new ArrayList<>();
        Map<String, ImageBlacklistService.BlacklistHit> evidence = new HashMap<>();
        if (attachments == null || attachments.isEmpty() || !props.enabled()) {
            return new FileGuardResult(signals, evidence);
        }
        if (attachments.size() > props.maxFilesPerRequest()) {
            signals.add(review(FILE_LIMIT, "too many files"));
            return new FileGuardResult(signals, evidence);
        }
        List<CompletableFuture<OneResult>> futures = attachments.stream()
                .map(att -> CompletableFuture
                        .supplyAsync(() -> checkOne(tenantId, att), attachmentExecutor)
                        .orTimeout(props.attachmentTimeoutMs(), TimeUnit.MILLISECONDS))
                .toList();
        for (int i = 0; i < futures.size(); i++) {
            joinSafe(futures.get(i), attachments.get(i), i, signals, evidence);
        }
        return new FileGuardResult(signals, evidence);
    }

    private void joinSafe(CompletableFuture<OneResult> future, AttachmentDto att, int idx,
                          List<SignalDto> signals,
                          Map<String, ImageBlacklistService.BlacklistHit> evidence) {
        try {
            OneResult r = future.join();
            signals.addAll(r.signals());
            r.imageEvidence().forEach((list, hit) ->
                    evidence.merge(list, hit, ImageBlacklistService::better));
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                future.cancel(true);
                log.warn("attachment check timed out: idx={} name={}", idx, att.name());
                signals.add(review(FILE_OCR_TIMEOUT, "attachment " + idx + " exceeded budget"));
            } else {
                log.warn("attachment check failed: idx={} name={}", idx, att.name(), e.getCause());
                signals.add(review(FILE_INTERNAL_ERROR, "attachment " + idx + " internal error"));
            }
        }
    }

    private record OneResult(List<SignalDto> signals,
                             Map<String, ImageBlacklistService.BlacklistHit> imageEvidence) {}

    private OneResult checkOne(String tenantId, AttachmentDto att) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(att.dataBase64());
        } catch (IllegalArgumentException e) {
            return new OneResult(List.of(review(FILE_INVALID, "invalid base64")), Map.of());
        }
        if (bytes.length == 0) {
            return new OneResult(List.of(review(FILE_EMPTY, "empty file")), Map.of());
        }
        if (bytes.length > props.maxBytes()) {
            return new OneResult(
                    List.of(block(FILE_TOO_LARGE, "file exceeds size limit")), Map.of());
        }

        FileType type = FileTypeDetector.detect(bytes);
        if (type == FileType.UNKNOWN) {
            return new OneResult(List.of(review(FILE_UNKNOWN, "unsupported file type")), Map.of());
        }

        List<SignalDto> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Map<String, ImageBlacklistService.BlacklistHit> evidence = new HashMap<>();
        collectEvidence(tenantId, bytes, evidence);

        if (type == FileType.IMAGE) {
            appendOcrText(ocrClient.extract(bytes), text, out);
        } else {
            ExtractResult r = docExtractor.extract(bytes, type);
            switch (r.status()) {
                case OK -> text.append(r.text());
                case SCANNED, ENCRYPTED, ERROR -> { /* no text layer → review below unless images saved us */ }
            }
            if (r.legacyDoc()) {
                out.add(review(FILE_LEGACY_DOC, "legacy .doc images not scanned"));
            }
            if (r.imageOverflow()) {
                out.add(review(FILE_EMBEDDED_LIMIT, "embedded images beyond scan limit"));
            }
            for (DocumentTextExtractor.EmbeddedImage img : r.images()) {
                collectEvidence(tenantId, img.data(), evidence);
                appendOcrText(ocrClient.extract(img.data()), text, out);
            }
        }

        String content = text.toString().strip();
        if (content.isEmpty()) {
            // OCR/doc extraction produced nothing; FILE_OCR_UNAVAILABLE (if any) already
            // explains an outage, otherwise flag as no-text for human review
            if (out.isEmpty()) {
                out.add(review(FILE_NO_TEXT, "no extractable text"));
            }
            return new OneResult(out, evidence);
        }

        detectChunked(content, out);
        return new OneResult(out, evidence);
    }

    /** Best-per-list blacklist evidence for one image (exact/phash, no policy). */
    private void collectEvidence(String tenantId, byte[] imageBytes,
                                 Map<String, ImageBlacklistService.BlacklistHit> evidence) {
        imageBlacklist.matchAll(tenantId, imageBytes)
                .forEach((list, hit) -> evidence.merge(list, hit, ImageBlacklistService::better));
    }

    private void appendOcrText(OcrResult ocr, StringBuilder text, List<SignalDto> out) {
        switch (ocr.status()) {
            case OK -> text.append(OcrPostProcessor.clean(ocr.text())).append('\n');
            case EMPTY -> { /* image had no text */ }
            case FAILED, BREAKER_OPEN -> out.add(review(FILE_OCR_UNAVAILABLE, ocr.status().name()));
        }
    }

    /**
     * Chunked full-coverage detection: overlapping windows over the entire text
     * (deduped), each sent as bare content. Replaces the old head-only
     * substring truncation, which an attacker could bypass by hiding the
     * payload past the cut.
     */
    private void detectChunked(String content, List<SignalDto> out) {
        Chunking c = chunkText(content);
        if (c.truncated()) {
            out.add(review(FILE_TRUNCATED, "text exceeds " + props.maxChunks() + " chunks"));
        }
        int total = c.chunks().size();
        for (int i = 0; i < total; i++) {
            InjectionDetectionResult r = injectionDetector.detect(c.chunks().get(i));
            if (r.hit()) {
                log.info("attachment injection detected: chunk {}/{}", i + 1, total);
                out.add(injection(r.riskDelta()));
                return;   // one deny signal is enough
            }
        }
    }

    record Chunking(List<String> chunks, boolean truncated) {}

    Chunking chunkText(String text) {
        int size = props.chunkSize();
        int overlap = props.chunkOverlap();
        int step = Math.max(1, size - overlap);
        int hardCap = size * props.maxChunks();

        String bounded = text.length() > hardCap ? text.substring(0, hardCap) : text;
        boolean truncated = text.length() > hardCap;

        Set<String> unique = new LinkedHashSet<>();
        for (int off = 0; off < bounded.length(); off += step) {
            String chunk = bounded.substring(off, Math.min(off + size, bounded.length()));
            if (unique.add(chunk) && unique.size() >= props.maxChunks()) {
                // stop before generating a chunk beyond the limit
                if (off + step < bounded.length()) {
                    truncated = true;
                }
                break;
            }
        }
        return new Chunking(List.copyOf(unique), truncated);
    }
}
