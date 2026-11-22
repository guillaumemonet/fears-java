package fears.engine;

/**
 * Les armes du joueur (MOTEUR.md « acteurs » §3) : choix, cadence, munitions, animation.
 *
 * <p>Six armes, touches 1 à 6 : poing, pistolet, rolling, lance-roquettes, plasma,
 * tronçonneuse. L'image affichée est l'une des 17 images 128×87 ; au tir, les images de tir
 * défilent, ou bien l'arme recule (poing, roquettes). Changer d'arme l'abaisse
 * (VSTART $D3 → $121 par pas de 5), change l'image, puis la relève ($121 → $D0 par pas de 4).
 *
 * <p>Sans aliens, les tirs n'ont pas encore de cible ; ils consomment, sonnent et s'animent
 * comme dans le jeu.
 */
public final class Weapons {

    public static final int FIST = 0;
    public static final int PISTOL = 1;
    public static final int ROLLING = 2;
    public static final int ROCKETS = 3;
    public static final int PLASMA = 4;
    public static final int CHAINSAW = 5;

    /** Image de repos de chaque arme. */
    static final int[] BASE_FRAME = {0, 1, 4, 8, 9, 13};
    /** Nombre d'images de tir et VBL par image (0 = recul). */
    static final int[] FIRE_FRAMES = {0, 2, 3, 0, 3, 3};
    static final int[] FRAME_TIME = {0, 3, 2, 0, 5, 2};
    /** Délai avant le tir suivant, en VBL. */
    static final int[] DELAY = {10, 15, 4, 32, 30, 3};
    /** Munition consommée : indice de stat, ou −1. */
    static final int[] AMMO = {-1, Stats.AMMO_1, Stats.AMMO_1, Stats.AMMO_2, Stats.AMMO_3, -1};
    /** Possession : indice de stat, ou −1 (toujours possédée). */
    static final int[] OWNED = {-1, -1, Stats.ROLLING, Stats.ROCKET, Stats.PLASMA, Stats.CHAINSAW};
    /** Son du tir (table du moteur), 0 = aucun. */
    static final int[] SOUND = {0, 0x48, 0x90, 0x78, 0x9C, 0xA8};
    /** Recul ajouté au VSTART, VBL après VBL : poing (0x134D0) et roquettes (L0134d9). */
    static final int[] FIST_RECOIL = {3, 2, 2, 1, 1, 1, 0, -3, -7};
    static final int[] ROCKET_RECOIL = {5, 3, 0, -1, -1, -1, -1, -2, -2};

    /** Position verticale de l'arme : en place, et abaissée. */
    public static final int RAISED = 0xD0;
    static final int LOWER_FROM = 0xD3;
    public static final int LOWERED = 0x121;

    private final Stats stats;
    private int weapon = PISTOL;
    private int pending = -1;
    private int vstart = RAISED;
    private boolean lowering;
    private boolean raising;
    private int cooldown;
    private int frame;
    private int frameClock;
    private int recoilStep = -1;
    private boolean sawPlaying;
    private int fired = -1;
    private java.util.function.BooleanSupplier projectileFlying = () -> false;

    /** Roquette et plasma : refus de tirer tant que le projectile précédent vole (L0126b4). */
    public void setProjectileFlying(java.util.function.BooleanSupplier flying) {
        this.projectileFlying = flying;
    }

    /** L'arme qui vient de tirer pendant la dernière VBL, une seule fois ; −1 sinon. */
    public int takeFired() {
        int w = fired;
        fired = -1;
        return w;
    }

    public Weapons(Stats stats) {
        this.stats = stats;
        int start = stats.weaponInHand();
        this.weapon = owned(start) ? start : PISTOL;
    }

    public int weapon() {
        return weapon;
    }

    /** L'arme est-elle possédée ? */
    public boolean owned(int w) {
        return OWNED[w] < 0 || stats.get(OWNED[w]) != 0;
    }

    /** Munitions de l'arme en main, ou −1 si elle n'en use pas. */
    public int ammo() {
        return AMMO[weapon] < 0 ? -1 : stats.get(AMMO[weapon]);
    }

