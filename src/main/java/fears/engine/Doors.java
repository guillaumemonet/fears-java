package fears.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Déclencheurs et portes (MOTEUR.md, axe « monde », §4 et §5).
 *
 * <p><b>Activation</b> ({@code L014212}, à chaque VBL) : seulement avec Espace enfoncé et
 * aucune porte en mouvement. La case visée est la voisine dans le quadrant de l'angle ; son
 * sens doit accepter le côté d'où l'on vient — ou bien la case du joueur porte un
 * déclencheur « sur la case ». Garder Espace enfoncé relance la porte dès qu'elle s'arrête.
 *
 * <p><b>Mouvement</b> ({@code L0142d8}, à chaque trame de rendu, soit une VBL sur deux) :
 * chaque case de la porte rapproche son sol et son plafond d'une unité de la grille B
 * (compte positif) ou de la grille A d'origine (compte négatif). Quand plus rien ne bouge, le
 * compte change de signe : le trajet suivant se fera dans l'autre sens. Une seule porte à la
 * fois, aucun blocage, aucune fermeture automatique.
 *
 * <p>Au premier pas, un mur bas de texture supérieure à 13 bascule (+1 à l'aller, −1 au
 * retour) : ce sont les interrupteurs. Le n° 64 est la sortie du niveau.
 */
public final class Doors {

    /** N° de la sortie. */
    public static final int EXIT = 64;

    /** Pour chaque quadrant de visée : déplacement vers la case visée et masque de sens. */
    private static final int[] AIM_OFFSET = {-1, -World.SIZE, +1, +World.SIZE};
    private static final int[] AIM_MASK = {4, 8, 1, 2};
    /** Tous les sens : déclencheur « sur la case ». */
    private static final int ALL_SIDES = 0xF;
    /** Texture de mur bas au-delà de laquelle elle bascule : les interrupteurs 14 et 15. */
    private static final int SWITCH_ABOVE = 13;

    /** Ce que la coquille doit entendre. */
    public interface Listener {
        /** Une porte démarre ({@code L0144c8}) : n° pair ou sortie à la période 720, impair à 600. */
        void doorStarted(int door);

        /** La porte s'est arrêtée : le son est coupé. */
        void doorStopped();
    }

    private final World world;
    private final Player player;
    private final Map<Integer, List<Integer>> cells;
    private final Map<Integer, Integer> counts = new HashMap<>();
    private final boolean switchesToggle;
    private Listener listener;
    private int triggerCell = -1;
    private boolean firstStep;
    private boolean exitReached;
    private boolean renderFrame;

    /**
     * @param switchesToggle faux au niveau 30 et en jeu à deux, où les interrupteurs ne
     *                       basculent pas ({@code L017ca2})
     */
    public Doors(World world, Player player, boolean switchesToggle) {
        this.world = world;
        this.player = player;
        this.cells = world.level().doors;
        this.switchesToggle = switchesToggle;
        // Dans les fichiers, tous les comptes sont positifs : chaque porte part de l'état A.
        for (Integer door : cells.keySet()) {
            counts.put(door, cells.get(door).size());
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Vrai quand le joueur a actionné la sortie. */
    public boolean exitReached() {
        return exitReached;
    }

    /** N° de la porte en mouvement, 0 si aucune. */
    public int activeDoor() {
        return world.activeDoor();
    }

    /** Une VBL : activation éventuelle, puis un pas de porte une VBL sur deux. */
    public void tick(boolean activate) {
        if (activate && world.activeDoor() == 0) {
            tryActivate();
        }
        renderFrame = !renderFrame;
        if (renderFrame) {
            step();
        }
    }

    /**
     * Met une porte en mouvement sans déclencheur, comme le boss du niveau 30 en écrivant
     * directement {@code L0143d6} : la porte qui bougeait s'arrête où elle en est.
     */
    public void force(int door) {
        world.setActiveDoor(door);
        triggerCell = -1;
        firstStep = false;
        if (listener != null) {
            listener.doorStarted(door);
        }
    }

    /** {@code L014212}. */
    private void tryActivate() {
        int here = player.cell();
        int quadrant = Trig.wrap(player.angle() - 0x21C) / Trig.QUARTER;
        int aimed = here + AIM_OFFSET[quadrant];
        if (world.triggerDoor(here) != 0 && sides(world.triggerDirection(here)) == ALL_SIDES) {
            start(here);
            return;
        }
        int door = world.triggerDoor(aimed);
        if (door == 0) {
            return;
        }
        int sides = sides(world.triggerDirection(aimed));
        if (sides == ALL_SIDES || (sides != 0 && (sides & AIM_MASK[quadrant]) == 0)) {
            return;
        }
        start(aimed);
    }

    /** Bits 3 à 6 de l'octet 6 ramenés à 0..15 : +x, +y, −x, −y. */
    private static int sides(int direction) {
        return (direction >> 3) & 0xF;
    }

    private void start(int trigger) {
        int door = world.triggerDoor(trigger) & 0x7F;
        world.setActiveDoor(door);
        world.markTriggerUsed(trigger);
        triggerCell = trigger;
        firstStep = true;
        if (listener != null) {
            listener.doorStarted(door);
        }
    }

    /** {@code L0142d8}. */
    private void step() {
        int door = world.activeDoor();
        if (door == 0) {
            return;
        }
        if (door == EXIT) {
            exitReached = true;
            return;
        }
        List<Integer> list = cells.get(door);
        int count = counts.getOrDefault(door, 0);
        boolean toB = count >= 0;
        if (firstStep) {
            firstStep = false;
            int texture = world.wallDown(triggerCell);
            if (switchesToggle && texture > SWITCH_ABOVE) {
                world.setWallDown(triggerCell, texture + (toB ? 1 : -1));
            }
        }
        boolean moved = false;
        if (list != null) {
            for (int cell : list) {
                int before = world.floor(cell);
                if (world.stepTowards(cell, toB)) {
                    moved = true;
                    if (world.floor(cell) > before && cell == player.cell()) {
                        // La plate-forme descend : le joueur descend avec elle.
                        player.lower(Player.ONE);
                    }
                }
            }
        }
        if (!moved) {
            counts.put(door, -count);
            world.setActiveDoor(0);
            if (listener != null) {
                listener.doorStopped();
            }
        }
    }
}
