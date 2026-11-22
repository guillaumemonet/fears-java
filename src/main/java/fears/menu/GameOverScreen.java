package fears.menu;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * L'écran de fin de partie, exécutable {@code d1_0ae87e} lancé par le menu ({@code L012cfc}) :
 * l'image 320×256, sur le module « dead end ».
 *
 * <p>La palette monte en 32 pas ({@code L0122a2}), puis l'écran attend le bouton gauche de la
 * souris, redescend en 32 pas ({@code L0122a4}) et coupe la musique. [S] Un pas par VBL.
 */
public final class GameOverScreen implements Scene {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 256;
    static final int STEPS = 0x20;

    private final int[] image;
    private final int[] frame = new int[WIDTH * HEIGHT];
    private int fadeIn;
    private int fadeOut = -1;
    private boolean fire;

    public GameOverScreen(Path resources) throws IOException {
        BufferedImage picture = ImageIO.read(resources.resolve("gameover").resolve("game_over_320x256.png").toFile());
        if (picture == null) {
            throw new IOException("image de game over illisible");
        }
        image = picture.getRGB(0, 0, WIDTH, HEIGHT, null, 0, WIDTH);
    }

    public boolean finished() {
        return fadeOut >= STEPS;
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
    public void setFire(boolean pressed) {
        fire = pressed;
    }

    @Override
    public void tick() {
        int level;
        if (fadeOut >= 0) {
            fadeOut++;
            level = Math.max(0, STEPS - fadeOut);
        } else if (fadeIn < STEPS) {
            level = ++fadeIn;
        } else {
            level = STEPS;
            if (fire) {
                fadeOut = 0;
            }
        }
        for (int i = 0; i < frame.length; i++) {
            int c = image[i];
            int r = ((c >> 16) & 0xFF) * level / STEPS;
            int g = ((c >> 8) & 0xFF) * level / STEPS;
            int b = (c & 0xFF) * level / STEPS;
            frame[i] = (r << 16) | (g << 8) | b;
        }
    }
}
