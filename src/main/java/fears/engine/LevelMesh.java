package fears.engine;

import fears.extract.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * La géométrie 3D d'un niveau, déduite de la grille A comme le lanceur de rayons la voit
 * (MOTEUR.md §3.2 à §3.4). Sans dépendance graphique : des tableaux de sommets regroupés par
 * texture, que la coquille transforme en maillages.
 *
 * <p>Repère : une case = 1.0 ; X vers l'est (colonne), Z vers le sud (ligne), Y vers le haut,
 * {@code Y = −hauteur × }{@link #HEIGHT_UNIT}. Une texture de 64 texels couvre une case en
 * largeur et 6,25 unités de hauteur (§4.2 : 10,24 texels par unité) : les texels sont carrés
 * pour {@code HEIGHT_UNIT = 0,16}.
 *
 * <p>Pour chaque case ouverte C et chacun de ses quatre voisins N :
 * <ul>
 *   <li><b>mur bas</b> (contremarche) si le sol de N est plus haut que celui de C, texture =
 *       octet 6 de N dans la grille vivante (les interrupteurs le changent), v = 0 au niveau du sol de N et croissant vers le bas ;</li>
 *   <li><b>mur haut</b> si le plafond de N descend sous celui de C, texture = octet 7 de N,
 *       v = 0 au niveau du plafond de N et croissant vers le haut ;</li>
 * </ul>
 * puis le sol (octet 5) et le plafond (octet 4) de C. Une case pleine n'a ni sol ni plafond :
 * ses voisins en voient les murs. Les faces ne sont visibles que du côté de C.
 */
public final class LevelMesh {

    /** Hauteur d'une unité de sol, en cases. */
    public static final float HEIGHT_UNIT = 0.16f;

    /** Les surfaces d'une même texture et d'une même sorte (mur ou sol/plafond). */
    public static final class Batch {
        public final boolean flat;
        public final int texture;
        private final List<float[]> positions = new ArrayList<>();
        private final List<float[]> uvs = new ArrayList<>();

        Batch(boolean flat, int texture) {
            this.flat = flat;
            this.texture = texture;
        }

        /** Quatre sommets, à relier en (0,1,2) et (0,2,3). */
        void quad(float[] p, float[] uv) {
            positions.add(p);
            uvs.add(uv);
        }

        public int quads() {
            return positions.size();
        }

        public float[] positions() {
            float[] out = new float[positions.size() * 12];
            for (int i = 0; i < positions.size(); i++) {
                System.arraycopy(positions.get(i), 0, out, i * 12, 12);
            }
            return out;
        }

        public float[] texCoords() {
            float[] out = new float[uvs.size() * 8];
            for (int i = 0; i < uvs.size(); i++) {
                System.arraycopy(uvs.get(i), 0, out, i * 8, 8);
            }
            return out;
        }

        public int[] indices() {
            int[] out = new int[positions.size() * 6];
            for (int i = 0; i < positions.size(); i++) {
                int b = i * 4;
                int at = i * 6;
                out[at] = b;
                out[at + 1] = b + 1;
                out[at + 2] = b + 2;
                out[at + 3] = b;
                out[at + 4] = b + 2;
                out[at + 5] = b + 3;
            }
            return out;
        }
    }

    private final Map<String, Batch> batches = new LinkedHashMap<>();

    public LevelMesh(World world) {
        for (int row = 0; row < World.SIZE; row++) {
            for (int column = 0; column < World.SIZE; column++) {
                int here = World.index(column, row);
                if (world.solid(here)) {
                    continue;
                }
                flats(world, column, row);
                walls(world, column, row);
            }
        }
    }

    public List<Batch> batches() {
        return new ArrayList<>(batches.values());
    }

    private Batch batch(boolean flat, int texture) {
        return batches.computeIfAbsent((flat ? "f" : "w") + texture, k -> new Batch(flat, texture & 15));
    }

    private static float y(int height) {
        return -height * HEIGHT_UNIT;
    }

    /** Sol et plafond : texel = tex[A·64 + B], A ≈ 64 − frac x (ligne), B ≈ 64 − frac y (colonne). */
    private void flats(World world, int c, int r) {
        int here = World.index(c, r);
        Level.Cell cell = world.cell(here);
        float floorY = y(world.floor(here));
        float ceilY = y(world.ceiling(here));
        // Coins : (c,r), (c,r+1), (c+1,r+1), (c+1,r) — vus d'en haut, normale +Y.
        float[] uv = {1, 0, 0, 0, 0, 1, 1, 1};
        batch(true, cell.texFloor()).quad(new float[]{
            c, floorY, r, c, floorY, r + 1, c + 1, floorY, r + 1, c + 1, floorY, r}, uv);
        // Plafond : mêmes coins dans l'ordre inverse, normale −Y.
        float[] uvCeil = {1, 1, 0, 1, 0, 0, 1, 0};
        batch(true, cell.texCeiling()).quad(new float[]{
            c + 1, ceilY, r, c + 1, ceilY, r + 1, c, ceilY, r + 1, c, ceilY, r}, uvCeil);
    }

    private void walls(World world, int c, int r) {
        // Bords : nord (normale +Z), sud (−Z), ouest (+X), est (−X). A → B dans le sens qui
        // tourne la face vers la case.
        edge(world, c, r, c, r - 1, c, r, c + 1, r, true);
        edge(world, c, r, c, r + 1, c + 1, r + 1, c, r + 1, true);
        edge(world, c, r, c - 1, r, c, r + 1, c, r, false);
        edge(world, c, r, c + 1, r, c + 1, r, c + 1, r + 1, false);
    }

    /**
     * Les murs vus de la case (c, r) vers le voisin (nc, nr), posés sur le segment A–B.
     *
     * @param alongX vrai pour un bord perpendiculaire à y : u ≈ 63 − frac x ; sinon u ≈ 64 − frac y
     */
    private void edge(World world, int c, int r, int nc, int nr,
                      float ax, float az, float bx, float bz, boolean alongX) {
        int here = World.index(c, r);
        int there = World.inside(nc, nr) ? World.index(nc, nr) : -1;
        int floorHere = world.floor(here);
        int ceilHere = world.ceiling(here);
        int floorThere = there >= 0 ? world.floor(there) : 0;
        int ceilThere = there >= 0 ? world.ceiling(there) : 0;
        Level.Cell seen = there >= 0 ? world.cell(there) : world.cell(here);
        float ua = 1 - (alongX ? ax - c : az - r);
        float ub = 1 - (alongX ? bx - c : bz - r);

        if (floorThere < floorHere) {
            // Mur bas : de son sol (haut, v = 0) jusqu'au nôtre (bas).
            int top = Math.max(floorThere, ceilHere);
            float vTop = 1 - (top - floorThere) * HEIGHT_UNIT;
            float vBottom = 1 - (floorHere - floorThere) * HEIGHT_UNIT;
            int texture = there >= 0 ? world.wallDown(there) : seen.texWallDown();
            wall(batch(false, texture), ax, az, bx, bz, y(floorHere), y(top),
                    ua, ub, vBottom, vTop);
        }
        if (ceilThere > ceilHere) {
            // Mur haut : de son plafond (bas, v = 0) jusqu'au nôtre (haut), image retournée.
            int bottom = Math.min(ceilThere, floorHere);
            float vBottom = 1 - (ceilThere - bottom) * HEIGHT_UNIT;
            float vTop = 1 - (ceilThere - ceilHere) * HEIGHT_UNIT;
            wall(batch(false, seen.texWallUp()), ax, az, bx, bz, y(bottom), y(ceilHere),
                    ua, ub, vBottom, vTop);
        }
    }

    private static void wall(Batch batch, float ax, float az, float bx, float bz,
                             float yLow, float yHigh, float ua, float ub, float vLow, float vHigh) {
        if (yHigh <= yLow) {
            return;
        }
        batch.quad(new float[]{
            ax, yLow, az, bx, yLow, bz, bx, yHigh, bz, ax, yHigh, az,
        }, new float[]{
            ua, vLow, ub, vLow, ub, vHigh, ua, vHigh,
        });
    }
}
