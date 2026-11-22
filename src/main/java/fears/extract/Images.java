package fears.extract;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Outils d'image : palettes Amiga et écriture PNG indexée. */
final class Images {
    private Images() {
    }

    /** Palette 12 bits (mots $0RGB big-endian). */
    static int[] rgb4Palette(byte[] d, int off, int count) {
        int[] p = new int[256];
        for (int i = 0; i < count; i++) {
            int c = Be.w(d, off + 2 * i);
            int r = ((c >> 8) & 15) * 17, g = ((c >> 4) & 15) * 17, b = (c & 15) * 17;
            p[i] = (r << 16) | (g << 8) | b;
        }
        return p;
    }

    static int[] grayPalette(int levels) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            int v = Math.min(255, i * 255 / Math.max(1, levels - 1));
            p[i] = (v << 16) | (v << 8) | v;
        }
        return p;
    }

    static BufferedImage indexed(int w, int h, int[] pal, boolean transparent0) {
        byte[] r = new byte[256], g = new byte[256], b = new byte[256], a = new byte[256];
        for (int i = 0; i < 256; i++) {
            r[i] = (byte) (pal[i] >> 16);
            g[i] = (byte) (pal[i] >> 8);
            b[i] = (byte) pal[i];
            a[i] = (byte) (transparent0 && i == 0 ? 0 : 255);
        }
        IndexColorModel cm = new IndexColorModel(8, 256, r, g, b, a);
        return new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, cm);
    }

    /** Copie {@code w*h} pixels 8 bits (ligne par ligne) depuis {@code src[off]} vers l'image en (x, y). */
    static void blit(BufferedImage img, byte[] src, int off, int w, int h, int x, int y) {
        var raster = img.getRaster();
        int[] row = new int[w];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int k = off + j * w + i;
                row[i] = k < src.length ? src[k] & 0xff : 0;
            }
            raster.setPixels(x, y + j, w, 1, row);
        }
    }

    /**
     * Copie une image rangée <em>par colonnes</em> ({@code src[off + x·h + y]}), comme le
     * moteur range les objets 32×32 (MOTEUR.md, rendu §6.4 : texel(u,v) = img[u·32 + v]).
     */
    static void blitColumns(BufferedImage img, byte[] src, int off, int w, int h, int x, int y) {
        var raster = img.getRaster();
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                int k = off + i * h + j;
                raster.setSample(x + i, y + j, 0, k < src.length ? src[k] & 0xff : 0);
            }
        }
    }

    static void write(BufferedImage img, Path p) throws IOException {
        ImageIO.write(img, "png", p.toFile());
    }

    /** Planche de tuiles carrées {@code tile×tile} prises à la suite dans {@code src}. */
    static BufferedImage tileSheet(byte[] src, int off, int count, int tile, int cols, int[] pal, boolean transparent0) {
        int rows = (count + cols - 1) / cols;
        BufferedImage img = indexed(cols * tile, rows * tile, pal, transparent0);
        for (int t = 0; t < count; t++) {
            blit(img, src, off + t * tile * tile, tile, tile, (t % cols) * tile, (t / cols) * tile);
        }
        return img;
    }
}
