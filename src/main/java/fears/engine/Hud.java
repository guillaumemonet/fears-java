package fears.engine;

import fears.menu.LinkedImage;

import java.util.Arrays;

/**
 * Le HUD et l'arme en main, comme les sprites matériels de l'original (MOTEUR.md « acteurs »
 * §5, rendu §7), composés dans une image 640×256 hires transparente (les sprites, basse
 * résolution, y sont doublés).
 *
 * <p>Le moteur n'a pas d'image de HUD toute faite : il écrit les chiffres <em>dans les données
 * des sprites</em> ({@code L014652}). On fait de même sur une copie des trois sprites lus dans
 * l'exécutable, aux mêmes adresses, avec les mêmes polices, puis on décode :
 * <ul>
 *   <li>cadre AMMO, sprite 6 seul ({@code 0x20410}) : couleurs 112 + 12 + index ;</li>
 *   <li>cadre HEALTH/LIVES, sprites 4 et 5 attachés ({@code 0x20AD0}, {@code 0x21190}) :
 *       16 couleurs 112 + index ; les chiffres de santé (plan A du sprite 5) prennent la
 *       couleur 116, que le moteur passe au rouge sous 32 points ({@code L014926}).</li>
 * </ul>
 * Position à l'écran : x = HSTART − $80, y = VSTART − $2A (fenêtre d'affichage $2A81).
 */
public final class Hud {

    /** Image hires : les sprites basse résolution y sont doublés, le message de vies est en hires. */
    public static final int WIDTH = 640;
    public static final int HEIGHT = 256;

    static final int SPRITE_AMMO = 0x20410;
    static final int SPRITE_FRAME = 0x20AD0;
    static final int SPRITE_HEALTH = 0x21190;
    static final int SPRITE_LINES = 106;
    static final int SPRITE_BYTES = 16 + SPRITE_LINES * 16;
    /** Couleurs 112 à 127 : MOVE quartets hauts puis bas de l'en-tête copper. */
    static final int PALETTE_HI = 0x1501E;
    static final int PALETTE_LO = 0x15062;
    /** Couleur des chiffres de santé quand elle est basse : table {@code L014926}, indexée par santé / 4. */
    static final int LOW_HEALTH_COLORS = 0x14926;
    static final int BIG_DIGITS = 0x1C668;
    static final int SMALL_DIGITS = 0x1CF08;
    /** Noms d'armes 64×7 (deux variantes de 0x70 octets : choisie, puis normale), pistolet à tronçonneuse. */
    static final int[] WEAPON_NAMES = {0x1C8E8, 0x1C9C8, 0x1CAA8, 0x1CB88, 0x1CC68};
    static final int[] WEAPON_NAME_DEST = {0x20760, 0x207E0, 0x20860, 0x208E0, 0x20960};
    static final int SHIELD_ON = 0x1CD48;
    static final int SHIELD_ENDING = 0x1CDB8;
    static final int SHIELD_DEST = 0x20F00;

    private final LinkedImage engine;
    private final byte[] ammo = new byte[SPRITE_BYTES];
    private final byte[] frameSprite = new byte[SPRITE_BYTES];
    private final byte[] health = new byte[SPRITE_BYTES];
    private final int[] colors = new int[16];
    private final int[][] weaponFrames;
    /** Index de couleur (0 à 15) des 17 images de l'arme : deux paires de sprites attachées. */
    private final int[][] weaponIndices;
    /**
     * Éclair de bouche ({@code L0134e4}) : BPLCON4 vaut $77 sur les rangées 0 à n de la vue,
     * $66 au-delà. Au-dessus de la ligne 2(n+1), les sprites prennent donc leurs couleurs dans
     * la banque 112–127 (oranges et jaunes) au lieu de la palette de l'arme. n vient de
     * {@code L013530} (pistolet : 91, 92, 93, 92) ou {@code L013538} (rolling : 99), selon
     * l'image de tir ; les autres armes gardent la dernière valeur écrite.
     */
    static final int PISTOL_FLASH = 0x13530;
    static final int ROLLING_FLASH = 0x13538;
    private int flashRows = 91;
    private final int[] image = new int[WIDTH * HEIGHT];
    /** Éclair rouge quand le joueur est touché : 16 crans, un toutes les 3 VBL (L013b34). */
    private int flash;
    private int flashClock;
    /** Mort : intensité du fondu rouge (0 à 64) et message de vies, −1 si aucun. */
    private int deathRed;
    private int livesMessage = -1;
    /** Message hires « PRESS MOUSE TO CONTINUE … LIVES LEFT » : 320 × 7, 40 octets par ligne. */
    static final int LIVES_MESSAGE = 0x20018;
    /** [S] Ligne du message à l'écran : pas encore relevée dans la liste copper. */
    static final int MESSAGE_LINE = 124;

