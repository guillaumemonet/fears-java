package fears.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Les aliens, leurs tirs et ceux du joueur (MOTEUR.md « acteurs » §2 et §3).
 *
 * <ul>
 *   <li><b>Apparition</b> ({@code L017aee}) : chaque objet 15, 16 ou 17 devient un alien du jeu
 *       0, 1 ou 2 du niveau, au centre de sa case ; au plus 64.</li>
 *   <li><b>Activation</b> : dès qu'il est dans le demi-carré de cases que le rendu parcourt
 *       (13 × 6 devant le joueur, plus sa colonne), sans test de ligne de vue ; définitive.</li>
 *   <li><b>Marche</b> : 1 unité par VBL sur l'axe dominant vers le joueur, la fraction sur
 *       l'autre ; retenu au centre de sa case si la voisine est bloquée ; jamais au contact.</li>
 *   <li><b>Tir</b> : toutes les 60 VBL à moins de 6 cases ; projectile à 8 unités par VBL,
 *       dégâts −1 par case, 15 cases au plus ; le joueur perd la moitié des dégâts restants.</li>
 *   <li><b>Mort</b> : PV négatifs ; deux images de mort, puis le cadavre reste.</li>
 * </ul>
 *
 * <p>Positions en unités monde (64 par case, X vers l'est, Y vers le sud) ; hauteurs à
 * l'échelle des sols (plus grand = plus bas).
 */
public final class Aliens {

    public static final int MAX = 64;
    static final int MAX_SHOTS = 15;
    /** « Loin » : 6 cases ; l'alien marche mais ne tire pas. */
    static final double FAR = 6 * 64;
    /** Œil des sprites sous les pieds : 0xEA60 = 0,9155 unité. */
    static final double SPRITE_EYE = 0xEA60 / 65536.0;
    /** Largeur d'une unité W en unités monde : focale des murs (voir ObjectView). */
    public static final double UNITS_PER_W = 2048.0 / 172.0;
    /** Dégâts des tirs d'aliens en mode facile (L014b72). */
    static final int[] EASY_DAMAGE = {10, 20, 30};
    /** Son « joueur touché » (voie 0). */
    public static final int SOUND_HIT = 0x6C;

    /** Ce que la coquille doit faire entendre. */
    public interface Listener {
        /** Voie 1 : son d'alien (décalage dans L01f948), période, durée en VBL, volume 0..64. */
        void alienSound(int offset, int period, int duration, int volume);

        /** Voie 0 : son du moteur. */
        void sound(int offset);
    }

    public enum State { ALIVE, DYING, DEAD }

    /** Un acteur (fiche de 0x78 octets). */
    public static final class Alien {
        public final int number;
        public final int set;
        public final EngineData.AlienType type;
        int cell;
        double x;
        double y;
        State state = State.ALIVE;
        boolean active;
        int health;
        int walkClock;
        int painClock;
        int fireClock;
        int reload;
        int dyingClock;

        Alien(int number, int set, EngineData.AlienType type, int cell, int health) {
            this.number = number;
            this.set = set;
            this.type = type;
            this.cell = cell;
            this.x = (cell % World.SIZE) * 64 + 32;
            this.y = (cell / World.SIZE) * 64 + 32;
            this.health = health;
            this.reload = type.fireDelay();
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public int cell() {
            return cell;
        }

        public State state() {
            return state;
        }

        /** Image à afficher (0 à N − 1). */
        public int image() {
            return switch (state) {
                case DEAD -> type.deathImage() / 8 + 1;
                case DYING -> type.deathImage() / 8 + (dyingClock >= type.deathLength() ? 1 : 0);
                case ALIVE -> painClock > 0 ? type.painImage() / 8
                        : fireClock > 0 ? type.fireImage() / 8
                        : walkClock / 8;
            };
        }

        /** Largeur, hauteur, décalage vertical de l'image (unités W/Hs/hauteur) selon l'état. */
        public double[] display() {
            return type.display()[state.ordinal()];
        }
    }

    /** Un projectile, d'alien ou du joueur. */
    public static final class Shot {
        public final boolean fromPlayer;
        public final int image;
        public final double width;
        public final double height;
        double x;
        double y;
        double h;
        double vx;
        double vy;
        double vh;
        int cell;
        int damage;
        int loss;
        int life;

        Shot(boolean fromPlayer, int image, double width, double height) {
            this.fromPlayer = fromPlayer;
            this.image = image;
            this.width = width;
            this.height = height;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        /** Hauteur, échelle des sols. */
        public double h() {
            return h;
        }
    }

    private final World world;
    private final Player player;
    private final EngineData data;
    private final int difficulty;
    private final List<Alien> aliens = new ArrayList<>();
    private final List<Shot> shots = new ArrayList<>();
    private Shot playerShot;
    private Listener listener;

    /**
     * @param sets       les ids de type des 3 jeux d'aliens du niveau
     * @param difficulty 0 facile, 1 normal, 2 difficile
     */
    public Aliens(World world, Player player, EngineData data, int[] sets, int difficulty) {
        this.world = world;
        this.player = player;
        this.data = data;
        this.difficulty = difficulty;
        for (int cell = 1; cell < World.SIZE * World.SIZE && aliens.size() < MAX; cell++) {
            int type = world.item(cell);
            if (type < 15 || type > 17) {
                continue;
            }
            int set = type - 15;
            EngineData.AlienType alienType = data.alienType(sets[set]);
            int health = alienType.health() + (difficulty == 2 ? 3 : 0);
            Alien alien = new Alien(aliens.size() + 1, set, alienType, cell, health);
            aliens.add(alien);
            world.removeItem(cell);
            world.setAlien(cell, alien.number);
        }
    }

    /** Aliens encore vivants (octet 3 non nul). */
    public int alive() {
        return (int) aliens.stream().filter(a -> a.state == State.ALIVE).count();
    }

    /** Après une mort : tous les projectiles sont effacés (L01779a). */
    public void clearShots() {
        shots.clear();
        playerShot = null;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public List<Alien> aliens() {
        return aliens;
    }

    public List<Shot> shots() {
        List<Shot> all = new ArrayList<>(shots);
        if (playerShot != null) {
            all.add(playerShot);
        }
        return all;
    }

    /** Une roquette ou une décharge de plasma vole-t-elle déjà ? (un seul projectile du joueur) */
    public boolean playerShotFlying() {
        return playerShot != null;
    }

    // ------------------------------------------------------------------ une VBL

    /**
     * @return les dégâts subis par le joueur pendant cette VBL
     */
    public int tick() {
        int damage = 0;
        for (Alien alien : aliens) {
            switch (alien.state) {
                case ALIVE -> {
                    if (!alien.active && visible(alien)) {
                        alien.active = true;
                    }
                    if (alien.active) {
                        step(alien);
                    }
                }
                case DYING -> {
                    if (++alien.dyingClock >= alien.type.deathLength() + 8) {
                        alien.state = State.DEAD;
                        world.setAlien(alien.cell, 0);   // l'octet 3 passe à l'octet 2 : cadavre
                    }
                }
                case DEAD -> {
                    // Le cadavre reste, avec sa dernière image.
                }
            }
        }
        for (int i = shots.size() - 1; i >= 0; i--) {
            Shot shot = shots.get(i);
            int hit = moveAlienShot(shot);
            if (hit != 0 || shot.life <= 0) {
                shots.remove(i);
                damage += Math.max(0, hit);
            }
        }
        if (playerShot != null && !movePlayerShot(playerShot)) {
            playerShot = null;
        }
        return damage;
    }

    /** {@code L0115ba} : le demi-carré de cases parcouru par le rendu, selon le quadrant de vue. */
    private boolean visible(Alien alien) {
        int pc = player.cell() % World.SIZE;
        int pr = player.cell() / World.SIZE;
        int ac = alien.cell % World.SIZE;
        int ar = alien.cell / World.SIZE;
        int quadrant = Trig.wrap(player.angle() - 0x21C) / Trig.QUARTER;
        int forward;
        int lateral;
        switch (quadrant) {
            case 0 -> { forward = pc - ac; lateral = ar - pr; }       // vers −x
            case 1 -> { forward = pr - ar; lateral = ac - pc; }       // vers −y
            case 2 -> { forward = ac - pc; lateral = ar - pr; }       // vers +x
            default -> { forward = ar - pr; lateral = ac - pc; }      // vers +y
        }
        return (forward == 0 && Math.abs(lateral) <= 1)
                || (forward >= 1 && forward <= 6 && Math.abs(lateral) <= 6);
    }

    /** {@code L01271e} : un pas de marche, puis peut-être un tir. */
    private void step(Alien alien) {
        if (alien.painClock > 0) {
            alien.painClock--;
        }
        if (alien.fireClock > 0) {
            alien.fireClock--;
        }
        alien.walkClock = (alien.walkClock + 1) % Math.max(1, alien.type.walkLength());
        double dx = player.worldX() - alien.x;
        double dy = player.worldY() - alien.y;
        boolean far = Math.abs(dx) >= FAR || Math.abs(dy) >= FAR;
        double sx;
        double sy;
        if (dx == 0 && dy == 0) {
            sx = 0;
            sy = 0;
        } else if (Math.abs(dx) >= Math.abs(dy)) {
            sx = Math.signum(dx);
            sy = dy / Math.abs(dx);
        } else {
            sy = Math.signum(dy);
            sx = dx / Math.abs(dy);
        }
        moveAxis(alien, sx, true);
        moveAxis(alien, sy, false);
        if (world.solid(alien.cell)) {
            kill(alien);                    // une porte s'est refermée sur lui
            return;
        }
        if (--alien.reload < 0 && !far && (sx != 0 || sy != 0) && shots.size() < MAX_SHOTS
                && shots.stream().noneMatch(s -> s.cell == alien.cell)) {
            fire(alien, sx, sy, Math.hypot(dx, dy));
            alien.reload = alien.type.fireDelay();
        }
    }

    private void moveAxis(Alien alien, double delta, boolean xAxis) {
        if (delta == 0) {
            return;
        }
        double position = (xAxis ? alien.x : alien.y) + delta;
        int column = alien.cell % World.SIZE;
        int row = alien.cell / World.SIZE;
        int current = xAxis ? column : row;
        int next = (int) Math.floor(position / 64);
        if (next != current) {
            int neighbour = alien.cell + (xAxis ? next - current : (next - current) * World.SIZE);
            if (blockedForAlien(alien.cell, neighbour)) {
                position = current * 64 + 32;     // retenu au centre de sa case
            } else {
                world.setAlien(alien.cell, 0);
                alien.cell = neighbour;
                world.setAlien(neighbour, alien.number);
            }
        }
        if (xAxis) {
            alien.x = position;
        } else {
            alien.y = position;
        }
    }

    /** {@code L012a4e}. */
    private boolean blockedForAlien(int from, int to) {
        int floorHere = world.floor(from);
        return world.blockedByMovingDoor(to)
                || Math.abs(world.floor(to) - floorHere) > 1
                || Math.abs(world.ceiling(to) - floorHere) <= 4
                || world.alien(to) != 0
                || to == player.cell();
    }

    /** {@code L012976} : projectile visant les yeux du joueur au bout de la distance. */
    private void fire(Alien alien, double sx, double sy, double distance) {
        EngineData.AlienShot model = data.alienShot(alien.type.shot());
        Shot shot = new Shot(false, model.image(), model.width(), model.height());
        shot.x = alien.x;
        shot.y = alien.y;
        shot.cell = alien.cell;
        shot.h = world.floor(alien.cell);
        shot.vx = sx * 8;
        shot.vy = sy * 8;
        double steps = Math.max(1, distance / 8);
        shot.vh = ((player.feet() - SPRITE_EYE) - shot.h) / steps;
        shot.damage = difficulty == 0 ? EASY_DAMAGE[alien.type.shot()] : model.damage();
        shot.loss = model.loss();
        shot.life = 15;
        shots.add(shot);
        alien.fireClock = 4;
    }

    /**
     * {@code L01216a} : deux demi-pas de 4 unités.
     *
     * @return les dégâts infligés au joueur (> 0), −1 si détruit sans toucher, 0 s'il vole encore
     */
    private int moveAlienShot(Shot shot) {
        for (int half = 0; half < 2; half++) {
            shot.x += shot.vx / 2;
            shot.y += shot.vy / 2;
            shot.h += shot.vh / 2;
            int cell = cellAt(shot.x, shot.y);
            if (cell != shot.cell) {
                shot.cell = cell;
                shot.damage -= shot.loss;
                shot.life--;
                if (world.solid(cell) || shot.damage <= 0 || shot.life <= 0) {
                    return -1;
                }
            }
            if (shot.h > world.floor(cell) + 2.5 || shot.h < world.ceiling(cell) - 2.5) {
                return -1;
            }
            if (cell == player.cell() && Math.abs(world.floor(cell) - shot.h) < 3) {
                return Math.max(1, shot.damage / 2);
            }
        }
        return 0;
    }

    private static int cellAt(double x, double y) {
        return World.index((int) Math.floor(x / 64), (int) Math.floor(y / 64));
    }

    // ------------------------------------------------------------------ tirs du joueur

    /**
     * Un tir de l'arme {@code weapon} vient de partir ({@link Weapons}).
     */
    public void playerFired(int weapon) {
        switch (weapon) {
            case Weapons.ROCKETS, Weapons.PLASMA -> launch(weapon);
            case Weapons.FIST, Weapons.CHAINSAW -> {
                Alien target = target(true);
                if (target != null) {
                    hurt(target, 1, 30);
                }
            }
            default -> {
                Alien target = target(false);
                if (target != null) {
                    double depth = 32 * depth(target) + 1500;
                    int volume = (int) Math.max(20, Math.min(50, (15000 - depth) / 250));
                    hurt(target, 1, volume);
                }
            }
        }
    }

    /**
     * {@code L017ca6} : l'alien vivant le plus proche qui couvre le centre de l'écran sans
     * être masqué. Au contact (poing, tronçonneuse) : une case voisine, moins de 4 unités de
     * dénivelé, profondeur d'origine < 0xFA0.
     */
    private Alien target(boolean melee) {
        Alien best = null;
        double bestDepth = Double.MAX_VALUE;
        for (Alien alien : aliens) {
            if (alien.state != State.ALIVE) {
                continue;
            }
            double z = depth(alien);
            if (z <= 0) {
                continue;
            }
            double half = alien.display()[0] * UNITS_PER_W / 2;
            if (Math.abs(lateral(alien)) > half) {
                continue;
            }
            if (melee) {
                int dc = Math.abs(alien.cell % World.SIZE - player.cell() % World.SIZE);
                int dr = Math.abs(alien.cell / World.SIZE - player.cell() / World.SIZE);
                if (dc > 1 || dr > 1 || Math.abs(world.floor(alien.cell) - world.floor(player.cell())) >= 4
                        || 32 * z + 1500 >= 0xFA0) {
                    continue;
                }
            } else if (!clearLine(alien)) {
                continue;
            }
            if (z < bestDepth) {
                bestDepth = z;
                best = alien;
            }
        }
        return best;
    }

    /** Profondeur dans l'axe de vue, en unités. */
    private double depth(Alien alien) {
        double a = Math.toRadians(player.angle() * 360.0 / Trig.FULL_TURN);
        return (alien.x - player.worldX()) * -Math.sin(a) + (alien.y - player.worldY()) * Math.cos(a);
    }

    private double lateral(Alien alien) {
        double a = Math.toRadians(player.angle() * 360.0 / Trig.FULL_TURN);
        return (alien.x - player.worldX()) * Math.cos(a) + (alien.y - player.worldY()) * Math.sin(a);
    }

    /** Rien de plein entre l'œil du joueur et l'alien (le moteur s'en remet à sa découpe par colonne). */
    private boolean clearLine(Alien alien) {
        double eye = player.eyeHeight();
        double dx = alien.x - player.worldX();
        double dy = alien.y - player.worldY();
        int steps = (int) Math.ceil(Math.hypot(dx, dy) / 4);
        for (int i = 1; i < steps; i++) {
            int cell = cellAt(player.worldX() + dx * i / steps, player.worldY() + dy * i / steps);
            if (cell == alien.cell) {
                break;
            }
            if (world.solid(cell) || world.floor(cell) < eye || world.ceiling(cell) > eye) {
                return false;
            }
        }
        return true;
    }

    /** Roquette (image 12) ou plasma (13) : 16 unités par VBL, 50 pas, 8 points. */
    private void launch(int weapon) {
        Shot shot = new Shot(true, weapon == Weapons.ROCKETS ? 12 : 13, 0x12C00 / 65536.0, 0x19000 / 65536.0);
        double a = Math.toRadians(player.angle() * 360.0 / Trig.FULL_TURN);
        shot.x = player.worldX();
        shot.y = player.worldY();
        shot.cell = player.cell();
        shot.h = world.floor(player.cell()) - 1;
        shot.vx = -Math.sin(a) * 16;
        shot.vy = Math.cos(a) * 16;
        Alien target = target(false);
        if (target != null) {
            double steps = Math.max(1, depth(target) / 16);
            double aim = world.floor(target.cell) - 1;
            shot.vh = (aim - shot.h) / steps;
        }
        shot.damage = 8;
        shot.life = 50;
        playerShot = shot;
    }

    /** {@code L012e50} : vrai tant que le projectile vole. */
    private boolean movePlayerShot(Shot shot) {
        for (int half = 0; half < 2; half++) {
            shot.x += shot.vx / 2;
            shot.y += shot.vy / 2;
            shot.h += shot.vh / 2;
            shot.life--;
            int cell = cellAt(shot.x, shot.y);
            shot.cell = cell;
            if (world.solid(cell) || shot.life <= 0
                    || shot.h > world.floor(cell) + 2 || shot.h < world.ceiling(cell) - 2) {
                return false;
            }
            int number = world.alien(cell);
            if (number != 0 && Math.abs(world.floor(cell) - shot.h) < 2) {
                hurt(aliens.get(number - 1), shot.damage, 30);
                return false;
            }
        }
        return true;
    }

    /** {@code §2.9} : douleur, ou mort si les PV deviennent négatifs. */
    private void hurt(Alien alien, int damage, int volume) {
        alien.active = true;
        alien.health -= damage;
        alien.painClock = 4;
        if (alien.health < 0) {
            kill(alien);
            if (listener != null) {
                listener.alienSound(alien.type.deathSound(), alien.type.deathPeriod(),
                        alien.type.deathDuration(), volume);
            }
        } else if (listener != null) {
            listener.alienSound(alien.type.painSound(), alien.type.painPeriod(),
                    alien.type.painDuration(), volume);
        }
    }

    private void kill(Alien alien) {
        alien.state = State.DYING;
        alien.dyingClock = 0;
        alien.painClock = 0;
    }
}
