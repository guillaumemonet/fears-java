package fears.menu;

import fears.engine.LevelResult;

import java.util.ArrayList;
import java.util.List;

/**
 * L'écran de fin de niveau ({@code L012d48}) : la page 2 du menu, remplie par {@code L012fe6}.
 *
 * <pre>
 *   LEVEL nn TIME hh:mm:ss
 *   TOTAL TIME hh:mm:ss
 *
 *   ALIENS    tués OUT OF total
 *   SECRETS   trouvés OUT OF ???
 *   TREASURES pris OUT OF total
 *   SCORE nnnnnnnnnn
 *
 *   SAVE GAME
 *   CONTINUE GAME
 * </pre>
 *
 * <p>Liste copper 0x29358 : hires, 3 plans de texte (couleur = valeur du glyphe), pointeur en
 * sprite. Les 8 couleurs partent toutes de $00F — l'écran s'ouvre en bleu — et rejoignent leur
 * valeur en 64 images (table {@code L012f86}) ; le clic n'est lu qu'ensuite. Le fondu de sortie
 * retranche la table {@code L012f56}. Un clic sur la ligne SAVE GAME (lignes $C6 à $D9)
 * demande la sauvegarde ; tout autre clic continue.
 */
public final class StatsScreen implements Scene {

    public static final int WIDTH = MenuText.WIDTH;
    public static final int HEIGHT = MenuText.HEIGHT;

    static final int FADE_IN = 0x12F86;
    static final int FADE_OUT = 0x12F56;
    static final int COLORS = 8;
    static final int FADE_STEPS = 0x40;
    /** Bande de la ligne SAVE GAME, en y du pointeur. */
    static final int SAVE_FROM = 0xC6;
    static final int SAVE_TO = 0xDA;

    private final MenuAssets assets;
    private final LinkedImage program;
    private final MenuText text;
    private final Pointer pointer = new Pointer();
    private final int[] frame = new int[WIDTH * HEIGHT];
    /** {@code L012fb6} : 8 couleurs × (R, V, B), en 8.6. */
    private final int[] color = new int[COLORS * 3];
    private int fadeIn;
    private int fadeOut = -1;
    private boolean fire;
    private boolean saveRequested;

    public StatsScreen(MenuAssets assets, LevelResult result, long totalTime, long score) {
        this.assets = assets;
        this.program = assets.program;
        this.text = new MenuText(assets);
        for (int i = 0; i < COLORS; i++) {
            color[3 * i + 2] = 0x3C00;
        }
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(String.format("LEVEL %02d TIME %s", result.level(), clock(result.time())));
        lines.add("TOTAL TIME " + clock(totalTime));
        lines.add("");
        lines.add(String.format("ALIENS    %03d OUT OF %03d", result.aliensKilled(), result.aliensTotal()));
        lines.add(String.format("SECRETS   %03d OUT OF ???", result.secretsFound()));
        lines.add(String.format("TREASURES %03d OUT OF %03d", result.goldTaken(), result.goldTotal()));
        lines.add(String.format("SCORE %010d", score));
        lines.add("");
        lines.add("SAVE GAME");
        lines.add("CONTINUE GAME");
        text.drawLines(lines);
    }

    /** Durée en VBL → hh:mm:ss (le menu divise par 50). */
    static String clock(long vbl) {
        long seconds = vbl / 50;
        return String.format("%02d:%02d:%02d", Math.min(99, seconds / 3600), (seconds / 60) % 60, seconds % 60);
    }

    public boolean finished() {
        return fadeOut > FADE_STEPS;
    }

    /** SAVE GAME a été choisi. */
    public boolean saveRequested() {
        return saveRequested;
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
        return 2f;
    }

    @Override
    public int[] frame() {
        return frame;
    }

    @Override
    public void movePointer(double dx, double dy) {
        pointer.move(dx, dy);
    }

    @Override
    public void setJoystick(boolean up, boolean down, boolean left, boolean right) {
        pointer.joystick(up, down, left, right);
    }

    @Override
    public void setFire(boolean pressed) {
        fire = pressed;
    }

    @Override
    public void tick() {
        if (fadeOut < 0) {
            pointer.tick();
            if (++fadeIn <= FADE_STEPS) {
                fade(FADE_IN);
            } else if (fire) {
                fadeOut = 0;
                saveRequested = pointer.y() >= SAVE_FROM && pointer.y() < SAVE_TO;
            }
        } else if (++fadeOut <= FADE_STEPS) {
            fade(FADE_OUT);
        }
        compose();
    }

    private void fade(int table) {
        for (int i = 0; i < COLORS * 3; i++) {
            color[i] = (color[i] - program.s16(table + 2 * i)) & 0xFFFF;
        }
    }

    private void compose() {
        int[] palette = new int[COLORS];
        for (int i = 0; i < COLORS; i++) {
            int r = (color[3 * i] >> 6) & 0xFF;
            int g = (color[3 * i + 1] >> 6) & 0xFF;
            int b = (color[3 * i + 2] >> 6) & 0xFF;
            palette[i] = (r << 16) | (g << 8) | b;
        }
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                frame[y * WIDTH + x] = palette[text.at(x, y)];
            }
        }
        if (fadeOut < 0) {
            // Le pointeur n'est plus affiché pendant le fondu de sortie (DMA des sprites coupé).
            pointer.draw(frame, WIDTH, HEIGHT, assets);
        }
    }
}
