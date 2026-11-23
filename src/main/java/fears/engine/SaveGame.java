package fears.engine;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Une partie sauvegardée : les champs de l'enregistrement de 512 octets que le menu écrit sur
 * la disquette de sauvegarde ({@code L014444}) et relit ({@code L014524}).
 *
 * <pre>
 * +00  3 longs   temps du niveau, temps total, score       (L013112)
 * +0C  9 mots    vies, L01490e, rolling, roquettes, plasma, tronçonneuse,
 *                balles, roquettes, cellules                 (L0132b0)
 * +1E  mot       n° du niveau suivant (0..29)                (L011cc8 + 1)
 * +20  long      −1 : arme par défaut                        (L0132ac)
 * +24  3 mots    murs/plafond, tramage, taille d'écran       (L011cb8, L011cba, L011cbe)
 *                (rétablis au chargement)
 * +2A  "Fred"    marque d'un emplacement occupé
 * +2E  mot       difficulté                                  (L011cc0)
 * </pre>
 *
 * <p>La santé n'est pas sauvegardée : le moteur repart à 99. Le portage range chaque
 * emplacement dans un fichier texte lisible plutôt que sur une piste de disquette.
 */
public record SaveGame(int level, long levelTime, long totalTime, long score, int lives,
                       boolean rolling, boolean rocket, boolean plasma, boolean chainsaw,
                       int bullets, int rockets, int cells, int difficulty,
                       boolean wallsOff, boolean ceilingOff, boolean dithering, int screenSize) {

    /** Nombre d'emplacements. */
    public static final int SLOTS = 11;

    /** L'état d'une partie au moment de sauvegarder, pour reprendre au niveau {@code nextLevel}. */
    public static SaveGame of(Stats stats, int nextLevel, long levelTime, int difficulty,
                              boolean wallsOff, boolean ceilingOff, boolean dithering, int screenSize) {
        return new SaveGame(nextLevel, levelTime, stats.totalTime(), stats.score(),
                stats.get(Stats.LIVES), stats.get(Stats.ROLLING) != 0, stats.get(Stats.ROCKET) != 0,
                stats.get(Stats.PLASMA) != 0, stats.get(Stats.CHAINSAW) != 0,
                stats.get(Stats.AMMO_1), stats.get(Stats.AMMO_2), stats.get(Stats.AMMO_3), difficulty,
                wallsOff, ceilingOff, dithering, screenSize);
    }

    /** Les stats d'une partie reprise : santé 99, arme par défaut. */
    public Stats toStats() {
        Stats stats = new Stats();
        stats.restore(this);
        return stats;
    }

    static Path file(Path directory, int slot) {
        return directory.resolve(String.format("partie%02d.sav", slot + 1));
    }

    /** L'emplacement {@code slot} (0 à 10), ou {@code null} s'il est vide ou illisible. */
    public static SaveGame read(Path directory, int slot) {
        Path file = file(directory, slot);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
            if (!"Fred".equals(p.getProperty("marque"))) {
                return null;
            }
            return new SaveGame(i(p, "niveau"), l(p, "tempsNiveau"), l(p, "tempsTotal"), l(p, "score"),
                    i(p, "vies"), b(p, "rolling"), b(p, "roquettes"), b(p, "plasma"), b(p, "tronconneuse"),
                    i(p, "balles"), i(p, "munitionsRoquettes"), i(p, "cellules"), i(p, "difficulte"),
                    b(p, "mursUnis", false), b(p, "solsUnis", false), b(p, "tramage", false),
                    i(p, "tailleVue", 0));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public void write(Path directory, int slot) throws IOException {
        Files.createDirectories(directory);
        Properties p = new Properties();
        p.setProperty("marque", "Fred");
        p.setProperty("niveau", String.valueOf(level));
        p.setProperty("tempsNiveau", String.valueOf(levelTime));
        p.setProperty("tempsTotal", String.valueOf(totalTime));
        p.setProperty("score", String.valueOf(score));
        p.setProperty("vies", String.valueOf(lives));
        p.setProperty("rolling", String.valueOf(rolling));
        p.setProperty("roquettes", String.valueOf(rocket));
        p.setProperty("plasma", String.valueOf(plasma));
        p.setProperty("tronconneuse", String.valueOf(chainsaw));
        p.setProperty("balles", String.valueOf(bullets));
        p.setProperty("munitionsRoquettes", String.valueOf(rockets));
        p.setProperty("cellules", String.valueOf(cells));
        p.setProperty("difficulte", String.valueOf(difficulty));
        p.setProperty("mursUnis", String.valueOf(wallsOff));
        p.setProperty("solsUnis", String.valueOf(ceilingOff));
        p.setProperty("tramage", String.valueOf(dithering));
        p.setProperty("tailleVue", String.valueOf(screenSize));
        try (Writer out = Files.newBufferedWriter(file(directory, slot), StandardCharsets.UTF_8)) {
            p.store(out, "Fears - partie sauvegardee (emplacement " + (slot + 1) + ")");
        }
    }

    private static int i(Properties p, String key) {
        return Integer.parseInt(p.getProperty(key).trim());
    }

    private static long l(Properties p, String key) {
        return Long.parseLong(p.getProperty(key).trim());
    }

    private static boolean b(Properties p, String key) {
        return Boolean.parseBoolean(p.getProperty(key).trim());
    }

    /** Champ ajouté après coup : absent des sauvegardes plus anciennes. */
    private static boolean b(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int i(Properties p, String key, int fallback) {
        String value = p.getProperty(key);
        return value == null ? fallback : Integer.parseInt(value.trim());
    }
}
