package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageHasherTest {

    /** Structured test image: dark bg + white circle + rectangle, so pHash is stable. */
    private static BufferedImage pattern(int size, int variant) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 40, 40));
        g.fillRect(0, 0, size, size);
        g.setColor(Color.WHITE);
        g.fillOval(size / 8, size / 8, size / 3, size / 3);
        if (variant == 0) {
            g.fillRect(size / 2, size / 2, size / 3, size / 4);
        } else {
            // variant 1: structurally different layout
            g.fillRect(size / 16, size / 2, size / 8, size / 3);
            g.fillOval(size / 2, size / 16, size / 4, size / 2);
        }
        g.dispose();
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, format, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void stableAcrossFormatAndScale() {
        Long a = ImageHasher.phash(encode(pattern(320, 0), "png"));
        Long pngSmall = ImageHasher.phash(encode(pattern(160, 0), "png"));
        Long jpegSmall = ImageHasher.phash(encode(pattern(160, 0), "jpg"));
        Long jpegBig = ImageHasher.phash(encode(pattern(640, 0), "jpg"));
        assertNotNull(a);
        assertTrue(ImageHasher.hamming(a, pngSmall) <= 10, "png rescale");
        assertTrue(ImageHasher.hamming(a, jpegSmall) <= 10, "jpeg re-encode + rescale");
        assertTrue(ImageHasher.hamming(a, jpegBig) <= 10, "jpeg upscale");
    }

    @Test
    void differentImagesAreFarApart() {
        Long a = ImageHasher.phash(encode(pattern(320, 0), "png"));
        Long b = ImageHasher.phash(encode(pattern(320, 1), "png"));
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(ImageHasher.hamming(a, b) >= 16, "different patterns must be far apart");
    }

    @Test
    void undecodableBytesReturnNull() {
        assertNull(ImageHasher.phash("not an image".getBytes()));
        assertNull(ImageHasher.phash(new byte[0]));
    }

    @Test
    void sha256IsStable() {
        assertEquals(ImageHasher.sha256Hex(new byte[] {1, 2, 3}),
                ImageHasher.sha256Hex(new byte[] {1, 2, 3}));
    }
}
