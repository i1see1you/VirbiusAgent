package io.virbius.policy;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/**
 * Image hashing for the blacklist: 64-bit pHash (DCT-based perceptual hash,
 * robust to re-encoding / rescaling / light tampering) plus exact SHA-256 for
 * the zero-false-positive fast path.
 */
public final class ImageHasher {

    private static final int SIZE = 32;
    private static final int LOW = 8;

    private ImageHasher() {}

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return 64-bit pHash, or null when the bytes are not a decodable image
     */
    public static Long phash(byte[] imageBytes) {
        BufferedImage img = decode(imageBytes);
        return img == null ? null : phash(img);
    }

    public static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static BufferedImage decode(byte[] bytes) {
        try {
            // ImageIO returns the first frame for multi-frame formats (GIF)
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private static long phash(BufferedImage source) {
        BufferedImage gray = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, SIZE, SIZE, null);
        g.dispose();

        double[][] px = new double[SIZE][SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                px[x][y] = gray.getRaster().getSample(x, y, 0);
            }
        }

        double[][] dct = dct2d(px);
        double[] low = new double[LOW * LOW];
        int idx = 0;
        for (int v = 0; v < LOW; v++) {
            for (int u = 0; u < LOW; u++) {
                low[idx++] = dct[u][v];
            }
        }
        // median over non-DC coefficients
        double[] sorted = new double[low.length - 1];
        System.arraycopy(low, 1, sorted, 0, sorted.length);
        java.util.Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];

        long hash = 0;
        for (double coef : low) {
            hash = (hash << 1) | (coef > median ? 1 : 0);
        }
        return hash;
    }

    private static double[][] dct2d(double[][] px) {
        int n = SIZE;
        double[][] cosX = new double[n][n];
        double[][] cosY = new double[n][n];
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                cosX[k][i] = Math.cos((2 * i + 1) * k * Math.PI / (2 * n));
                cosY[k][i] = Math.cos((2 * i + 1) * k * Math.PI / (2 * n));
            }
        }
        double c0 = Math.sqrt(0.5);
        double[][] out = new double[n][n];
        for (int u = 0; u < n; u++) {
            for (int v = 0; v < n; v++) {
                double cu = u == 0 ? c0 : 1.0;
                double cv = v == 0 ? c0 : 1.0;
                double sum = 0;
                for (int x = 0; x < n; x++) {
                    for (int y = 0; y < n; y++) {
                        sum += px[x][y] * cosX[u][x] * cosY[v][y];
                    }
                }
                out[u][v] = 0.25 * cu * cv * sum;
            }
        }
        return out;
    }
}
