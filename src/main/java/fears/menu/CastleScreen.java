package fears.menu;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * L'écran de chargement entre deux niveaux ({@code L0119bc}) : le château (fichier
 * {@code d1_00d7ec}, 320×512 basse résolution entrelacée, palette du menu à 0x1660e), avec
 * cinq zones animées de trois images chacune (table de {@code 0x13686}).
 *
 * <p>Sur Amiga, l'écran reste affiché le temps de lire le niveau sur la disquette. Ici :
 * fondu d'entrée de 64 images, deux secondes, fondu de sortie de 64 images. [S] La cadence
 * des animations (une image toutes les 8 VBL) et la durée d'affichage ne sont pas encore
 * relevées dans le code.
 */
public final class CastleScreen implements Scene {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 512;
    static final int FADE = 64;
    static final int HOLD = 100;
    static final int FRAME_TIME = 8;

    private record Zone(int x, int y, int[][] images, int width, int height) {
    }

    private final int[] castle;
    private final List<Zone> zones = new ArrayList<>();
    private final int[] frame = new int[WIDTH * HEIGHT];
    private int clock;

    public CastleScreen(Path resources) throws IOException {
        Path pictures = resources.resolve("pictures");
        castle = argb(pictures.resolve("d1_00d7ec_chateau_320x512.png"));
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
                    int image = Integer.parseInt(f[1]);
                    x = Integer.parseInt(f[2]);
                    y = Integer.parseInt(f[3]);
                    w = Integer.parseInt(f[4]);
                    h = Integer.parseInt(f[5]);
                    images[image] = argb(animations.resolve(f[6]));
                }
            }
            zones.add(new Zone(x, y, images, w, h));
        }
    }

    private static int[] argb(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("image illisible : " + file);
        }
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
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

    /** Un pixel basse résolution entrelacé est deux fois plus large que haut. */
    @Override
    public float pixelAspect() {
        return 0.5f;
    }

    @Override
    public int[] frame() {
        return frame;
    }

    @Override
    public void tick() {
        clock++;
        int level = clock < FADE ? clock : clock > FADE + HOLD ? Math.max(0, 2 * FADE + HOLD - clock) : FADE;
        System.arraycopy(castle, 0, frame, 0, frame.length);
        int image = (clock / FRAME_TIME) % 3;
        for (Zone zone : zones) {
            int[] pixels = zone.images()[image];
            if (pixels == null) {
                continue;
            }
            for (int y = 0; y < zone.height(); y++) {
                for (int x = 0; x < zone.width(); x++) {
                    int argb = pixels[y * zone.width() + x];
                    int px = zone.x() + x;
                    int py = zone.y() + y;
                    if (px < WIDTH && py < HEIGHT) {
                        frame[py * WIDTH + px] = argb;
                    }
                }
            }
        }
        for (int i = 0; i < frame.length; i++) {
            int c = frame[i];
            int r = ((c >> 16) & 0xFF) * level / FADE;
            int g = ((c >> 8) & 0xFF) * level / FADE;
            int b = (c & 0xFF) * level / FADE;
            frame[i] = (r << 16) | (g << 8) | b;
        }
    }
}
