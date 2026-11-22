package fears.engine;

import fears.menu.LinkedImage;

/**
 * Les tables du moteur ({@code d1_0282da}) lues dans l'exécutable extrait : sinus, objets et
 * sons. Les adresses sont celles du listing {@code extract/out/exe/d1_0282da.s}.
 */
public final class EngineData {

    /** Table de sinus en degrés (MOTEUR.md §8). */
    static final int SINE = 0x182D4;
    /** Objets 1 à 14 : pointeur d'image, W, Hs, dy, en longs (§6.3 du rendu). */
    static final int OBJECTS = 0x11FFA;
    /** Première image d'objet ; chacune occupe 0x1000 octets (deux niveaux d'ombre). */
    static final int OBJECT_IMAGES = 0xDA510;
    /** Sons : pointeur, période, longueur en mots, volume, durée en VBL (12 octets). */
    static final int SOUNDS = 0x1F8A0;
    public static final int SOUND_COUNT = 15;

    /** Un objet de la grille C tel que le moteur le dessine. */
    public record ObjectSprite(int image, double width, double height, double dy) {
    }

    /** Un son du moteur : échantillon 8 bits signé joué par Paula. */
    public record Sound(byte[] pcm, int period, int volume, int duration) {
    }

    /** Sons des aliens (voie 1) : seuls l'adresse et la longueur servent ({@code L01f948}). */
    static final int ALIEN_SOUNDS = 0x1F948;
    /** Types d'aliens, 0x58 octets ({@code L015780}). */
    static final int ALIEN_TYPES = 0x15780;
    /** Modèles des projectiles d'aliens : dégâts, perte par case, image, largeur, hauteur. */
    static final int ALIEN_SHOTS = 0x126EE;

    /** Un type d'alien (MOTEUR.md « acteurs » §2.3). Images en « compteur » : image = valeur / 8. */
    public record AlienType(int frames, int health, int fireDelay, int shot, int walkLength,
                            int fireImage, int painImage, int deathImage, int deathLength,
                            boolean translucent, int painSound, int painPeriod, int painDuration,
                            int deathSound, int deathPeriod, int deathDuration, double[][] display) {
    }

    /** Modèle de projectile d'alien. */
    public record AlienShot(int damage, int loss, int image, double width, double height) {
    }

    private final Trig trig;
    private final LinkedImage engine;
    private final ObjectSprite[] objects = new ObjectSprite[15];
    private final Sound[] sounds = new Sound[SOUND_COUNT];

    public EngineData(LinkedImage engine) {
        this.engine = engine;
        int[] degrees = new int[361];
        for (int d = 0; d <= 360; d++) {
            degrees[d] = engine.s16(SINE + 2 * d);
        }
        trig = new Trig(degrees);
        for (int n = 1; n <= 14; n++) {
            int at = OBJECTS + 16 * (n - 1);
            int image = (engine.u32(at) - OBJECT_IMAGES) >> 12;
            objects[n] = new ObjectSprite(image, engine.u32(at + 4) / 65536.0,
                    engine.u32(at + 8) / 65536.0, engine.u32(at + 12) / 65536.0);
        }
        for (int i = 1; i < SOUND_COUNT; i++) {
            int at = SOUNDS + 12 * i;
            int pointer = engine.u32(at);
            int length = engine.u16(at + 6) * 2;
            byte[] pcm = new byte[length];
            for (int k = 0; k < length; k++) {
                pcm[k] = (byte) engine.u8(pointer + k);
            }
            sounds[i] = new Sound(pcm, engine.u16(at + 4), engine.u16(at + 8), engine.u16(at + 10));
        }
    }

    /** Type d'alien n° id (0 à 17). */
    public AlienType alienType(int id) {
        int at = ALIEN_TYPES + id * 0x58;
        double[][] display = new double[3][3];
        for (int state = 0; state < 3; state++) {
            for (int k = 0; k < 3; k++) {
                display[state][k] = engine.u32(at + 0x34 + 12 * state + 4 * k) / 65536.0;
            }
        }
        int pain = engine.u32(at + 0x2A);
        int death = engine.u32(at + 0x30);
        return new AlienType(engine.u32(at), engine.u16(at + 0x14), engine.u16(at + 0x18),
                engine.u16(at + 0x1A), engine.u16(at + 0x1C), engine.u16(at + 0x1E),
                engine.u16(at + 0x20), engine.u16(at + 0x22), engine.u16(at + 0x24),
                engine.u16(at + 0x26) != 0, engine.u16(at + 0x28), pain >>> 16, pain & 0xFFFF,
                engine.u16(at + 0x2E), death >>> 16, death & 0xFFFF, display);
    }

    /** Modèle de projectile d'alien 0, 1 ou 2. */
    public AlienShot alienShot(int type) {
        int at = ALIEN_SHOTS + 16 * type;
        return new AlienShot(engine.u16(at), engine.u16(at + 2), (engine.u32(at + 4) - OBJECT_IMAGES) >> 12,
                engine.u32(at + 8) / 65536.0, engine.u32(at + 12) / 65536.0);
    }

    /** Échantillon d'un son d'alien (voie 1), par son décalage dans la table. */
    public byte[] alienSample(int offset) {
        int at = ALIEN_SOUNDS + offset;
        int pointer = engine.u32(at);
        int length = engine.u16(at + 6) * 2;
        byte[] pcm = new byte[length];
        for (int k = 0; k < length; k++) {
            pcm[k] = (byte) engine.u8(pointer + k);
        }
        return pcm;
    }

    public Trig trig() {
        return trig;
    }

    /** Objet n° 1 à 14, ou {@code null}. */
    public ObjectSprite object(int number) {
        return number >= 1 && number <= 14 ? objects[number] : null;
    }

    /** Son par son décalage dans la table ({@code $0C}, {@code $18}…), comme le code le désigne. */
    public Sound sound(int offset) {
        int index = offset / 12;
        return index > 0 && index < SOUND_COUNT ? sounds[index] : null;
    }
}
