package fears.engine;

import fears.menu.LinkedImage;

/**
 * La carte (touche TAB, {@code L01602a}) : jeu en pause, écran basse résolution 320×256 de
 * 7 plans (liste copper 0x39338).
 *
 * <ul>
 *   <li><b>Fond</b> ({@code L016330}) : une tuile 32×32 de 3 plans ({@code L01CF94}) répétée,
 *       couleurs 0 à 7 (dégradé gris-bleu).</li>
 *   <li><b>Carte</b> ({@code L0161c2}) : 4×4 pixels par case, la case (r, c) en x = 32 + 4c,
 *       y = 4r, lignes 1 à 62. Seules des <em>arêtes</em> : entre une case vue et non pleine et
 *       un voisin vu dont le sol diffère — 1 unité : plan 4 (gris), davantage : plan 3 (blanc) —
 *       tracées à l'intérieur de la case (ligne 0, ligne 3, colonne 0 ou 3). Ni portes, ni
 *       objets, ni aliens.</li>
 *   <li><b>Joueur</b> ({@code L0162E8}) : un disque 4×4 rouge (plan 5), sans orientation.</li>
 *   <li><b>Boussole</b> ({@code L016b3c}…{@code L016c50}) : une aiguille 3D de 4 facettes
 *       projetée vers (280, 218), qui tourne en 32 images vers le cap du joueur ; ses couleurs
 *       64 + 16f + k suivent une table de sinus selon l'angle ({@code L01636c}).</li>
 * </ul>
 *
 * <p>Les cases « vues » sont, dans l'original, celles que le lanceur de rayons a parcourues
 * (mot à case + 0x221FC). Le portage les marque en lançant des rayons dans le champ de la
 * caméra à chaque VBL, jusqu'à la première case pleine.
 */
public final class AutoMap {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 256;
    static final int TILE = 0x1CF94;
    /** Couleurs 0 à 7 du fond (copper 0x39338). */
    static final int[] BACKGROUND = {0x000000, 0x545568, 0x4A4C5D, 0x404151, 0x373846, 0x2D2E3A, 0x24242F, 0x1B1B23};
    /** Aiguille : sommets (x, y, z) de {@code L016C8E} et faces de {@code L016CC2}. */
    static final int[][] POINTS = {{0, -500, 0}, {200, 200, 0}, {0, 500, 0}, {-200, 200, 0}, {0, 200, 300}};
    static final int[][] FACES = {{0, 1, 4}, {1, 2, 4}, {2, 3, 4}, {3, 0, 4}};
    /** Plans 4, 5, 6 écrits par chaque face (bits 4, 5, 6 de l'index de couleur). */
    static final int[] FACE_BITS = {0x40, 0x50, 0x60, 0x70};
    static final int ANIMATION = 32;

    private final World world;
    private final boolean[] seen = new boolean[World.SIZE * World.SIZE];
    private final int[] tile = new int[32 * 32];
    private final int[] index = new int[WIDTH * HEIGHT];
    private final int[] image = new int[WIDTH * HEIGHT];
    private final int[] palette = new int[128];
    /** Angle de l'aiguille en degrés 16.16 ; gardé d'une ouverture à l'autre (0 à la première). */
    private int theta;
    private int step;
    private int steps;
    private int[] edges;

