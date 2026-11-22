package fears.engine;

import fears.extract.Level;

/**
 * L'état du niveau pendant le jeu (MOTEUR.md, axe « monde », §2 et §3).
 *
 * <ul>
 *   <li>grille A <em>vivante</em> : hauteurs courantes et texture de mur bas, que les portes
 *       et les interrupteurs modifient — seule grille lue par le rendu et les collisions ;</li>
 *   <li>grille A d'origine et grille B : les deux états extrêmes des cases mobiles ;</li>
 *   <li>grille C : objet (octet 4), sens du déclencheur (octet 6), n° de porte (octet 7, bit 7
 *       posé à l'activation).</li>
 * </ul>
 *
 * <p>Hauteurs de 0 à 63, <em>plus grand = plus bas</em>. Une case est pleine quand son sol
 * n'est pas plus bas que son plafond. Au chargement, le moteur ramène tout plafond situé
 * sous le sol au niveau du sol ({@code L014bf6}).
 */
public final class World {

    public static final int SIZE = Level.W;
    private static final int CELLS = SIZE * SIZE;

    private final Level level;
    private final int[] floor = new int[CELLS];
    private final int[] ceiling = new int[CELLS];
    private final int[] floorA = new int[CELLS];
    private final int[] ceilingA = new int[CELLS];
    private final int[] floorB = new int[CELLS];
    private final int[] ceilingB = new int[CELLS];
    private final int[] wallDown = new int[CELLS];
    private final int[] item = new int[CELLS];
    /** Octet 3 de la grille C : alien présent (numéro 1..64), 0 si aucun. */
    private final int[] alien = new int[CELLS];
    private final int[] triggerDirection = new int[CELLS];
    private final int[] triggerDoor = new int[CELLS];
    /** N° de la porte en mouvement ({@code L0143d6}), 0 si aucune. */
    private int activeDoor;
    /** Version de la géométrie : augmente à chaque changement de hauteur ou de texture. */
    private int revision;
    private int itemRevision;

    public World(Level level) {
        this.level = level;
        for (int i = 0; i < CELLS; i++) {
            Level.Cell a = level.a[i];
            floor[i] = a.floor();
            ceiling[i] = Math.min(a.ceiling(), a.floor());
            floorA[i] = floor[i];
            ceilingA[i] = ceiling[i];
            Level.Cell b = level.b[i];
            floorB[i] = b.floor();
            ceilingB[i] = Math.min(b.ceiling(), b.floor());
            wallDown[i] = a.texWallDown();
        }
        for (Level.Item it : level.items) {
            item[index(it.x(), it.y())] = it.type();
        }
        for (Level.Trigger t : level.triggers) {
            int at = index(t.x(), t.y());
            triggerDirection[at] = t.direction();
            triggerDoor[at] = t.door();
        }
    }

    public Level level() {
        return level;
    }

    public static int index(int column, int row) {
        return row * SIZE + column;
    }

    public static boolean inside(int column, int row) {
        return column >= 0 && row >= 0 && column < SIZE && row < SIZE;
    }

    private static boolean valid(int index) {
        return index >= 0 && index < CELLS;
    }

    /** Sol d'une case ; hors de la carte, une case pleine au niveau 0. */
    public int floor(int index) {
        return valid(index) ? floor[index] : 0;
    }

    public int ceiling(int index) {
        return valid(index) ? ceiling[index] : 0;
    }

    public boolean solid(int index) {
        return floor(index) <= ceiling(index);
    }

    public int item(int index) {
        return valid(index) ? item[index] : 0;
    }

    public int alien(int index) {
        return valid(index) ? alien[index] : 0;
    }

    void setAlien(int index, int number) {
        if (valid(index)) {
            alien[index] = number;
        }
    }

    /**
     * Secrets restants ({@code L015558}) : déclencheurs jamais actionnés, de n° &amp; $3F non nul
     * (la sortie est exclue), sur une case dont la texture de mur bas est inférieure à 11 (ni
     * porte ordinaire, ni interrupteur).
     */
    public int secretsLeft() {
        int count = 0;
        for (int i = 0; i < CELLS; i++) {
            int door = triggerDoor[i];
            if ((door & 0x80) == 0 && (door & 0x3F) != 0 && wallDown[i] < 11) {
                count++;
            }
        }
        return count;
    }

    /** Trésors restants : cases dont l'objet vaut 11 (or). */
    public int goldLeft() {
        int count = 0;
        for (int i = 0; i < CELLS; i++) {
            if (item[i] == 11) {
                count++;
            }
        }
        return count;
    }

    /** Efface l'objet d'une case (ramassage). */
    public void removeItem(int index) {
        if (valid(index) && item[index] != 0) {
            item[index] = 0;
            itemRevision++;
        }
    }

    /** Version des objets posés : augmente à chaque ramassage. */
    public int itemRevision() {
        return itemRevision;
    }

    /** Sol acide : texture de sol 0 (octet 5 de la grille A, MOTEUR.md « monde » §7). */
    public boolean acid(int index) {
        return valid(index) && level.a[index].texFloor() == 0;
    }

    /** Texture du mur bas (octet 6 de la grille A vivante). */
    public int wallDown(int index) {
        return valid(index) ? wallDown[index] : 0;
    }

    /** Textures d'origine de la case : plafond, sol, mur bas, mur haut. */
    public Level.Cell cell(int index) {
        return level.a[index];
    }

    public int revision() {
        return revision;
    }

    // ------------------------------------------------------------------ déclencheurs et portes

    /** Octet 6 & $78 : sens d'activation. */
    int triggerDirection(int index) {
        return valid(index) ? triggerDirection[index] : 0;
    }

    /** Octet 7 : n° de porte (1..63) ou 64 = sortie, bit 7 = déjà actionné. */
    int triggerDoor(int index) {
        return valid(index) ? triggerDoor[index] : 0;
    }

    void markTriggerUsed(int index) {
        triggerDoor[index] |= 0x80;
    }

    int activeDoor() {
        return activeDoor;
    }

    void setActiveDoor(int door) {
        activeDoor = door;
    }

    /**
     * Pendant qu'une porte bouge, la case dont l'octet 7 vaut {@code $80 | porte} — le
     * déclencheur actionné — est infranchissable ({@code L013f32}).
     */
    boolean blockedByMovingDoor(int index) {
        return activeDoor != 0 && valid(index) && triggerDoor[index] == (0x80 | activeDoor);
    }

    /** Rapproche d'une unité le sol et le plafond de leur cible ; vrai si l'un a bougé. */
    boolean stepTowards(int index, boolean toB) {
        int targetFloor = toB ? floorB[index] : floorA[index];
        int targetCeiling = toB ? ceilingB[index] : ceilingA[index];
        boolean moved = false;
        if (floor[index] != targetFloor) {
            floor[index] += Integer.signum(targetFloor - floor[index]);
            moved = true;
        }
        if (ceiling[index] != targetCeiling) {
            ceiling[index] += Integer.signum(targetCeiling - ceiling[index]);
            moved = true;
        }
        if (moved) {
            revision++;
        }
        return moved;
    }

    void setWallDown(int index, int texture) {
        wallDown[index] = texture;
        revision++;
    }
}
