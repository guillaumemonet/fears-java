package fears.engine;

/**
 * Le joueur et ses déplacements, portés du moteur (MOTEUR.md §5 à §7).
 *
 * <p>La position est gardée comme dans le moteur : une case, et une position locale en 16.16
 * dans cette case, {@code x} mesuré depuis le bord <em>est</em> (il croît vers l'ouest),
 * {@code y} depuis le bord nord. La hauteur des pieds suit l'échelle des sols (16.16, plus
 * grand = plus bas).
 */
public final class Player {

    /** 1.0 en 16.16. */
    public static final int ONE = 0x10000;
    /** Largeur d'une case, en 16.16. */
    static final int CELL = 64 * ONE;
    /** Rayon de collision contre un mur : 23.0 et 64 − 23 = 41.0 (L013dcc). */
    static final int NEAR = 23 * ONE;
    static final int FAR = 41 * ONE;
    /** Hauteur des yeux au-dessus du sol (0x3A980 ≈ 3,662 unités). */
    public static final int EYE = 0x3A980;
    /** Pas de marche : composantes ×7 d'un sinus Q15 (≈ 3,5 unités par VBL). */
    static final int WALK = 7;
    /** Rotation clavier : 6 crans de 1/3° par VBL. */
    public static final int TURN = 0x18;
    /** Balancement de tête : +10° de phase par VBL de marche. */
    static final int BOB_STEP = 0x78;
    /** Gravité (L013706). */
    static final int FALL_START = 0x40;
    static final int GRAVITY = 0x600;

    private final World world;
    private final Trig trig;

    private int cell;
    private int x = 32 * ONE;
    private int y = 32 * ONE;
    private int angle;
    private int height;
    private int fallSpeed = FALL_START;
    private int bobPhase;
    private boolean bobbing;

    public Player(World world, Trig trig, int column, int row, int angle) {
        this.world = world;
        this.trig = trig;
        this.cell = World.index(column, row);
        this.angle = Trig.wrap(angle);
        // Valeur statique d'origine (0x380000) ; la gravité l'amène au sol réel.
        this.height = 56 * ONE;
    }

    /** Réapparition au départ : centre de la case, hauteur statique d'origine. */
    public void respawn(int column, int row, int angle) {
        cell = World.index(column, row);
        x = 32 * ONE;
        y = 32 * ONE;
        this.angle = Trig.wrap(angle);
        height = 56 * ONE;
        fallSpeed = FALL_START;
    }

    public void setBobbing(boolean bobbing) {
        this.bobbing = bobbing;
    }

    // ------------------------------------------------------------------ lecture

    public int cell() {
        return cell;
    }

    public int angle() {
        return angle;
    }

    /** Hauteur des pieds (échelle des sols). */
    public double feet() {
        return height / (double) ONE;
    }

    /** Position monde en unités (64 par case) : X vers l'est. */
    public double worldX() {
        return (cell % World.SIZE + 1) * 64.0 - x / (double) ONE;
    }

    /** Position monde en unités : Y vers le sud. */
    public double worldY() {
        return (cell / World.SIZE) * 64.0 + y / (double) ONE;
    }

    /** Hauteur des yeux, échelle des sols (plus grand = plus bas), balancement compris. */
    public double eyeHeight() {
        double bob = bobbing ? trig.sin(bobPhase) / 65536.0 : 0;
        return (height - EYE) / (double) ONE - bob;
    }

    /** Une plate-forme descend sous le joueur ({@code L01437c}) : les pieds suivent. */
    void lower(int amount) {
        height += amount;
    }

    // ------------------------------------------------------------------ une VBL

    /**
     * Une VBL de déplacement.
     *
     * @param forward  +1 avancer, −1 reculer, 0 rien
     * @param strafe   +1 pas à droite, −1 à gauche
     * @param run      deux pas au lieu d'un, en avançant seulement
     * @param turn     crans d'angle à ajouter (octets de table)
     */
    public void tick(int forward, int strafe, boolean run, int turn) {
        angle = Trig.wrap(angle + turn);
        if (forward != 0) {
            int steps = forward > 0 && run ? 2 : 1;
            int direction = forward > 0 ? angle : angle + Trig.FULL_TURN / 2;
            for (int i = 0; i < steps; i++) {
                step(direction);
            }
            bobPhase = Trig.wrap(bobPhase + BOB_STEP);
        }
        if (strafe != 0) {
            step(angle + strafe * Trig.QUARTER);
        }
        settle();
    }

