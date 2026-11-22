package fears.engine;

/**
 * La table de sinus du moteur ({@code L0154d8}, MOTEUR.md §8).
 *
 * <p>Source : 361 mots {@code S[d] = trunc(32767·sin d°)} rangés à 0x182D4 de {@code d1_0282da}
 * (tronqués et non arrondis : S[1] = 571, et non 572).
 * Le moteur en tire 1080 entrées, trois par degré, par interpolation tronquée :
 * {@code T[3d+r] = S[d] + r·trunc((S[d+1] − S[d]) / 3)}, et remplace toute valeur nulle par 1.
 *
 * <p>Les angles se comptent en <em>octets</em> de cette table, comme dans le moteur : un tour
 * vaut 0x10E0, un cran (1/3°) vaut 4. {@code sin(a) = T[a/4]}, {@code cos(a) = T[a/4 + 270]}.
 * Valeurs en Q15.
 */
public final class Trig {

    /** Un tour, en octets de table. */
    public static final int FULL_TURN = 0x10E0;
    /** Un quart de tour. */
    public static final int QUARTER = 0x438;
    public static final int ENTRIES = FULL_TURN / 4;

    private final int[] table = new int[ENTRIES];

    /** @param degrees les 361 valeurs S[0..360] lues dans le moteur */
    public Trig(int[] degrees) {
        if (degrees.length < 361) {
            throw new IllegalArgumentException("361 valeurs attendues");
        }
        for (int d = 0; d < 360; d++) {
            int step = (degrees[d + 1] - degrees[d]) / 3;
            for (int r = 0; r < 3; r++) {
                int value = degrees[d] + r * step;
                table[3 * d + r] = value == 0 ? 1 : value;
            }
        }
    }

    /** La même table, calculée plutôt que lue (pour les tests sans ressources). */
    public static Trig computed() {
        int[] degrees = new int[361];
        for (int d = 0; d <= 360; d++) {
            degrees[d] = (int) (32767 * Math.sin(Math.toRadians(d)));
        }
        return new Trig(degrees);
    }

    /** Ramène un angle en octets dans [0, FULL_TURN). */
    public static int wrap(int angle) {
        return Math.floorMod(angle, FULL_TURN);
    }

    public int sin(int angle) {
        return table[wrap(angle) >> 2];
    }

    public int cos(int angle) {
        return table[wrap(angle + QUARTER) >> 2];
    }
}
