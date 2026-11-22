package fears.menu;

import java.util.Arrays;

/**
 * La présentation du menu ({@code L010e7a}) : le logo FEARS, et par-dessus un texte qui
 * monte — titre, auteurs, puis l'histoire du professeur Bloodheart — en boucle jusqu'au clic.
 * Elle sert aussi d'écran CREDITS.
 *
 * <p>Écran hires 640×256 en double champ de jeu (liste copper 0x294d4, BPLCON0 $f605) :
 * <ul>
 *   <li>champ 1, plans impairs : le logo 320×191 basse résolution, chaque pixel doublé
 *       ({@code L0113d6}), posé ligne 32 — couleurs 0 à 15 ;</li>
 *   <li>champ 2, plans pairs, devant : le texte sur trois plans — couleurs 16 + v, la
 *       valeur 0 laissant voir le logo.</li>
 * </ul>
 *
 * <p>Le défilement ({@code L010f3c}) ne travaille qu'une image sur deux (trames longues de
 * l'entrelacé) : il remonte le texte d'une ligne et trace sous le bas de l'écran (ligne 256)
 * la ligne suivante des glyphes de la chaîne en cours. Une chaîne dure 23 lignes : 21 de
 * glyphes, deux vides. Après le {@code $FF} final, le texte reprend au début.
 *
 * <p>La palette (24 couleurs, table d'incréments {@code L0112e2}) monte du noir en 64 images
 * ({@code L011160}). Un clic ou le feu ({@code L0111a8}) lance le fondu inverse ; la
 * présentation s'achève 71 images après le clic.
 */
public final class Presentation implements Scene {

    public static final int WIDTH = MenuText.WIDTH;
    public static final int HEIGHT = MenuText.HEIGHT;

    static final int TEXT = 0x11424;
    static final int FADE = 0x112e2;
    static final int COLORS = 24;
    static final int FADE_STEPS = 0x40;
    /** Fin de la présentation : {@code L011250} > $46. */
    static final int END_AFTER_CLICK = 0x46;
    /** Lignes par chaîne ({@code L01111c} jusqu'à $17) et lignes de glyphe tracées ($15). */
    static final int ROWS_PER_STRING = 0x17;
    static final int GLYPH_ROWS = 0x15;
    /** Ligne de la bande où naît le texte, et hauteur de la bande qui défile (lignes 0 à 266). */
    static final int BIRTH_LINE = 0xF000 / 0xF0;
    static final int STRIP_LINES = 267;
    static final int LOGO_TOP = 0x2800 / 320;

    private final MenuAssets assets;
    private final LinkedImage program;
    private final MenuText font;
    private final int[] frame = new int[WIDTH * HEIGHT];
    private final byte[] strip = new byte[WIDTH * STRIP_LINES];
    /** {@code L011252} : 24 couleurs × (R, V, B). */
    private final int[] color = new int[COLORS * 3];

    private int frameCount;
    private boolean longFrame;
    private int text = TEXT;
    private int row;
    private boolean fire;
    private boolean leaving;
    private int sinceClick;

    public Presentation(MenuAssets assets) {
        this.assets = assets;
        this.program = assets.program;
        this.font = new MenuText(assets);
    }

    public boolean finished() {
        return sinceClick > END_AFTER_CLICK;
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
    public void setFire(boolean pressed) {
        fire = pressed;
    }

    @Override
    public void tick() {
        // L011160 : fondu d'entrée, mené par la boucle principale pendant 64 images.
        if (frameCount < FADE_STEPS) {
            fade(+1);
        }
        frameCount++;
        // L010ed6, la VBL : défilement, puis clic.
        longFrame = !longFrame;
        if (longFrame) {
            scroll();
        }
        if (!leaving) {
            leaving = fire;
        } else {
            sinceClick++;
            if (sinceClick <= FADE_STEPS) {
                fade(-1);
            }
        }
        compose();
    }

    private void fade(int sign) {
        for (int i = 0; i < COLORS * 3; i++) {
            color[i] = (color[i] + sign * program.s16(FADE + 2 * i)) & 0xFFFF;
        }
    }

    /** Une ligne de défilement, et la ligne suivante des glyphes sous le bas de l'écran. */
    private void scroll() {
        System.arraycopy(strip, WIDTH, strip, 0, WIDTH * (STRIP_LINES - 1));
        Arrays.fill(strip, WIDTH * (STRIP_LINES - 1), strip.length, (byte) 0);
        String line = program.string(text);
        if (row < GLYPH_ROWS) {
            drawRow(line, row);
        }
        row++;
        if (row >= ROWS_PER_STRING) {
            row = 0;
            text += line.length() + 1;
            if (program.u8(text) == LinkedImage.END_OF_PAGE) {
                text = TEXT;
            }
        }
    }

    /** {@code L011054} / {@code L0110ac} : une ligne des glyphes d'une chaîne, centrée. */
    private void drawRow(String line, int glyphRow) {
        int x = ((WIDTH - font.width(line)) & 0xFFFF) >> 1;
        int at = BIRTH_LINE * WIDTH;
        for (int i = 0; i < line.length(); i++) {
            int c = line.charAt(i);
            if (c != ' ') {
                for (int px = 0; px < 16; px++) {
                    int sx = x + px;
                    if (sx >= 0 && sx < WIDTH) {
                        strip[at + sx] |= (byte) font.glyphPixel(c, glyphRow, px);
                    }
                }
            }
            x += font.charWidth(c);
        }
    }

    private int rgb(int index) {
        int r = (color[3 * index] >> 6) & 0xFF;
        int g = (color[3 * index + 1] >> 6) & 0xFF;
        int b = (color[3 * index + 2] >> 6) & 0xFF;
        return (r << 16) | (g << 8) | b;
    }

    private void compose() {
        int[] palette = new int[COLORS];
        for (int i = 0; i < COLORS; i++) {
            palette[i] = rgb(i);
        }
        int[][] logo = assets.logo;
        for (int y = 0; y < HEIGHT; y++) {
            int logoRow = y - LOGO_TOP;
            int[] logoLine = (logoRow >= 0 && logoRow < logo.length) ? logo[logoRow] : null;
            for (int x = 0; x < WIDTH; x++) {
                int value = strip[y * WIDTH + x];
                int index;
                if (value != 0) {
                    index = 16 + value;
                } else {
                    index = logoLine != null ? logoLine[x >> 1] : 0;
                }
                frame[y * WIDTH + x] = palette[index];
            }
        }
    }
}
