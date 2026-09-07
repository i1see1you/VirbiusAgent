package io.virbius.engine.eval;

import io.virbius.engine.config.FileProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.util.IOUtils;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Extracts text from PDF/DOCX/DOC via the document text layer (no OCR), and
 * collects embedded images so the caller can OCR them — otherwise injection
 * text hidden inside document images would bypass detection entirely.
 */
@Component
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    public enum Status { OK, ENCRYPTED, SCANNED, ERROR }

    /** Embedded image bytes (PNG re-encode) eligible for OCR. */
    public record EmbeddedImage(byte[] data) {}

    /**
     * @param images        embedded eligible images, capped at maxEmbeddedImagesPerFile
     * @param imageOverflow true if more eligible images existed than the cap allows
     * @param legacyDoc     true for .doc, whose images are not scanned (HWPF limitation) — caller must flag
     */
    public record ExtractResult(String text, Status status, int pages,
                                List<EmbeddedImage> images, boolean imageOverflow, boolean legacyDoc) {
        public ExtractResult(String text, Status status, int pages) {
            this(text, status, pages, List.of(), false, false);
        }
    }

    private final FileProperties props;

    public DocumentTextExtractor(FileProperties props) {
        this.props = props;
        // DOCX is a zip; guard against zip bombs.
        ZipSecureFile.setMinInflateRatio(0.01);
    }

    public ExtractResult extract(byte[] data, FileTypeDetector.FileType type) {
        return switch (type) {
            case PDF -> extractPdf(data);
            case DOCX -> extractDocx(data);
            case DOC -> extractDoc(data);
            default -> new ExtractResult("", Status.ERROR, 0);
        };
    }

    private ExtractResult extractPdf(byte[] data) {
        try (PDDocument doc = Loader.loadPDF(data)) {
            if (doc.isEncrypted()) {
                return new ExtractResult("", Status.ENCRYPTED, 0);
            }
            int total = doc.getNumberOfPages();
            int pages = Math.min(total, props.maxPdfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            String text = stripper.getText(doc);
            ImageCollector collector = new ImageCollector();
            collectPdfImages(doc, pages, collector);
            if (text == null || text.strip().length() < 10) {
                return new ExtractResult("", Status.SCANNED, pages,
                        collector.images, collector.overflow(), false);
            }
            return new ExtractResult(text, Status.OK, pages,
                    collector.images, collector.overflow(), false);
        } catch (Exception e) {
            log.warn("pdf extract failed: {}", e.getMessage());
            return new ExtractResult("", Status.ERROR, 0);
        }
    }

    private void collectPdfImages(PDDocument doc, int pages, ImageCollector collector) {
        for (int p = 0; p < pages && !collector.capped(); p++) {
            PDResources res = doc.getPage(p).getResources();
            if (res == null) {
                continue;
            }
            for (COSName name : res.getXObjectNames()) {
                if (collector.capped()) {
                    break;
                }
                try {
                    if (res.getXObject(name) instanceof PDImageXObject img) {
                        collector.add(img.getImage());
                    }
                } catch (Exception e) {
                    // one broken image must not fail the whole document
                    log.debug("pdf image extract failed on page {}: {}", p + 1, e.getMessage());
                }
            }
        }
        // overflow probe only when at the cap: one more eligible image past it
        if (collector.capped() && findExtraPdfImage(doc, pages, collector.count)) {
            collector.overflow = true;
        }
    }

    private boolean findExtraPdfImage(PDDocument doc, int pages, int eligibleSeen) {
        int count = 0;
        for (int p = 0; p < pages; p++) {
            PDResources res = doc.getPage(p).getResources();
            if (res == null) {
                continue;
            }
            for (COSName name : res.getXObjectNames()) {
                try {
                    if (res.getXObject(name) instanceof PDImageXObject img
                            && isEligible(img.getImage())) {
                        count++;
                        if (count > eligibleSeen) {
                            return true;
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        return false;
    }

    private ExtractResult extractDocx(byte[] data) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();

            Set<String> seen = new LinkedHashSet<>();
            ImageCollector collector = new ImageCollector();
            collectDocxPictures(doc.getAllPictures(), seen, collector);
            for (XWPFHeader h : doc.getHeaderList()) {
                collectDocxPictures(h.getAllPictures(), seen, collector);
            }
            for (XWPFFooter f : doc.getFooterList()) {
                collectDocxPictures(f.getAllPictures(), seen, collector);
            }

            if (text == null || text.isBlank()) {
                return new ExtractResult("", Status.SCANNED, 1,
                        collector.images, collector.overflow(), false);
            }
            return new ExtractResult(text, Status.OK, 1,
                    collector.images, collector.overflow(), false);
        } catch (Exception e) {
            log.warn("docx extract failed: {}", e.getMessage());
            return new ExtractResult("", Status.ERROR, 0);
        }
    }

    private void collectDocxPictures(List<XWPFPictureData> pictures, Set<String> seen,
                                     ImageCollector collector) {
        for (XWPFPictureData pic : pictures) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(pic.getData()));
                if (!isEligible(img)
                        || !seen.add(String.valueOf(pic.getChecksum()))) {
                    continue;
                }
                collector.add(img);
            } catch (Exception e) {
                log.debug("docx picture skipped: {}", e.getMessage());
            }
        }
    }

    private ExtractResult extractDoc(byte[] data) {
        try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(data))) {
            String text = extractor.getText();
            if (text == null || text.isBlank()) {
                return new ExtractResult("", Status.SCANNED, 1, List.of(), false, true);
            }
            return new ExtractResult(text, Status.OK, 1, List.of(), false, true);
        } catch (Exception e) {
            log.warn("doc extract failed: {}", e.getMessage());
            return new ExtractResult("", Status.ERROR, 0);
        }
    }

    private boolean isEligible(BufferedImage img) {
        return img != null
                && img.getWidth() >= props.minEmbeddedImageDim()
                && img.getHeight() >= props.minEmbeddedImageDim();
    }

    /**
     * Accumulates eligible embedded images up to the per-file cap; once capped,
     * further eligible candidates are counted but not stored, and overflow flips
     * to true so the caller can emit FILE_EMBEDDED_LIMIT.
     */
    private final class ImageCollector {
        private final List<EmbeddedImage> images = new ArrayList<>();
        private boolean overflow;
        private int count;

        boolean capped() {
            return count >= props.maxEmbeddedImagesPerFile();
        }

        boolean overflow() {
            return overflow;
        }

        void add(BufferedImage img) {
            count++;
            if (count > props.maxEmbeddedImagesPerFile()) {
                overflow = true;
                return;
            }
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                images.add(new EmbeddedImage(baos.toByteArray()));
            } catch (Exception e) {
                log.debug("image re-encode failed: {}", e.getMessage());
            }
        }
    }
}
