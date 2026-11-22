package fears.menu;

import java.util.Arrays;

/**
 * Le tracé d'une page de texte du menu : portage de {@code L01089e} et {@code L010914}.
 *
 * <p>Une page est une suite de chaînes terminées par zéro, close par l'octet {@code $FF}.
 * Chaque chaîne occupe une bande de 22 lignes (0x6e0 octets d'un plan de 80 octets) et se
 * centre sur les 640 pixels hires d'après la table de chasse {@code L011122}. Les glyphes
 * font 16×21 sur trois plans, dessinés dans les plans 2 à 4 de l'écran : un pixel de valeur
 * v prend la couleur 2v.
 *
 * <p>La police se lit à {@code L0261e2 + 2×caractère + 120×ligne} : un glyphe est un mot de
 * seize pixels, et une ligne de la planche en compte soixante. Les caractères au-delà de
 * {@code ;} (0x3c et suivants) débordent donc sur la ligne suivante de la planche — et leur
 * dernière ligne sur le plan suivant. Le calcul ci-dessous reproduit ce débordement plutôt
 * que de le corriger : c'est ainsi que les glyphes s'alignent.
 */
public final class MenuText {

    public static final int WIDTH = 640;
    public static final int HEIGHT = 256;
    /** Hauteur d'une ligne de menu (0x6e0 / 80). */
    public static final int LINE_HEIGHT = 22;
    /** Lignes d'un glyphe (dbra 0x14). */
    static final int GLYPH_ROWS = 21;

    /** Table de chasse : largeur en pixels du caractère c à {@code L011122 + c - 0x20}. */
    static final int WIDTHS = 0x11122;

    private static final int FONT_ROW_BYTES = 0x78;
    private static final int FONT_PLANES = 3;

    private final MenuAssets assets;
    /** Plans 2 à 4 de l'écran, une valeur 0..7 par pixel. */
    private final byte[] pixels = new byte[WIDTH * HEIGHT];

    public MenuText(MenuAssets assets) {
        this.assets = assets;
    }

    /** Valeur 0..7 du texte au pixel donné. */
    public int at(int x, int y) {
        return pixels[y * WIDTH + x];
    }

    /** Efface puis trace la page dont le texte commence à {@code address}. */
    public void drawPage(int address) {
        Arrays.fill(pixels, (byte) 0);
        LinkedImage program = assets.program;
        int line = 0;
        int at = address;
        do {
            String text = program.string(at);
            drawLine(text, line);
            at += text.length() + 1;
            line++;
        } while (program.u8(at) != LinkedImage.END_OF_PAGE && line * LINE_HEIGHT < HEIGHT);
    }

    /** Efface puis trace des lignes données (une par bande de 22 pixels). */
    public void drawLines(java.util.List<String> lines) {
        Arrays.fill(pixels, (byte) 0);
        for (int line = 0; line < lines.size() && line * LINE_HEIGHT < HEIGHT; line++) {
            drawLine(lines.get(line), line);
        }
    }

    /** Largeur d'une chaîne selon la table de chasse. */
    public int width(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += charWidth(text.charAt(i));
        }
        return total;
    }

    int charWidth(int c) {
        return assets.program.u8(WIDTHS + c - 0x20);
    }

    private void drawLine(String text, int line) {
        // move.w #$280,d1 ; sub.w d7,d1 ; lsr.w #1,d1
        int x = ((WIDTH - width(text)) & 0xFFFF) >> 1;
        int top = line * LINE_HEIGHT;
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i);
            if (c != ' ') {
                drawGlyph(c >= 0x5C ? ' ' : c, x, top);
            }
            // La chasse est prise sur le caractère d'origine, avant son remplacement.
            x += charWidth(c);
        }
    }

    private void drawGlyph(int c, int x, int top) {
        for (int row = 0; row < GLYPH_ROWS; row++) {
            int y = top + row;
            if (y >= HEIGHT) {
                return;
            }
            for (int px = 0; px < 16; px++) {
                int sx = x + px;
                if (sx < 0 || sx >= WIDTH) {
                    continue;
                }
                int value = glyphPixel(c, row, px);
                if (value != 0) {
                    pixels[y * WIDTH + sx] |= (byte) value;
                }
            }
        }
    }

    /** Valeur du pixel {@code px} de la ligne {@code row} du glyphe {@code c}, débordements compris. */
    int glyphPixel(int c, int row, int px) {
        int value = 0;
        for (int plane = 0; plane < FONT_PLANES; plane++) {
            if (fontBit(plane, 2 * c + FONT_ROW_BYTES * row, px)) {
                value |= 1 << plane;
            }
        }
        return value;
    }

    /** Un bit de la police, lu à un décalage d'octets depuis le début d'un plan. */
    private boolean fontBit(int plane, int offset, int px) {
        int[][] font = assets.font;
        int planeRows = font.length;
        int rowInPlanes = offset / FONT_ROW_BYTES;
        plane += rowInPlanes / planeRows;
        int row = rowInPlanes % planeRows;
        if (plane >= FONT_PLANES) {
            return false;
        }
        int x = (offset % FONT_ROW_BYTES) * 8 + px;
        return ((font[row][x] >> plane) & 1) != 0;
    }
}
