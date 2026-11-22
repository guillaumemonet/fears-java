package fears.extract;

import java.util.Map;
import java.util.TreeMap;

/**
 * Ressources de chaque niveau, codées en dur dans le chargeur (d1_000400, scripts à L01248A + niveau × 0x40).
 * Les planches d'aliens sont désignées par le mot id en tête des fichiers de sprites ({@code _setNN}).
 */
public final class LevelCatalog {

    public record Entry(int number, String walls, boolean paletteB, int[] aliens) {
    }

    private static final String[] WALLS_BY_LEVEL = new String[31];
    private static final int[][] ALIENS = {
        null,
        {0, 5, 5}, {0, 5, 4}, {0, 5, 4}, {0, 5, 1}, {5, 7, 1}, {7, 3, 1}, {15, 3, 2}, {7, 3, 1}, {16, 3, 1}, {7, 10, 1},
        {7, 3, 2}, {3, 10, 11}, {14, 10, 8}, {7, 4, 8}, {3, 10, 6}, {4, 10, 2}, {7, 1, 2}, {0, 10, 17}, {0, 3, 9}, {5, 11, 8},
        {3, 1, 2}, {7, 4, 9}, {5, 2, 8}, {3, 10, 9}, {5, 9, 6}, {10, 8, 2}, {7, 4, 17}, {3, 2, 9}, {0, 2, 13}, {6, 9, 13}};

    static {
        fill(1, 3, "d1_06c5c0");
        fill(4, 6, "d1_075f28");
        fill(7, 9, "d1_07e6ee");
        fill(10, 11, "d1_087cb6");
        fill(12, 14, "d1_091954");
        fill(15, 17, "d1_09adaa");
        fill(18, 20, "d2_063980");
        fill(21, 23, "d2_06c6be");
        fill(24, 26, "d2_07590a");
        fill(27, 29, "d2_080d88");
        fill(30, 30, "d2_0874c2");
    }

    private static void fill(int from, int to, String walls) {
        for (int i = from; i <= to; i++) {
            WALLS_BY_LEVEL[i] = walls;
        }
    }

    /** Niveau 1..30, ou null (LEVEL 00 = jeu par câble, LEVEL TEST = éditeur). */
    public static Entry forLevel(int n) {
        if (n < 1 || n > 30) {
            return null;
        }
        return new Entry(n, WALLS_BY_LEVEL[n], n == 30, ALIENS[n]);
    }

    /** Numéro de niveau tiré du titre (« LEVEL 07 BY … », « LEVL 29 … »), ou −1. */
    public static int numberFromTitle(String title) {
        var m = java.util.regex.Pattern.compile("^LEV\\w*\\s+(\\d+)").matcher(title);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    public static Map<Integer, Entry> all() {
        Map<Integer, Entry> m = new TreeMap<>();
        for (int i = 1; i <= 30; i++) {
            m.put(i, forLevel(i));
        }
        return m;
    }
}
