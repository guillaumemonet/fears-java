package fears.menu;

/**
 * L'intro de démarrage, exécutable {@code d1_0d140c} : trois tableaux en hires entrelacé
 * (640×512), chacun amené par un fondu de palette, tenu, puis effacé.
 *
 * <ol>
 *   <li>« MANYK presents » : fondu du noir vers fond blanc, texte noir et gris ;</li>
 *   <li>« A game developed by » : même palette ;</li>
 *   <li>la mascotte BOMB software, 32 couleurs, qui s'efface vers le noir.</li>
 * </ol>
 *
 * <p>La VBL ({@code L0102ea}) compte les images de chaque tableau : les 64 premières ajoutent
 * la table d'entrée aux accumulateurs de couleur ({@code L010538}, 8.6 par composante) ;
 * de la durée {@code d3} à {@code d3 + 63}, la table de sortie est retranchée ; à
 * {@code d3 + 64}, on passe au tableau suivant. Le premier tableau part du noir, et chacun
 * suivant de la couleur où le précédent s'est arrêté. L'intro ne lit aucune entrée.
 */
public final class BootIntro implements Scene {

    public static final int WIDTH = 640;
    public static final int HEIGHT = 512;

    /** Table des tableaux, 24 octets chacun : entrée, sortie, mots−1, couleurs, couleurs, durée. */
    static final int SCENES = 0x102a2;
    static final int SCENE_COUNT = 3;
    static final int FADE_STEPS = 0x40;

    /**
     * Où chaque image se pose sur l'écran entrelacé : abscisse d'après l'adresse de
     * destination du blitter, ligne d'après DIWSTRT (×2 en entrelacé), dernière ligne d'après
     * le WAIT qui coupe les plans (BPLCON0 $0205).
     */
    private static final int[][] PLACE = {
        {14 * 8, 2 * (0x90 - 0x29), 2 * (0xC1 - 0x29)},
        {28 * 8, 2 * (0x84 - 0x29), 2 * (0xC4 - 0x29)},
        {16 * 8, 0, HEIGHT},
    };

    private final MenuAssets assets;
    private final LinkedImage program;
    private final int[] frame = new int[WIDTH * HEIGHT];
    /** {@code L010538} : 32 couleurs × (R, V, B). */
    private final int[] color = new int[32 * 3];
    /** {@code L010382}, {@code L010386}. */
    private int scene;
    private int counter;

    public BootIntro(MenuAssets assets) {
        this.assets = assets;
        this.program = assets.bootProgram;
    }

    public boolean finished() {
        return scene >= SCENE_COUNT;
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
        if (finished()) {
            return;
        }
        int shown = scene;
        int entry = SCENES + 24 * scene;
        int fadeIn = program.u32(entry);
        int fadeOut = program.u32(entry + 4);
        int words = program.u32(entry + 8) + 1;
        int hold = program.u32(entry + 20) & 0xFFFF;
        counter++;
        if (counter <= FADE_STEPS) {
            add(fadeIn, words, +1);
        } else if (counter >= hold + FADE_STEPS) {
            scene++;
            counter = 0;
        } else if (counter >= hold) {
            add(fadeOut, words, -1);
        }
        compose(shown, words / 3);
    }

    private void add(int table, int words, int sign) {
        for (int i = 0; i < words; i++) {
            color[i] = (color[i] + sign * program.s16(table + 2 * i)) & 0xFFFF;
        }
    }

    private int rgb(int index) {
        int r = (color[3 * index] >> 6) & 0xFF;
        int g = (color[3 * index + 1] >> 6) & 0xFF;
        int b = (color[3 * index + 2] >> 6) & 0xFF;
        return (r << 16) | (g << 8) | b;
    }

    private void compose(int shown, int colors) {
        int[] palette = new int[colors];
        for (int i = 0; i < colors; i++) {
            palette[i] = rgb(i);
        }
        java.util.Arrays.fill(frame, palette[0]);
        int[][] image = assets.bootImages[shown];
        int left = PLACE[shown][0];
        int top = PLACE[shown][1];
        int bottom = Math.min(PLACE[shown][2], HEIGHT);
        for (int y = 0; y < image.length && top + y < bottom; y++) {
            int row = (top + y) * WIDTH + left;
            for (int x = 0; x < image[y].length; x++) {
                int index = image[y][x];
                if (index < colors) {
                    frame[row + x] = palette[index];
                }
            }
        }
    }
}
