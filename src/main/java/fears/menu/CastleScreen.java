package fears.menu;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * L'écran de chargement entre deux niveaux ({@code L0119bc}) : le château, et la progression
 * du joueur dans la tour.
 *
 * <ul>
 *   <li><b>Image</b> : 320×512 en mémoire (0x50000, 8 plans entrelacés, 0x140 octets par
 *       ligne), dont l'écran ne montre qu'une fenêtre de 256 lignes, choisie par niveau
 *       ({@code L0132c2}, table {@code L0135c6}) — la tour se gravit d'un écran à l'autre.</li>
 *   <li><b>Marques</b> : 32×32 pixels sur 3 plans, copiés au blitter dans les plans 5 à 7
 *       (couleur = index du château | v &lt;&lt; 5, des rouges et des oranges). Chaque niveau fini
 *       porte une marque ({@code L013412}, graphisme 0x290de) ; celui qu'on vient de finir la
 *       reçoit en 5 images ({@code L01338c}, 0x2895e, une image par trame pendant 9 trames).</li>
 *   <li><b>Flèche</b> ({@code L0134b0}) : le fanion 0x287de rebondit 8 lignes au-dessus du
 *       niveau qu'on va jouer, selon la table de 36 décalages {@code L013574}, une position par
 *       VBL ; le blitter recopie le fanion sans effacer, comme l'original.</li>
 *   <li><b>Animations</b> ({@code L013686}) : une zone avance toutes les 2 VBL, soit une image
 *       toutes les 10 VBL pour chacune des 5 zones. Flèche et animations partent 20 trames
 *       après le début ({@code L013338}).</li>
 *   <li><b>Fondus</b> : 64 pas à l'entrée ({@code L015f5a}) et à la sortie ({@code L015f24}).</li>
 * </ul>
 *
 * <p>Sur Amiga, l'écran reste affiché le temps de lire le niveau sur la disquette. [S] Le
 * portage le tient {@link #HOLD} VBL entre les deux fondus.
 */
public final class CastleScreen implements Scene {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 256;
    static final int PICTURE_HEIGHT = 512;
    static final int FADE = 0x41;
    static final int HOLD = 150;
    static final int TABLE = 0x135C6;
    static final int BOB = 0x13574;
    static final int FLAG = 0x287DE;
    static final int APPEAR = 0x2895E;
    static final int DONE = 0x290DE;
    static final int GRAPHIC_BYTES = 0x180;
    /** Trames de L013338 : 9 images d'apparition, puis 11 d'attente avant la flèche. */
    static final int APPEAR_FRAMES = 9;
    static final int ARROW_START = APPEAR_FRAMES + 11;

    private record Zone(int x, int y, int[][] images, int width, int height) {
    }

    private final LinkedImage program;
    private final int[] castle = new int[WIDTH * PICTURE_HEIGHT];
    private final int[] overlay = new int[WIDTH * PICTURE_HEIGHT];
    private final int[] palette = new int[256];
    private final List<Zone> zones = new ArrayList<>();
    private final int[] zoneFrame = new int[5];
    private final int[] frame = new int[WIDTH * HEIGHT];
    private final int windowTop;
    private final int[] current;
    private final int[] next;
    private int clock;
    private int zoneTick;
    private int zoneIndex;
    private int bob;

    /**
     * @param level le niveau qu'on va jouer, de 1 à 30
     */
    public CastleScreen(MenuAssets assets, Path resources, int level) throws IOException {
        this.program = assets.program;
        Path pictures = resources.resolve("pictures");
        BufferedImage picture = read(pictures.resolve("d1_00d7ec_chateau_320x512.png"));
        picture.getRaster().getPixels(0, 0, WIDTH, PICTURE_HEIGHT, castle);
        IndexColorModel model = (IndexColorModel) picture.getColorModel();
        for (int i = 0; i < Math.min(256, model.getMapSize()); i++) {
            palette[i] = model.getRGB(i) & 0xFFFFFF;
        }
        Path animations = pictures.resolve("chateau_animations");
        List<String> rows = Files.readAllLines(animations.resolve("positions.csv"));
        for (int zone = 0; zone < 5; zone++) {
            int[][] images = new int[3][];
            int x = 0;
            int y = 0;
            int w = 0;
            int h = 0;
            for (String row : rows.subList(1, rows.size())) {
                String[] f = row.split(";");
                if (Integer.parseInt(f[0]) == zone) {
                    x = Integer.parseInt(f[2]);
                    y = Integer.parseInt(f[3]);
                    w = Integer.parseInt(f[4]);
                    h = Integer.parseInt(f[5]);
                    BufferedImage image = read(animations.resolve(f[6]));
                    images[Integer.parseInt(f[1])] = image.getRaster().getPixels(0, 0, w, h, (int[]) null);
                }
            }
            zones.add(new Zone(x, y, images, w, h));
        }

        // L0132c2 : niveau k (0..29) → fenêtre de l'entrée k + 1, marque animée sur l'entrée k,
        // flèche sur l'entrée k + 1.
        int k = Math.max(0, Math.min(29, level - 1));
        windowTop = Math.max(0, Math.min(PICTURE_HEIGHT - HEIGHT, word(3 * k + 3)));
        current = position(k);
        next = position(k + 1);
        // L013412 : une marque sur chaque niveau déjà fini.
        for (int i = 0; i < k; i++) {
            int[] p = position(i);
            if (p != null) {
                blit(DONE, p[0], p[1]);
            }
        }
    }

    private static BufferedImage read(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null || !(image.getColorModel() instanceof IndexColorModel)) {
            throw new IOException("image indexée attendue : " + file);
        }
        return image;
    }

    private int word(int index) {
        return program.u16(TABLE + 2 * index);
    }

    /** Position (x, ligne) de l'entrée i de la table, ou null si elle est vide (x et y nuls). */
    private int[] position(int i) {
        int x = word(3 * i + 1);
        int y = word(3 * i + 2);
        if (x == 0 && y == 0) {
            return null;
        }
        return new int[]{x * 16, y * 8};
    }

    /** Copie au blitter d'un graphisme 32×32 de 3 plans (12 octets par ligne) dans les plans 5 à 7. */
    private void blit(int graphic, int x0, int y0) {
        for (int y = 0; y < 32; y++) {
            int line = y0 + y;
            if (line < 0 || line >= PICTURE_HEIGHT) {
                continue;
            }
            for (int x = 0; x < 32; x++) {
                int value = 0;
                for (int plane = 0; plane < 3; plane++) {
                    int bits = program.u8(graphic + y * 12 + plane * 4 + (x >> 3));
                    value |= ((bits >> (7 - (x & 7))) & 1) << plane;
                }
                int px = x0 + x;
                if (px < WIDTH) {
                    overlay[line * WIDTH + px] = value;
                }
            }
        }
    }

    public boolean finished() {
        return clock >= 2 * FADE + HOLD;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    @Override
    public float pixelAspect() {
        return 1f;
    }

    @Override
    public int[] frame() {
        return frame;
    }

    @Override
    public void tick() {
        clock++;
        if (clock <= APPEAR_FRAMES && current != null) {
            // L013338 : d0 de 8 à 0, image (d0 >> 1).
            int d0 = APPEAR_FRAMES - clock;
            blit(APPEAR + (d0 >> 1) * GRAPHIC_BYTES, current[0], current[1]);
        }
        if (clock > ARROW_START) {
            animateZones();
            if (next != null) {
                blit(FLAG, next[0], next[1] - 8 + program.s16(BOB + 2 * bob));
                bob = (bob + 1) % 36;
            }
        }
        compose();
    }

    /** {@code L013686} : toutes les 2 VBL, la zone suivante passe à son image suivante. */
    private void animateZones() {
        if (++zoneTick < 2) {
            return;
        }
        zoneTick = 0;
        zoneIndex = (zoneIndex + 1) % zones.size();
        zoneFrame[zoneIndex] = (zoneFrame[zoneIndex] + 1) % 3;
        Zone zone = zones.get(zoneIndex);
        int[] pixels = zone.images()[zoneFrame[zoneIndex]];
        if (pixels == null) {
            return;
        }
        for (int y = 0; y < zone.height(); y++) {
            for (int x = 0; x < zone.width(); x++) {
                int px = zone.x() + x;
                int py = zone.y() + y;
                if (px < WIDTH && py < PICTURE_HEIGHT) {
                    castle[py * WIDTH + px] = pixels[y * zone.width() + x] & 31;
                }
            }
        }
    }

    private void compose() {
        int level = clock < FADE ? clock : clock > FADE + HOLD ? Math.max(0, 2 * FADE + HOLD - clock) : FADE;
        level = Math.min(64, level);
        for (int y = 0; y < HEIGHT; y++) {
            int source = (windowTop + y) * WIDTH;
            for (int x = 0; x < WIDTH; x++) {
                int index = (castle[source + x] & 31) | (overlay[source + x] << 5);
                int c = palette[index];
                int r = ((c >> 16) & 0xFF) * level / 64;
                int g = ((c >> 8) & 0xFF) * level / 64;
                int b = (c & 0xFF) * level / 64;
                frame[y * WIDTH + x] = (r << 16) | (g << 8) | b;
            }
        }
    }
}