    /** Type de munition affiché par le HUD : 1 balles, 2 roquettes, 3 cellules, 0 aucune. */
    public int ammoType() {
        return switch (AMMO[weapon]) {
            case Stats.AMMO_1 -> 1;
            case Stats.AMMO_2 -> 2;
            case Stats.AMMO_3 -> 3;
            default -> 0;
        };
    }

    /** Image à afficher (0 à 16). */
    public int image() {
        return BASE_FRAME[weapon] + frame;
    }

    /** VSTART des sprites de l'arme, recul compris. */
    public int vstart() {
        return vstart;
    }

    /** Demande une arme (touches 1 à 6, ou ramassage) : elle sera montée si elle est possédée. */
    public void select(int w) {
        if (w >= 0 && w <= CHAINSAW && owned(w) && (w != weapon || pending >= 0)) {
            pending = w;
        }
    }

    /**
     * Une VBL.
     *
     * @return le son à jouer (décalage dans la table), 0 si aucun
     */
    public int tick(boolean fire) {
        if (cooldown > 0) {
            cooldown--;
        }
        animate();
        if (switching()) {
            return 0;
        }
        if (fire && cooldown == 0) {
            return shoot();
        }
        if (!fire) {
            sawPlaying = false;
        }
        return 0;
    }

    /** Abaisse l'arme, en change, la relève ; vrai tant que le changement dure. */
    private boolean switching() {
        if (pending >= 0) {
            if (!lowering) {
                lowering = true;
                vstart = Math.max(vstart, LOWER_FROM);
                frame = 0;
                recoilStep = -1;
            }
            vstart += 5;
            if (vstart >= LOWERED) {
                vstart = LOWERED;
                weapon = pending;
                pending = -1;
                lowering = false;
                raising = true;
            }
            return true;
        }
        if (raising) {
            vstart -= 4;
            if (vstart <= RAISED) {
                vstart = RAISED;
                raising = false;
            }
            return true;
        }
        return false;
    }

    private int shoot() {
        if ((weapon == ROCKETS || weapon == PLASMA) && projectileFlying.getAsBoolean()) {
            return 0;
        }
        int ammo = AMMO[weapon];
        if (ammo >= 0 && stats.get(ammo) == 0) {
            autoSwitch();
            return 0;
        }
        if (ammo >= 0) {
            stats.add(ammo, -1);
        }
        cooldown = DELAY[weapon];
        fired = weapon;
        if (FIRE_FRAMES[weapon] > 0) {
            frame = 1;
            frameClock = 0;
        } else {
            recoilStep = 0;
        }
        if (weapon == CHAINSAW) {
            if (sawPlaying) {
                return 0;               // le son de la tronçonneuse n'est pas relancé
            }
            sawPlaying = true;
        }
        return SOUND[weapon];
    }

    /** Munition épuisée : plasma → roquettes → rolling → pistolet → tronçonneuse → poing. */
    private void autoSwitch() {
        if (stats.get(Stats.ROCKET) != 0 && stats.get(Stats.AMMO_2) != 0 && weapon != ROCKETS) {
            select(ROCKETS);
        } else if (stats.get(Stats.ROLLING) != 0 && weapon != ROLLING) {
            select(ROLLING);
        } else if (stats.get(Stats.AMMO_1) != 0 && weapon != PISTOL) {
            select(PISTOL);
        } else if (stats.get(Stats.CHAINSAW) != 0) {
            select(CHAINSAW);
        } else {
            select(FIST);
        }
    }

    /** {@code L013400} : images de tir, ou recul. */
    private void animate() {
        if (frame > 0) {
            if (++frameClock >= FRAME_TIME[weapon]) {
                frameClock = 0;
                frame++;
                if (frame > FIRE_FRAMES[weapon]) {
                    frame = 0;
                }
            }
        }
        if (recoilStep >= 0) {
            int[] table = weapon == FIST ? FIST_RECOIL : ROCKET_RECOIL;
            vstart += table[recoilStep];
            if (++recoilStep >= table.length) {
                recoilStep = -1;
            }
        }
    }
}