    /**
     * @param weaponFrames les 17 images de l'arme, 128×87 en 0xAARRGGBB (alpha 0 = transparent)
     */
    /**
     * @param weaponIndices les mêmes images en index de couleur 0..15 (0 = transparent)
     */
    public Hud(LinkedImage engine, int[][] weaponFrames, int[][] weaponIndices) {
        this.engine = engine;
        this.weaponFrames = weaponFrames;
        this.weaponIndices = weaponIndices;
        copy(SPRITE_AMMO, ammo);
        copy(SPRITE_FRAME, frameSprite);
        copy(SPRITE_HEALTH, health);
        for (int i = 0; i < 16; i++) {
            colors[i] = rgb24(engine.u16(PALETTE_HI + 4 * i + 2), engine.u16(PALETTE_LO + 4 * i + 2));
        }
    }

    private void copy(int address, byte[] target) {
        for (int i = 0; i < target.length; i++) {
            target[i] = (byte) engine.u8(address + i);
        }
    }

    static int rgb24(int hi, int lo) {
        int r = ((hi >> 8) & 15) << 4 | ((lo >> 8) & 15);
        int g = ((hi >> 4) & 15) << 4 | ((lo >> 4) & 15);
        int b = (hi & 15) << 4 | (lo & 15);
        return r << 16 | g << 8 | b;
    }

    /** L'image du HUD, 320×256 en 0xAARRGGBB. */
    public int[] image() {
        return image;
    }

    /** Séquence de mort : fondu rouge 0..64, et nombre de vies affiché (−1 : pas de message). */
    public void death(int red, int lives) {
        deathRed = red;
        livesMessage = lives;
    }

    /** Le joueur vient d'être touché. */
    public void flash() {
        flash = 16;
        flashClock = 0;
    }

    /** Recompose le HUD d'après les stats et l'arme. */
    public void update(Stats stats, Weapons weapons) {
        // Santé : deux gros chiffres, plan A du sprite 5, ligne 12, octets 2 à 5.
        bigNumber(health, SPRITE_HEALTH, 0x21262, stats.get(Stats.HEALTH));
        // Munition de l'arme en main : sprite 6, ligne 12, octets 1 à 4.
        bigNumber(ammo, SPRITE_AMMO, 0x204E1, Math.max(0, weapons.ammo()));
        smallDigit(ammo, SPRITE_AMMO, 0x20446, weapons.ammoType());
        smallNumber(ammo, SPRITE_AMMO, 0x20525, stats.get(Stats.AMMO_1));
        smallNumber(ammo, SPRITE_AMMO, 0x205B5, stats.get(Stats.AMMO_2));
        smallNumber(ammo, SPRITE_AMMO, 0x20645, stats.get(Stats.AMMO_3));
        for (int i = 0; i < WEAPON_NAMES.length; i++) {
            int w = Weapons.PISTOL + i;
            int at = WEAPON_NAME_DEST[i] - SPRITE_AMMO;
            if (weapons.owned(w)) {
                int source = WEAPON_NAMES[i] + (weapons.weapon() == w ? 0 : 0x70);
                for (int k = 0; k < 0x70; k++) {
                    ammo[at + k] = (byte) engine.u8(source + k);
                }
            } else {
                Arrays.fill(ammo, at, at + 0x70, (byte) 0);
            }
        }
        smallDigit(frameSprite, SPRITE_FRAME, 0x20E46, stats.get(Stats.LIVES));
        int shield = stats.get(Stats.SHIELD);
        int at = SHIELD_DEST - SPRITE_FRAME;
        if (shield > Stats.SHIELD_TIME - 10) {
            copyLines(SHIELD_ON, frameSprite, at);
        } else if (shield > 0 && shield <= 100) {
            copyLines(SHIELD_ENDING, frameSprite, at);
        } else {
            Arrays.fill(frameSprite, at, at + 0x70, (byte) 0);
        }
        compose(stats.get(Stats.HEALTH), weapons);
    }

    private void copyLines(int source, byte[] sprite, int at) {
        for (int k = 0; k < 0x70; k++) {
            sprite[at + k] = (byte) engine.u8(source + k);
        }
    }

    /** Deux chiffres 16×32 d'un plan (64 octets par chiffre), lignes de sprite de 16 octets. */
    private void bigNumber(byte[] sprite, int spriteAddress, int address, int value) {
        int at = address - spriteAddress;
        int tens = Math.min(9, value / 10);
        int units = value % 10;
        for (int row = 0; row < 32; row++) {
            int line = at + row * 16;
            for (int b = 0; b < 2; b++) {
                sprite[line + b] = (byte) engine.u8(BIG_DIGITS + tens * 64 + row * 2 + b);
                sprite[line + 2 + b] = (byte) engine.u8(BIG_DIGITS + units * 64 + row * 2 + b);
            }
        }
    }

