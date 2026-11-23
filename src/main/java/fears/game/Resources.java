package fears.game;

import fears.extract.ExtractAll;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Où trouver les ressources du jeu, et les produire s'il le faut.
 *
 * <p>Le dépôt ne contient aucune donnée du jeu d'origine. Au premier lancement, les deux
 * disquettes (ADF, ou les archives zip qui les contiennent) sont passées à l'extracteur, et
 * le résultat est rangé dans un cache : {@code ~/.fears/ressources} par défaut, ou le
 * répertoire donné par {@code -Dfears.ressources=<chemin>}. Les lancements suivants le
 * relisent directement.
 *
 * <p>Les disquettes se cherchent dans {@code -Dfears.adf=<répertoire>}, puis à côté du
 * projet ({@code ../adf}, {@code ../extract/disks}), puis dans {@code ~/.fears/adf}.
 */
public final class Resources {

    /** Fichier que l'extracteur écrit en dernier : sa présence signe une extraction complète. */
    static final String COMPLETE = "INDEX.md";
    /**
     * Version de l'extraction. À augmenter à chaque changement de l'extracteur qui modifie
     * ses sorties : un cache d'une autre version est refait au lancement suivant.
     * 2 : objets 32×32 rangés par colonnes (ils sortaient transposés).
     */
    static final int EXTRACTION_VERSION = 2;
    static final String VERSION_FILE = "version-extraction.txt";

    /** Taille d'une disquette DD. */
    static final int ADF_SIZE = 901_120;

    private static final String[] ADF_PLACES = {"../adf", "../extract/disks", "adf", "."};

    private Resources() {
    }

    /** Répertoire du cache. */
    public static Path cacheDirectory() {
        String configured = System.getProperty("fears.ressources");
        if (configured != null) {
            return Path.of(configured).toAbsolutePath();
        }
        return Path.of(System.getProperty("user.home"), ".fears", "ressources");
    }

    /**
     * Les ressources extraites, prêtes à lire.
     *
     * @throws IOException si elles manquent et que les disquettes sont introuvables
     */
    public static Path obtain() throws IOException {
        Path cache = cacheDirectory();
        if (Files.isRegularFile(cache.resolve(COMPLETE))) {
            if (upToDate(cache)) {
                return cache;
            }
            System.out.println("Ressources extraites par une version precedente de l'extracteur : nouvelle extraction.");
        }
        List<Path> disks = findDisks();
        if (disks.isEmpty()) {
            disks = askForDisks();
        }
        if (disks.isEmpty()) {
            throw new IOException("ressources absentes de " + cache
                    + " et aucune disquette de Fears trouvée.\n"
                    + "Posez les deux ADF (ou leurs zip) dans ../adf, ou indiquez leur répertoire"
                    + " avec -Dfears.adf=<chemin>.");
        }
        System.out.println("Premiere utilisation : extraction des ressources depuis");
        disks.forEach(d -> System.out.println("  " + d));
        extract(disks, cache);
        return cache;
    }

    private static boolean upToDate(Path cache) {
        try {
            return Files.readString(cache.resolve(VERSION_FILE)).trim()
                    .equals(String.valueOf(EXTRACTION_VERSION));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Le paquet autonome n'a pas de console : s'il ne trouve pas les disquettes, il demande où
     * elles sont, puis range leur dossier dans ~/.fears/adf.txt pour ne plus le redemander.
     */
    private static List<Path> askForDisks() throws IOException {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return List.of();
        }
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Fears : dossier des deux disquettes (ADF ou zip)");
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return List.of();
        }
        Path chosen = chooser.getSelectedFile().toPath();
        List<Path> disks = disksIn(chosen);
        if (!disks.isEmpty()) {
            Path memo = cacheDirectory().getParent().resolve("adf.txt");
            Files.createDirectories(memo.getParent());
            Files.writeString(memo, chosen.toAbsolutePath().toString());
        }
        return disks;
    }

    /** Extrait dans un répertoire temporaire, puis le met en place d'un seul coup. */
    private static void extract(List<Path> disks, Path cache) throws IOException {
        Path parent = cache.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path work = Files.createTempDirectory(parent, "extraction-");
        Path adfs = Files.createDirectories(work.resolve("disquettes"));
        Path out = work.resolve("ressources");
        List<String> arguments = new ArrayList<>();
        arguments.add(out.toString());
        for (int i = 0; i < disks.size(); i++) {
            arguments.add(materialize(disks.get(i), adfs.resolve("disk" + (i + 1) + ".adf")).toString());
        }
        long started = System.nanoTime();
        ExtractAll.main(arguments.toArray(String[]::new));
        if (!Files.isRegularFile(out.resolve(COMPLETE))) {
            throw new IOException("l'extraction n'a pas abouti (" + out + ")");
        }
        Files.writeString(out.resolve(VERSION_FILE), String.valueOf(EXTRACTION_VERSION));
        if (Files.exists(cache)) {
            deleteTree(cache);
        }
        Files.move(out, cache, StandardCopyOption.ATOMIC_MOVE);
        deleteTree(work);
        System.out.printf("Ressources extraites en %.1f s dans %s%n",
                (System.nanoTime() - started) / 1e9, cache);
    }

    /** Une disquette : l'ADF tel quel, ou le premier .adf d'une archive zip. */
    private static Path materialize(Path source, Path target) throws IOException {
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return source;
        }
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".adf")) {
                    copy(zip, target);
                    return target;
                }
            }
        }
        throw new IOException("aucun .adf dans " + source);
    }

    private static void copy(InputStream in, Path target) throws IOException {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Les disquettes, disque 1 d'abord. Le numéro se lit dans le nom (« Disk 1 of 2 ») ; à
     * défaut, l'ordre alphabétique fait foi.
     */
    static List<Path> findDisks() throws IOException {
        List<Path> places = new ArrayList<>();
        String configured = System.getProperty("fears.adf");
        if (configured != null) {
            places.add(Path.of(configured));
        }
        for (String place : ADF_PLACES) {
            places.add(Path.of(place));
        }
        places.add(Path.of(System.getProperty("user.home"), ".fears", "adf"));
        // Le dossier choisi lors d'un lancement précédent.
        Path memo = cacheDirectory().getParent().resolve("adf.txt");
        if (Files.isRegularFile(memo)) {
            places.add(Path.of(Files.readString(memo).trim()));
        }
        for (Path place : places) {
            List<Path> found = disksIn(place);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private static List<Path> disksIn(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> adf = new ArrayList<>();
        List<Path> zip = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.contains("fears") || !Files.isRegularFile(entry)) {
                    continue;
                }
                if (name.endsWith(".adf") && Files.size(entry) == ADF_SIZE) {
                    adf.add(entry);
                } else if (name.endsWith(".zip")) {
                    zip.add(entry);
                }
            }
        }
        List<Path> chosen = adf.size() >= 2 ? adf : zip.size() >= 2 ? zip : List.of();
        if (chosen.isEmpty()) {
            return chosen;
        }
        List<Path> sorted = new ArrayList<>(chosen);
        sorted.sort(Comparator.comparing((Path p) -> diskNumber(p)).thenComparing(Path::toString));
        return sorted.subList(0, 2);
    }

    private static int diskNumber(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int at = name.indexOf("disk ");
        if (at >= 0 && at + 5 < name.length() && Character.isDigit(name.charAt(at + 5))) {
            return name.charAt(at + 5) - '0';
        }
        return 99;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