    public AutoMap(LinkedImage engine, World world) {
        this.world = world;
        for (int y = 0; y < 32; y++) {
            for (int plane = 0; plane < 3; plane++) {
                int bits = engine.u32(TILE + y * 12 + plane * 4);
                for (int x = 0; x < 32; x++) {
                    if (((bits >>> (31 - x)) & 1) != 0) {
                        tile[y * 32 + x] |= 1 << plane;
                    }
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            palette[i] = BACKGROUND[i];
            palette[8 + i] = 0xFFFFFF;
            palette[16 + i] = 0x888888;
            palette[24 + i] = 0xFFFFFF;
        }
        for (int i = 32; i < 64; i++) {
            palette[i] = 0xFF0000;
        }
    }

    // ------------------------------------------------------------------ cases vues

    /** Marque les cases parcourues par des rayons couvrant le champ de vision horizontal. */
    public void markSeen(Player player, double fieldOfViewDegrees) {
        int rays = 48;
        double a0 = Math.toRadians(player.angle() * 360.0 / Trig.FULL_TURN);
        double half = Math.toRadians(fieldOfViewDegrees / 2);
        seen[player.cell()] = true;
        for (int r = 0; r < rays; r++) {
            double a = a0 - half + 2 * half * r / (rays - 1);
            double dx = -Math.sin(a) * 8;
            double dy = Math.cos(a) * 8;
            double x = player.worldX();
            double y = player.worldY();
            for (int i = 0; i < 72; i++) {
                x += dx;
                y += dy;
                int column = (int) Math.floor(x / 64);
                int row = (int) Math.floor(y / 64);
                if (!World.inside(column, row)) {
                    break;
                }
                int cell = World.index(column, row);
                seen[cell] = true;
                if (world.solid(cell)) {
                    break;
                }
            }
        }
    }

    public boolean seen(int cell) {
        return seen[cell];
    }

    // ------------------------------------------------------------------ ouverture et images

    /** {@code L01602a} : la carte est dessinée une fois, l'aiguille part vers le cap du joueur. */
    public void open(Player player) {
        java.util.Arrays.fill(index, 0);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                index[y * WIDTH + x] = tile[(y & 31) * 32 + (x & 31)];
            }
        }
        drawEdges();
        edges = index.clone();
        // L016b3c : cible = angle / 12 + 180, par le plus court chemin, en 32 images.
        int target = ((player.angle() / 12) + 180) % 360;
        int current = Math.floorMod(theta >> 16, 360);
        int delta = Math.floorMod(target - current + 180, 360) - 180;
        step = (delta << 16) / ANIMATION;
        steps = ANIMATION;
    }

    private void drawEdges() {
        for (int r = 1; r <= 62; r++) {
            for (int c = 0; c < World.SIZE; c++) {
                int cell = World.index(c, r);
                if (!seen[cell] || world.solid(cell)) {
                    continue;
                }
                edge(cell, cell - World.SIZE, c, r, 0);
                edge(cell, cell + World.SIZE, c, r, 1);
                edge(cell, cell + 1, c, r, 2);
                edge(cell, cell - 1, c, r, 3);
            }
        }
    }

    /** Une arête à l'intérieur de la case : côté 0 nord, 1 sud, 2 est, 3 ouest. */
    private void edge(int cell, int neighbour, int c, int r, int side) {
        if (neighbour < 0 || neighbour >= seen.length || !seen[neighbour]) {
            return;
        }
        int d = Math.abs(world.floor(neighbour) - world.floor(cell));
        if (d == 0) {
            return;
        }
        int bit = d == 1 ? 0x10 : 0x08;
        int x0 = 32 + 4 * c;
        int y0 = 4 * r;
        for (int i = 0; i < 4; i++) {
            int x = switch (side) {
                case 2 -> x0 + 3;
                case 3 -> x0;
                default -> x0 + i;
            };
            int y = switch (side) {
                case 0 -> y0;
                case 1 -> y0 + 3;
                default -> y0 + i;
            };
            if (x >= 0 && x < WIDTH && y < HEIGHT) {
                index[y * WIDTH + x] |= bit;
            }
        }
    }