    /** Un chiffre 8×7 sur deux plans : octet n des lignes de 20 octets (10 plan A, 10 plan B). */
    private void smallDigit(byte[] sprite, int spriteAddress, int address, int digit) {
        int at = address - spriteAddress;
        for (int row = 0; row < 7; row++) {
            sprite[at + row * 16] = (byte) engine.u8(SMALL_DIGITS + row * 20 + digit);
            sprite[at + row * 16 + 8] = (byte) engine.u8(SMALL_DIGITS + row * 20 + 10 + digit);
        }
    }

    private void smallNumber(byte[] sprite, int spriteAddress, int address, int value) {
        smallDigit(sprite, spriteAddress, address, Math.min(9, value / 10));
        smallDigit(sprite, spriteAddress, address + 1, value % 10);
    }

    private static int bits(byte[] sprite, int line, int x) {
        int at = 16 + line * 16 + (x >> 3);
        int bit = 7 - (x & 7);
        return ((sprite[at] >> bit) & 1) | (((sprite[at + 8] >> bit) & 1) << 1);
    }

    private void compose(int healthValue, Weapons weapons) {
        Arrays.fill(image, 0);
        int healthColor = colors[4];
        if (healthValue < 32) {
            int word = engine.u16(LOW_HEALTH_COLORS + 2 * (healthValue / 4));
            healthColor = rgb24(word, word);
        }
        for (int y = 0; y < SPRITE_LINES; y++) {
            for (int x = 0; x < 64; x++) {
                int a = bits(ammo, y, x);
                if (a != 0) {
                    put(x, y, colors[12 + a]);
                }
                int b = bits(frameSprite, y, x) | (bits(health, y, x) << 2);
                if (b != 0) {
                    put(256 + x, y, b == 4 ? healthColor : colors[b]);
                }
            }
        }
        // L'arme ensuite : les sprites 0 à 3 passent devant les 4 à 7.
        drawWeapon(weapons);
        if (deathRed > 0) {
            int alpha = Math.min(255, deathRed * 4);
            for (int i = 0; i < image.length; i++) {
                if ((image[i] >>> 24) == 0) {
                    image[i] = (alpha << 24) | 0xFF0000;
                }
            }
        }
        if (livesMessage >= 0) {
            drawLivesMessage(livesMessage);
        }
        if (flash > 0) {
            int alpha = flash * 8;
            for (int i = 0; i < image.length; i++) {
                if ((image[i] >>> 24) == 0) {
                    image[i] = (alpha << 24) | 0xFF0000;
                }
            }
            if (++flashClock >= 3) {
                flashClock = 0;
                flash--;
            }
        }
    }

    private void drawWeapon(Weapons weapons) {
        int table = weapons.weapon() == Weapons.PISTOL ? PISTOL_FLASH
                : weapons.weapon() == Weapons.ROLLING ? ROLLING_FLASH : 0;
        if (table != 0) {
            flashRows = engine.u16(table + 2 * Math.min(3, weapons.fireFrame()));
        }
        int bankLimit = 2 * (flashRows + 1);
        int[] frame = weaponFrames[weapons.image()];
        int[] indices = weaponIndices[weapons.image()];
        int top = weapons.vstart() - 0x2A;
        for (int y = 0; y < 87; y++) {
            boolean hudBank = top + y < bankLimit;
            for (int x = 0; x < 128; x++) {
                int argb = frame[y * 128 + x];
                if ((argb >>> 24) != 0) {
                    put(96 + x, top + y, hudBank ? colors[indices[y * 128 + x]] : argb & 0xFFFFFF);
                }
            }
        }
    }

    /** Un pixel basse résolution : deux pixels hires. */
    private void put(int x, int y, int rgb) {
        if (x >= 0 && y >= 0 && x < WIDTH / 2 && y < HEIGHT) {
            image[y * WIDTH + 2 * x] = 0xFF000000 | rgb;
            image[y * WIDTH + 2 * x + 1] = 0xFF000000 | rgb;
        }
    }

    /**
     * Le message de vies, centré : 320 pixels hires lus à 0x20018, le chiffre des vies posé à
     * l'octet 24 de chaque ligne. [S] Le chiffre est pris dans la police des petits chiffres du
     * HUD : celle que le moteur emploie (0x20130) n'est pas encore décodée.
     */
    private void drawLivesMessage(int lives) {
        int left = (WIDTH - 320) / 2;
        for (int row = 0; row < 7; row++) {
            int y = MESSAGE_LINE + row;
            for (int byteIndex = 0; byteIndex < 40; byteIndex++) {
                int bits = byteIndex == 24
                        ? engine.u8(SMALL_DIGITS + row * 20 + Math.max(0, Math.min(9, lives)))
                        : engine.u8(LIVES_MESSAGE + row * 40 + byteIndex);
                for (int b = 0; b < 8; b++) {
                    int x = left + byteIndex * 8 + b;
                    image[y * WIDTH + x] = ((bits >> (7 - b)) & 1) != 0 ? 0xFFCCCCDD : 0xFF000000;
                }
            }
        }
    }
}
