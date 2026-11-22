package fears.engine;

/**
 * Le tableau des stats du joueur ({@code L014908 + 2k}, MOTEUR.md « monde » §6.2) et le
 * ramassage des objets ({@code L013866}, §6.1).
 *
 * <p>On ramasse en <em>entrant</em> dans une case : le test n'a lieu qu'au changement de case.
 * Un objet refusé parce que le stock est plein reste en place.
 */
public final class Stats {

    public static final int HEALTH = 0;
    public static final int ROLLING = 4;
    public static final int ROCKET = 5;
    public static final int PLASMA = 6;
    public static final int AMMO_1 = 7;
    public static final int AMMO_2 = 8;
    public static final int LIVES = 9;
    public static final int SHIELD = 10;
    public static final int AMMO_3 = 13;
    public static final int CHAINSAW = 14;

    /** Sons du ramassage, en décalages de la table du moteur. */
    public static final int SOUND_WEAPON = 0x0C;
    public static final int SOUND_HEALTH = 0x18;
    public static final int SOUND_GOLD = 0x24;
    public static final int SOUND_FULL = 0x30;
    public static final int SOUND_AMMO = 0x84;

    /** Bouclier : 900 VBL, soit 18 secondes. */
    static final int SHIELD_TIME = 900;
    /** Sol acide : un point de santé toutes les 10 VBL. */
    static final int ACID_PERIOD = 10;

    /** Objets 1 à 9 : stat, quantité, maximum, son ({@code L013966}, {@code L01397e}). */
    private static final int[][] PICKUP = {
        null,
        {ROLLING, 1, 1, SOUND_WEAPON},
        {ROCKET, 1, 1, SOUND_WEAPON},
        {PLASMA, 1, 1, SOUND_WEAPON},
        {CHAINSAW, 1, 1, SOUND_WEAPON},
        {AMMO_1, 8, 99, SOUND_AMMO},
        {AMMO_2, 2, 99, SOUND_AMMO},
        {AMMO_3, 1, 99, SOUND_AMMO},
        {LIVES, 1, 9, SOUND_HEALTH},
        {HEALTH, 8, 99, SOUND_HEALTH},
    };

    private final int[] stat = new int[15];
    /** Arme ramassée à monter ({@code L01355e}), −1 si aucune. */
    private int pickedWeapon = -1;
    private int acidClock;

    public Stats() {
        stat[HEALTH] = 99;
        // [S] Valeurs de départ transmises par le menu, non encore relevées : trois vies et un
        // chargeur plein pour l'arme de base.
        stat[LIVES] = 3;
        stat[AMMO_1] = 99;
    }

    public int get(int k) {
        return stat[k];
    }

    /** L'arme que le dernier ramassage demande de prendre en main (une seule fois), ou −1. */
    public int takePickedWeapon() {
        int w = pickedWeapon;
        pickedWeapon = -1;
        return w;
    }

    void add(int k, int delta) {
        stat[k] = Math.max(0, stat[k] + delta);
    }

    /**
     * Tente de ramasser l'objet {@code type}.
     *
     * @return le son à jouer (décalage dans la table), 0 si aucun ; {@code taken[0]} dit si
     *         l'objet doit disparaître
     */
    public int pickUp(int type, boolean[] taken) {
        taken[0] = false;
        if (type >= 12 || type <= 0) {
            return 0;                               // décors
        }
        if (type == 11) {
            taken[0] = true;                        // l'or ne compte qu'en fin de niveau
            return SOUND_GOLD;
        }
        if (type == 10) {
            if (stat[SHIELD] != 0) {
                return SOUND_FULL;
            }
            stat[SHIELD] = SHIELD_TIME;
            taken[0] = true;
            return SOUND_HEALTH;
        }
        int[] rule = PICKUP[type];
        int k = rule[0];
        if (stat[k] == rule[2]) {
            return SOUND_FULL;
        }
        if (type <= 4) {
            // Objets 1 à 4 : rolling, roquettes, plasma, tronçonneuse = armes 2 à 5.
            pickedWeapon = type + 1;
        }
        stat[k] = Math.min(rule[2], stat[k] + rule[1]);
        taken[0] = true;
        return rule[3];
    }

    /**
     * Une VBL : le bouclier s'use ({@code L0139f6}) ; sur un sol acide sans bouclier, la santé
     * baisse d'un point toutes les 10 VBL ({@code L013a44}).
     *
     * @return vrai si la santé passe sous zéro : une vie est perdue
     */
    public boolean tick(boolean onAcid) {
        if (stat[SHIELD] > 0) {
            stat[SHIELD]--;
            return false;
        }
        if (!onAcid) {
            return false;
        }
        if (++acidClock >= ACID_PERIOD) {
            acidClock = 0;
            stat[HEALTH]--;
            if (stat[HEALTH] < 0) {
                stat[HEALTH] = 0;
                stat[LIVES]--;
                return true;
            }
        }
        return false;
    }

    /**
     * Le joueur est touché ; vrai s'il en meurt (santé ≤ 0 : une vie de moins).
     */
    public boolean damage(int amount) {
        stat[HEALTH] -= amount;
        if (stat[HEALTH] <= 0) {
            stat[HEALTH] = 0;
            stat[LIVES]--;
            return true;
        }
        return false;
    }

    /** Score cumulé sur la partie, et temps total en VBL. */
    private long score;
    private long totalTime;

    public long score() {
        return score;
    }

    public long totalTime() {
        return totalTime;
    }

    /**
     * Fin de niveau ({@code L012fe6}) : score += 2 × aliens tués + secrets trouvés
     * + 5 × trésors pris ; le temps du niveau s'ajoute au total.
     */
    public void endLevel(LevelResult result) {
        score += 2L * result.aliensKilled() + result.secretsFound() + 5L * result.goldTaken();
        totalTime += result.time();
    }

    /** Plus de vie : la partie est finie (vies < 0, MOTEUR.md « acteurs » §4). */
    public boolean gameOver() {
        return stat[LIVES] < 0;
    }

    /** L'arme en main, transmise d'un niveau au suivant comme le menu le fait (L0132AC). */
    private int weaponInHand = Weapons.PISTOL;

    public int weaponInHand() {
        return weaponInHand;
    }

    public void setWeaponInHand(int weapon) {
        weaponInHand = weapon;
    }

    /** Après une mort : santé et munitions de base remises à 99 (§6.2). */
    public void revive() {
        stat[HEALTH] = 99;
        stat[AMMO_1] = 99;
    }
}