    /** Un pas dans une direction : (sin a, cos a) × 7 en coordonnées locales. */
    private void step(int direction) {
        move(trig.sin(direction) * WALK, trig.cos(direction) * WALK);
    }

    /** {@code L013dcc} : axe x, puis axe y, les blocages recalculés avant chacun. */
    void move(int dx, int dy) {
        boolean[][] blocked = blockMatrix();
        int row = (y >> 16) >> 4;
        x += dx;
        if (!blocked[row][3] && x < 0) {
            x += CELL;
            cell += 1;
        } else if (blocked[row][3] && x <= NEAR) {
            x = NEAR;
        } else if (!blocked[row][0] && x >= CELL) {
            x -= CELL;
            cell -= 1;
        } else if (blocked[row][0] && x >= FAR) {
            x = FAR;
        }

        blocked = blockMatrix();
        int column = (63 - (x >> 16)) >> 4;
        column = Math.max(0, Math.min(3, column));
        y += dy;
        if (!blocked[0][column] && y < 0) {
            y += CELL;
            cell -= World.SIZE;
        } else if (blocked[0][column] && y <= NEAR) {
            y = NEAR;
        } else if (!blocked[3][column] && y >= CELL) {
            y -= CELL;
            cell += World.SIZE;
        } else if (blocked[3][column] && y >= FAR) {
            y = FAR;
        }
    }

    /**
     * {@code L013ef4} puis {@code L014054} : quelles bandes de 16 unités de la case sont
     * interdites, d'après les huit voisins. Indices [ligne][colonne], colonne 0 à l'ouest.
     */
    private boolean[][] blockMatrix() {
        boolean[][] m = new boolean[4][4];
        int floorHere = world.floor(cell);
        boolean nw = blocks(cell - World.SIZE - 1, floorHere);
        boolean n = blocks(cell - World.SIZE, floorHere);
        boolean ne = blocks(cell - World.SIZE + 1, floorHere);
        boolean w = blocks(cell - 1, floorHere);
        boolean e = blocks(cell + 1, floorHere);
        boolean sw = blocks(cell + World.SIZE - 1, floorHere);
        boolean s = blocks(cell + World.SIZE, floorHere);
        boolean se = blocks(cell + World.SIZE + 1, floorHere);
        for (int i = 0; i < 4; i++) {
            m[i][0] |= w;
            m[i][3] |= e;
            m[0][i] |= n;
            m[3][i] |= s;
        }
        m[0][0] |= nw;
        m[0][3] |= ne;
        m[3][0] |= sw;
        m[3][3] |= se;
        return m;
    }

    /** Un voisin est-il infranchissable depuis une case dont le sol est {@code floorHere} ? */
    private boolean blocks(int neighbour, int floorHere) {
        if (world.item(neighbour) > 12) {
            return true;                                        // objet bloquant
        }
        if (world.alien(neighbour) != 0) {
            return true;                                        // alien vivant
        }
        if (world.blockedByMovingDoor(neighbour)) {
            return true;                                        // déclencheur d'une porte en mouvement
        }
        int floor = world.floor(neighbour);
        int ceiling = world.ceiling(neighbour);
        return floor - ceiling < 6                              // moins de 6 unités d'ouverture
                || floor - floorHere < -1                       // marche de plus d'une unité
                || ceiling - floorHere >= -5;                   // plafond trop bas
    }

    /** {@code L013706} : les pieds rejoignent le sol de la case. */
    private void settle() {
        int target = world.floor(cell) << 16;
        if (height == target) {
            fallSpeed = FALL_START;
        } else if (target > height) {
            fallSpeed += GRAVITY;
            height = Math.min(target, height + fallSpeed);
        } else {
            if (target - height < -ONE) {
                height = target + ONE;
            }
            height += (target - height) / 10;
        }
    }
}
