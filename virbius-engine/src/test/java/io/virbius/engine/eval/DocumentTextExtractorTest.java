package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.engine.config.FileProperties;
import io.virbius.engine.eval.DocumentTextExtractor.ExtractResult;
import io.virbius.engine.eval.DocumentTextExtractor.Status;
import io.virbius.engine.eval.FileTypeDetector.FileType;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;

class DocumentTextExtractorTest {

    private static FileProperties props() {
        return new FileProperties(true, 20L * 1024 * 1024, 5, 50, 8000,
                3500, 400, 32, 10, 64,
                "ovisocr2", "http://127.0.0.1:11434", 30000,
                0.5, 45_000, 1000, 60, 3, 12_000,
                true, 60);
    }

    @Test
    void extractsDocxTextWithEmbeddedImage() throws Exception {
        byte[] docx;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("This is a benign document about project planning.");
            // 100x100 PNG (>= min dim) as embedded picture
            var run = p.createRun();
            var img = new java.awt.image.BufferedImage(100, 100,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            byte[] pngBytes;
            try (var baos = new ByteArrayOutputStream()) {
                javax.imageio.ImageIO.write(img, "png", baos);
                pngBytes = baos.toByteArray();
            }
            try (var in = new java.io.ByteArrayInputStream(pngBytes)) {
                run.addPicture(in, org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                        "pic.png", org.apache.poi.util.Units.toEMU(100), org.apache.poi.util.Units.toEMU(100));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            docx = out.toByteArray();
        }
        ExtractResult r = new DocumentTextExtractor(props()).extract(docx, FileType.DOCX);
        assertEquals(Status.OK, r.status());
        assertTrue(r.text().contains("benign document"));
        assertEquals(1, r.images().size());
        assertFalse(r.imageOverflow());
    }

    @Test
    void extractsDocxText() throws Exception {
        byte[] docx;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("This is a benign document about project planning.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            docx = out.toByteArray();
        }
        ExtractResult r = new DocumentTextExtractor(props()).extract(docx, FileType.DOCX);
        assertEquals(Status.OK, r.status());
        assertTrue(r.text().contains("benign document"));
    }

    @Test
    void extractsPdfText() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Quarterly financial summary for review.");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }
        ExtractResult r = new DocumentTextExtractor(props()).extract(pdf, FileType.PDF);
        assertEquals(Status.OK, r.status());
        assertTrue(r.text().contains("Quarterly"));
    }

    @Test
    void emptyPdfIsScanned() throws Exception {
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());   // blank page, no text
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }
        ExtractResult r = new DocumentTextExtractor(props()).extract(pdf, FileType.PDF);
        assertEquals(Status.SCANNED, r.status());
    }
}