    /** Une image (50 Hz) : couleurs de l'aiguille, effacement, rotation, facettes, joueur. */
    public void tick(Player player) {
        if (steps > 0) {
            steps--;
            theta += step;
        }
        int degrees = Math.floorMod(theta >> 16, 360);
        // L01636c : couleur 64 + 16f + k = V[(b + 90f + 12k) mod 360], b = θ + 270.
        int b = degrees + 270;
        for (int f = 0; f < 4; f++) {
            for (int k = 0; k < 8; k++) {
                palette[64 + 16 * f + k] = shade(Math.floorMod(b + 90 * f + 12 * k, 360));
            }
        }
        System.arraycopy(edges, 0, index, 0, index.length);
        // L016a44 : plans 4, 5, 6 effacés sous l'aiguille.
        for (int y = 175; y < HEIGHT; y++) {
            for (int x = 224; x < WIDTH; x++) {
                index[y * WIDTH + x] &= 0x0F;
            }
        }
        drawNeedle(degrees);
        drawPlayer(player);
        for (int i = 0; i < image.length; i++) {
            image[i] = palette[index[i] & 0x7F];
        }
    }

    /** Table V ({@code L016414}) : m·(0xB4, 0x78, 0x82) >> 8, m = min(i, 360 − i). */
    static int shade(int i) {
        int m = Math.min(i, 360 - i);
        return ((m * 0xB4 >> 8) << 16) | ((m * 0x78 >> 8) << 8) | (m * 0x82 >> 8);
    }

    private void drawNeedle(int degrees) {
        double s = 32767 * Math.sin(Math.toRadians(degrees));
        double c = 32767 * Math.cos(Math.toRadians(degrees));
        int[][] screen = new int[POINTS.length][2];
        for (int i = 0; i < POINTS.length; i++) {
            int[] p = POINTS[i];
            double z = p[2] + 3000;
            screen[i][0] = 280 + (int) (((long) (p[0] * c - p[1] * s) * 2 >> 16) * 256 / z);
            screen[i][1] = 218 + (int) (((long) (p[1] * c + p[0] * s) * 2 >> 16) * 256 / z);
        }
        for (int f = 0; f < FACES.length; f++) {
            fill(screen[FACES[f][0]], screen[FACES[f][1]], screen[FACES[f][2]], FACE_BITS[f]);
        }
    }

    /** Remplit un triangle en écrasant les plans 4 à 6 ({@code L016CE6}). */
    private void fill(int[] a, int[] b, int[] c, int bits) {
        int minY = Math.max(0, Math.min(a[1], Math.min(b[1], c[1])));
        int maxY = Math.min(HEIGHT - 1, Math.max(a[1], Math.max(b[1], c[1])));
        int[][] e = {a, b, c};
        for (int y = minY; y <= maxY; y++) {
            double left = Double.MAX_VALUE;
            double right = -Double.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                int[] p = e[i];
                int[] q = e[(i + 1) % 3];
                if ((y >= p[1] && y < q[1]) || (y >= q[1] && y < p[1])) {
                    double x = p[0] + (y + 0.5 - p[1]) * (q[0] - p[0]) / (double) (q[1] - p[1]);
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
            for (int x = (int) Math.max(0, Math.round(left)); x < Math.min(WIDTH, Math.round(right)); x++) {
                index[y * WIDTH + x] = (index[y * WIDTH + x] & 0x0F) | bits;
            }
        }
    }

    /** {@code L0162E8} : .XX. XXXX XXXX .XX. dans le plan 5, octet entier remplacé. */
    private void drawPlayer(Player player) {
        int cell = player.cell();
        int c = cell % World.SIZE;
        int r = cell / World.SIZE;
        int[] rows = {0x6, 0xF, 0xF, 0x6};
        int byteX = (32 + 4 * c) & ~7;
        int shift = (c & 1) == 0 ? 4 : 0;
        for (int i = 0; i < 4; i++) {
            int y = 4 * r + i;
            if (y >= HEIGHT) {
                break;
            }
            int bits = rows[i] << shift;
            for (int b = 0; b < 8; b++) {
                int x = byteX + b;
                if (x < WIDTH) {
                    int on = (bits >> (7 - b)) & 1;
                    index[y * WIDTH + x] = (index[y * WIDTH + x] & ~0x20) | (on << 5);
                }
            }
        }
    }

    public int[] image() {
        return image;
    }
}
