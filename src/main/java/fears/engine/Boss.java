package fears.engine;

import fears.menu.LinkedImage;

/**
 * Le boss du niveau 30 (MOTEUR.md « acteurs » §2.10, « monde » §9) : ce n'est pas un acteur
 * mais la <b>texture de mur 11</b>, que le rendu fait vivre par une table de remappage.
 *
 * <ul>
 *   <li><b>Animation</b> ({@code L01142e}, toutes les 20 trames) : pour chaque texture 1 à 15,
 *       {compteur, recharge, max, min} ({@code L01148a}) — si le compteur est nul, la texture
 *       affichée avance d'un cran et revient au min au-delà du max, le compteur reprenant sa
 *       recharge. Les murs passent par cette table ({@code L011308}).</li>
 *   <li><b>Tir</b> ({@code L0114c6}) : un compteur avance au même rythme ; de 8 à 14, un
 *       projectile de type 0 part de la case (15,25) dans l'une des 7 directions de
 *       {@code L011582}, puis le compteur revient à 0 à 15.</li>
 *   <li><b>Dégâts</b> ({@code L013080}) : −1 pour un tir de pistolet ou de rolling quand la
 *       colonne centrale montre la texture 11, −10 pour une roquette qui atteint sa case ; sous
 *       400, 300, 200 points, les portes 6, 7, 8 partent d'elles-mêmes, une fois chacune.</li>
 *   <li><b>Mort</b> ({@code L0113a2}) : son, mur bas de (14,25) passé à 12, animation figée,
 *       porte 10.</li>
 * </ul>
 */
public final class Boss {

    public static final int HEALTH = 500;
    /** Case du boss (offset $3278) et case dont le mur bas marque sa mort (offset $3270). */
    public static final int CELL = World.index(15, 25);
    static final int MARK = World.index(14, 25);
    static final int FACE = 11;
    static final int DEAD_FACE = 12;
    /** Une trame de rendu = deux VBL ; l'animation avance toutes les 20 trames. */
    static final int TICK_VBL = 2 * 0x14;
    static final int ANIMATION = 0x1147A;
    static final int CYCLES = 0x1148A;
    static final int DIRECTIONS = 0x11582;
    /** Son de douleur et de mort : voie 1, $0C, période 270, 40 VBL. */
    static final int SOUND = 0x0C;
    static final int SOUND_PERIOD = 0x10E;
    static final int SOUND_DURATION = 0x28;

    private final World world;
    private final Doors doors;
    private final Aliens aliens;
    private final int[] remap = new int[16];
    /** {compteur, recharge, max, min} des textures 1 à 15. */
    private final int[][] cycles = new int[15][4];
    private final double[][] directions = new double[7][2];
    private int health = HEALTH;
    private int clock;
    private int shotCounter;
    private int deadCountdown = 0x100;
    private final boolean[] doorOpened = new boolean[3];
    private boolean remapChanged = true;

    public Boss(LinkedImage engine, World world, Doors doors, Aliens aliens) {
        this.world = world;
        this.doors = doors;
        this.aliens = aliens;
        for (int t = 0; t < 16; t++) {
            remap[t] = engine.u8(ANIMATION + t);
        }
        for (int i = 0; i < 15; i++) {
            for (int k = 0; k < 4; k++) {
                cycles[i][k] = engine.u8(CYCLES + 4 * i + k);
            }
        }
        for (int i = 0; i < 7; i++) {
            directions[i][0] = engine.u32(DIRECTIONS + 8 * i) / 65536.0;
            directions[i][1] = engine.u32(DIRECTIONS + 8 * i + 4) / 65536.0;
        }
    }

    public int health() {
        return health;
    }

    public boolean dead() {
        return world.wallDown(MARK) == DEAD_FACE;
    }

    /** Texture affichée pour la texture de mur {@code t} (table {@code L011308}). */
    public int remap(int t) {
        return remap[t & 15];
    }

    /** Vrai une fois si la table de remappage a changé depuis le dernier appel. */
    public boolean takeRemapChanged() {
        boolean changed = remapChanged;
        remapChanged = false;
        return changed;
    }

    /** Une VBL. */
    public void tick() {
        if (dead()) {
            // L01141c : un compte à rebours, puis la texture 12 s'affiche en 13.
            if (deadCountdown >= 0 && --deadCountdown < 0) {
                remap[12] = 13;
                remapChanged = true;
            }
            return;
        }
        if (health < 0) {
            die();
            return;
        }
        if (++clock < TICK_VBL) {
            return;
        }
        clock = 0;
        fire();
        for (int i = 0; i < 15; i++) {
            int[] c = cycles[i];
            int texture = remap[i + 1];
            if (c[0] > 0) {
                c[0]--;
            } else {
                texture++;
                if (texture > c[2]) {
                    texture = c[3];
                    c[0] = c[1];
                }
            }
            if (remap[i + 1] != texture) {
                remap[i + 1] = texture;
                remapChanged = true;
            }
        }
    }

    /** {@code L0114c6} : de 8 à 14, un projectile par cran, dans la direction n° compteur − 8. */
    private void fire() {
        shotCounter++;
        if (shotCounter >= 0xF) {
            shotCounter = 0;
            return;
        }
        if (shotCounter < 8) {
            return;
        }
        double[] d = directions[shotCounter - 8];
        // x local croît vers l'ouest : une vitesse locale négative va vers l'est.
        aliens.spawnBossShot(CELL, 0x2E, -d[0], d[1]);
    }

    /** Un tir a porté : −1 (pistolet, rolling) ou −10 (roquette arrivée sur sa case). */
    public void hurt(int damage, Aliens.Listener listener) {
        if (dead() || health < 0) {
            return;
        }
        health -= damage;
        if (health >= 0 && listener != null) {
            listener.alienSound(SOUND, SOUND_PERIOD, SOUND_DURATION, 10);
        }
        int[] limits = {400, 300, 200};
        for (int i = 0; i < 3; i++) {
            if (health < limits[i] && !doorOpened[i]) {
                doorOpened[i] = true;
                doors.force(6 + i);
            }
        }
        if (health < 0 && listener != null) {
            listener.alienSound(SOUND, SOUND_PERIOD, SOUND_DURATION, 50);
        }
    }

    private void die() {
        world.setWallDown(MARK, DEAD_FACE);
        // L0113dc… : l'animation se fige autrement, et la porte 10 s'ouvre.
        cycles[12][0] = 10;
        remap[12] = 12;
        cycles[4][3] = 6;
        cycles[5][3] = 6;
        cycles[6][3] = 8;
        cycles[7][3] = 8;
        remapChanged = true;
        doors.force(10);
    }

    /**
     * Ce que montre la colonne centrale de l'écran ({@code L01134a}) : la texture de la
     * première face que la visée rencontre à hauteur d'yeux, −1 si rien à portée.
     */
    public static int centerTexture(World world, Player player) {
        double a = Math.toRadians(player.angle() * 360.0 / Trig.FULL_TURN);
        double dx = -Math.sin(a);
        double dy = Math.cos(a);
        double eye = player.eyeHeight();
        double x = player.worldX();
        double y = player.worldY();
        int cell = player.cell();
        for (int step = 0; step < 600; step++) {
            x += dx;
            y += dy;
            int next = World.index((int) Math.floor(x / 64), (int) Math.floor(y / 64));
            if (next == cell) {
                continue;
            }
            cell = next;
            if (world.floor(cell) < eye || world.solid(cell)) {
                return world.wallDown(cell);
            }
            if (world.ceiling(cell) > eye) {
                return world.cell(cell).texWallUp();
            }
        }
        return -1;
    }
}
