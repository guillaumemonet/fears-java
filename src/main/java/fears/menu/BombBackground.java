package fears.menu;

/**
 * Le fond du menu : le logo « bomb » sur un plan, qui ondule verticalement.
 *
 * <p>Au démarrage ({@code L010406}), le logo de 208×197 pixels basse résolution est étiré à
 * 416 pixels hires (chaque bit doublé) et posé dans un plan de 640×256 à l'adresse 0x555ee,
 * soit à la ligne 30 et au pixel 112 : centré. La liste copper repointe ensuite le plan 1 à
 * chaque ligne de l'écran ({@code L010146}/{@code L01014e}, à chaque VBL) : la ligne affichée
 * n'est pas la ligne de l'écran mais une ligne du plan choisie par un compteur 16.16, qui
 * avance de deux sinus d'une ligne à l'autre. D'une image à l'autre, le point de départ
 * descend d'une ligne et les deux phases tournent (+1° et −2°) : le logo défile et se
 * déforme sans cesse.
 */
public final class BombBackground {

    static final int WIDTH = MenuText.WIDTH;
    static final int HEIGHT = MenuText.HEIGHT;

    /** Position du logo dans le plan : 0x555ee − 0x54c80 = 30 × 80 + 14. */
    static final int LOGO_TOP = 30;
    static final int LOGO_LEFT = 14 * 8;

    /** Table de sinus du menu, 360 mots signés ({@code L017b10}). */
    static final int SINE = 0x17b10;
    static final int DEGREES = 360;

    private final boolean[] plane = new boolean[WIDTH * HEIGHT];
    private final int[] sine = new int[DEGREES];

    /** {@code L01013a}, {@code L01013e}, {@code L010142}. */
    private int phase1;
    private int phase2;
    private int start;

    /** Ligne du plan affichée sur chaque ligne de l'écran, recalculée à chaque VBL. */
    private final int[] rowOfLine = new int[HEIGHT];

    public BombBackground(MenuAssets assets) {
        boolean[][] logo = assets.bomb;
        for (int y = 0; y < logo.length; y++) {
            for (int x = 0; x < logo[y].length; x++) {
                if (logo[y][x]) {
                    int at = (LOGO_TOP + y) * WIDTH + LOGO_LEFT + 2 * x;
                    plane[at] = true;
                    plane[at + 1] = true;
                }
            }
        }
        for (int i = 0; i < DEGREES; i++) {
            sine[i] = assets.program.s16(SINE + 2 * i);
        }
        for (int y = 0; y < HEIGHT; y++) {
            rowOfLine[y] = y;
        }
    }

    /** Une VBL : portage de {@code L010154}. */
    public void tick() {
        int d6 = phase1;
        phase1++;
        if (phase1 >= DEGREES) {
            phase1 -= DEGREES;
        }
        int d0 = phase2;
        phase2 -= 2;
        if (phase2 < 0) {
            phase2 += DEGREES;
        }
        start += 0x10000;
        if (start >= 0x1000000) {
            start -= 0x1000000;
        }
        int d3 = start;
        for (int line = 0; line < HEIGHT; line++) {
            rowOfLine[line] = (d3 >> 16) & 0xFF;
            d3 += 2 * sine[d6];
            d3 += 2 * sine[d0];
            d6++;
            if (d6 >= DEGREES) {
                d6 -= DEGREES;
            }
            d0 += 2;
            if (d0 >= DEGREES) {
                d0 -= DEGREES;
            }
        }
    }

    /** Le bit du plan 1 affiché au pixel (x, y) de l'écran. */
    public boolean at(int x, int y) {
        return plane[rowOfLine[y] * WIDTH + x];
    }
}
