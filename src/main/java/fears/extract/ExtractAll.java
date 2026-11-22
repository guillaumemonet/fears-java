package fears.extract;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Extraction complète des ressources de Fears (AGA, 1995) depuis les deux ADF.
 *
 * <pre>
 * java fears.extract.ExtractAll &lt;dossier de sortie&gt; disk1.adf disk2.adf
 * </pre>
 *
 * Sortie :
 * <ul>
 * <li>raw/ — chaque bloc du disque décompressé, tel quel (+ manifest.csv)</li>
 * <li>exe/ — exécutables : hunks séparés, image liée et relogée, relocations</li>
 * <li>sound/ — échantillons 8SVX (IFF d'origine + WAV)</li>
 * <li>sprites/ — planches d'aliens/objets 64×64 avec leur palette</li>
 * <li>walls/ — textures murales 64×64 (palette du moteur) + ambiances sonores</li>
 * <li>engine/ — armes en main, cadres du HUD, chiffres, messages, palettes</li>
 * <li>objects/ — objets 32×32 (bonus, clés, explosions…)</li>
 * <li>pictures/ — images plein écran</li>
 * <li>levels/ — niveaux : en-tête, calques en PNG</li>
 * </ul>
 */
public final class ExtractAll {

    public static final int LEVEL_SIZE = 106536;

    private final Path out;
    private final StringBuilder index = new StringBuilder();
    private final Map<String, String> seen = new HashMap<>();

    private ExtractAll(Path out) {
        this.out = out;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage : ExtractAll <sortie> <disk1.adf> [disk2.adf]");
            System.exit(1);
        }
        new ExtractAll(Path.of(args[0])).run(Arrays.copyOfRange(args, 1, args.length));
    }

    private Path dir(String name) throws IOException {
        Path p = out.resolve(name);
        Files.createDirectories(p);
        return p;
    }

    private EngineAssets engine;
    private byte[] menuImage;

    private void run(String[] adfs) throws IOException {
        Path raw = dir("raw");
        StringBuilder manifest = new StringBuilder("disk;offset;end;packed;flags;size;sha1;type;file;duplicate_of\n");
        index.append("# Fears — inventaire des ressources extraites\n\n");
        List<DiskExtractor.Chunk> unique = new ArrayList<>();
        for (int i = 0; i < adfs.length; i++) {
            int disk = i + 1;
            byte[] adf = Files.readAllBytes(Path.of(adfs[i]));
            if (adf.length != 901120) {
                System.err.println("AVERTISSEMENT : " + adfs[i] + " ne fait pas 901120 octets (ADF DD attendu)");
            }
            String id = new String(adf, 0x3f8, 4, StandardCharsets.ISO_8859_1);
            System.out.printf("Disque %d : '%s' n°%d%n", disk, id, Be.l(adf, 0x3fc));
            Files.write(raw.resolve("d" + disk + "_bootblock.bin"), Arrays.copyOf(adf, 0x400));
            for (DiskExtractor.Chunk c : DiskExtractor.scan(disk, adf)) {
                String file = c.name() + ".bin";
                Files.write(raw.resolve(file), c.data());
                String sha = DiskExtractor.sha1(c.data());
                String dup = seen.get(sha);
                manifest.append(String.format("%d;%06x;%06x;%s;%d;%d;%s;%s;%s;%s%n", disk, c.offset(), c.end(), c.packed(),
                        c.flags(), c.data().length, sha, classify(c), file, dup == null ? "" : dup));
                if (dup == null) {
                    seen.put(sha, c.name());
                    unique.add(c);
                }
            }
        }
        // Le moteur (seul exécutable à 4 hunks) porte les palettes des murs et des objets.
        // Le menu (d1_000400) porte les palettes de l'image du château et de l'écran du mode liaison.
        for (DiskExtractor.Chunk c : unique) {
            if ("exe".equals(classify(c))) {
                HunkFile hf = HunkFile.parse(c.data());
                hf.layout(EngineAssets.BASE);
                byte[] linked = hf.linkedImage();
                if (hf.hunks.size() == 4) {
                    engine = new EngineAssets(linked);
                    engine.exportAll(out, index);
                }
                if (c.name().equals("d1_000400")) {
                    menuImage = linked;
                }
                ExeAssets.export(c.name(), linked, out, index);
            }
        }
        if (menuImage == null) {
            System.err.println("AVERTISSEMENT : menu introuvable — image du château et écran liaison en niveaux de gris");
        }
        if (engine == null) {
            System.err.println("AVERTISSEMENT : moteur introuvable — murs et objets sortiront en niveaux de gris");
        }
        for (DiskExtractor.Chunk c : unique) {
            String type = classify(c);
            try {
                export(c, type);
            } catch (RuntimeException e) {
                System.err.println("AVERTISSEMENT : export de " + c.name() + " (" + type + ") en échec : " + e);
                e.printStackTrace();
            }
        }
        Files.writeString(out.resolve("manifest.csv"), manifest);
        Files.writeString(out.resolve("INDEX.md"), index);
        System.out.println("Terminé : " + out.toAbsolutePath());
    }

    // ------------------------------------------------------------------ classification

    static String classify(DiskExtractor.Chunk c) {
        byte[] d = c.data();
        if (!c.packed()) {
            return Be.l(d, 0) == DiskExtractor.MAGIC ? "corrupt" : "raw";
        }
        if (HunkFile.isHunkFile(d)) {
            return "exe";
        }
        if (d.length == LEVEL_SIZE && d[8] == 'L' && d[9] == 'E' && d[10] == 'V') {
            return "level";
        }
        if (isSpriteSet(d)) {
            return "sprites";
        }
        if (d.length == 320 * 512) {
            return "picture";
        }
        if (d.length == 20 * 32 * 32) {
            return "objects";     // 20 objets 32×32, palette du moteur (chargé à 0x195FE0)
        }
        if (d.length == 320 * 85 * 3 / 8) {
            return "linkpic";     // écran du mode liaison : 320×85, 3 plans entrelacés
        }
        if (d.length == 74746) {
            return "deltasamples"; // échantillons 8 bits codés en delta, décodés par le menu à 0x5ECB8
        }
        if (d.length > 65536 && looksLikeWallSet(d)) {
            return "walls";
        }
        return "unknown";
    }

    /** id (mot) + 256 couleurs 12 bits + N images 64×64. */
    static boolean isSpriteSet(byte[] d) {
        if (d.length <= 514 || (d.length - 514) % 4096 != 0) {
            return false;
        }
        for (int i = 0; i < 256; i++) {
            if ((Be.w(d, 2 + 2 * i) & 0xf000) != 0) {
                return false;
            }
        }
        return true;
    }

    /** 16 textures 64×64 en tête : forte corrélation verticale à 64 octets d'écart. */
    static boolean looksLikeWallSet(byte[] d) {
        long same64 = 0, same1 = 0;
        for (int i = 0; i < 65536 - 64; i++) {
            same64 += Math.abs((d[i] & 0xff) - (d[i + 64] & 0xff));
            same1 += Math.abs((d[i] & 0xff) - (d[i + 37] & 0xff));
        }
        return same64 * 2 < same1;
    }

    // ------------------------------------------------------------------ export

    private void export(DiskExtractor.Chunk c, String type) throws IOException {
        String n = c.name();
        byte[] d = c.data();
        switch (type) {
            case "exe" -> exportExe(n, d);
            case "level" -> exportLevel(n, d);
            case "sprites" -> exportSprites(n, d);
            case "picture" -> exportPicture(n, d);
            case "walls" -> exportWalls(n, d);
            case "objects" -> exportObjects(n, d);
            case "linkpic" -> exportLinkPicture(n, d);
            case "deltasamples" -> exportDeltaSamples(n, d);
            default -> index.append(String.format("- `%s` : %s, %d octets (non interprété)%n", n, type, d.length));
        }
    }

    private void exportExe(String n, byte[] d) throws IOException {
        HunkFile hf = HunkFile.parse(d);
        Path ex = dir("exe");
        // Base 0x10000 : arbitraire, mais évite que les adresses relogées ressemblent à de petites constantes.
        hf.export(ex, n, 0x10000);
        int insns = fears.disasm.DisasmListing.write(d, n, ex.resolve(n + ".s"));
        index.append(String.format("- `%s` : exécutable, %d hunks, %d instructions désassemblées → exe/%s.s%n", n, hf.hunks.size(), insns, n));
        // sons intégrés
        for (HunkFile.Hunk h : hf.hunks) {
            for (Audio.Sample s : Audio.find8svx(h.data)) {
                Path snd = dir("sound");
                String base = String.format("%s_h%d_%05x_%s", n, h.index, s.offset(),
                        s.name() == null ? "sample" : s.name().replaceAll("[^A-Za-z0-9._-]", "_"));
                Files.write(snd.resolve(base + ".8svx"), s.iff());
                Audio.writeWav(snd.resolve(base + ".wav"), s.pcmSigned(), s.rate());
                index.append(String.format("  - son `%s` %d Hz, %d octets → sound/%s.wav%n", s.name(), s.rate(),
                        s.pcmSigned().length, base));
            }
        }
    }

    private void exportSprites(String n, byte[] d) throws IOException {
        int id = Be.w(d, 0);
        int count = (d.length - 514) / 4096;
        int[] pal = Images.rgb4Palette(d, 2, 256);
        Path p = dir("sprites");
        String base = String.format("%s_set%02d", n, id);
        Images.write(Images.tileSheet(d, 514, count, 64, Math.min(count, 6), pal, true), p.resolve(base + ".png"));
        // chaque image séparément, pour la réutiliser directement dans un moteur
        Path frames = dir("sprites/" + base);
        for (int t = 0; t < count; t++) {
            BufferedImage img = Images.indexed(64, 64, pal, true);
            Images.blit(img, d, 514 + t * 4096, 64, 64, 0, 0);
            Images.write(img, frames.resolve(String.format("%02d.png", t)));
        }
        writePalettePng(pal, p.resolve(base + "_palette.png"));
        index.append(String.format("- `%s` : planche de sprites n°%d, %d images 64×64 → sprites/%s.png%n", n, id, count, base));
    }

    private void exportPicture(String n, byte[] d) throws IOException {
        // Écran de chargement entre les niveaux ; le menu en affiche une fenêtre de 256 lignes selon le niveau.
        int[] pal = menuImage != null ? ExeAssets.rgb8Palette(menuImage, ExeAssets.CASTLE_PALETTE, 256) : Images.grayPalette(32);
        BufferedImage img = Images.indexed(320, 512, pal, false);
        Images.blit(img, d, 0, 320, 512, 0, 0);
        Images.write(img, dir("pictures").resolve(n + "_chateau_320x512.png"));
        if (menuImage != null) {
            ExeAssets.castleAnimations(menuImage, d, pal, dir("pictures").resolve("chateau_animations"), index);
        }
        index.append(String.format("- `%s` : château, écran de chargement entre niveaux, 320×512 (palette du menu 0x1660e) → pictures/%n", n));
    }

    /** Le seul jeu de murs du niveau 30 (d2_0874c2) utilise la palette B. */
    static boolean usesPaletteB(String name) {
        return name.equals("d2_0874c2");
    }

    private void exportWalls(String n, byte[] d) throws IOException {
        Path p = dir("walls");
        int[] pal;
        String suffix;
        if (engine != null) {
            pal = engine.wallPalette(usesPaletteB(n));
            suffix = usesPaletteB(n) ? "palB" : "palA";
        } else {
            pal = Images.grayPalette(256);
            suffix = "gris";
        }
        Images.write(Images.tileSheet(d, 0, 16, 64, 8, pal, false), p.resolve(n + "_textures_" + suffix + ".png"));
        Path td = dir("walls/" + n);
        for (int t = 0; t < 16; t++) {
            BufferedImage img = Images.indexed(64, 64, pal, false);
            Images.blit(img, d, t * 4096, 64, 64, 0, 0);
            Images.write(img, td.resolve(String.format("%02d.png", t)));
        }
        Files.write(p.resolve(n + "_textures.bin"), Arrays.copyOf(d, 65536));
        exportAmbience(n, d, td);
        index.append(String.format("- `%s` : 16 textures murales 64×64 (palette %s) + 2 ambiances sonores en boucle → walls/%s/%n",
                n, suffix, n));
    }

    /**
     * Suite d'un fichier de murs (à +0x10000) : mots len1, len2, loop1, loop2 ; len1+len2 octets
     * d'échantillons codés en delta (acc += b) ; puis deux listes de triplets (trames, volume 0–64, période)
     * terminées par $FFFF, interpolées linéairement par le moteur sur AUD2/AUD3.
     */
    private void exportAmbience(String n, byte[] d, Path td) throws IOException {
        int o = 65536;
        int len1 = Be.w(d, o), len2 = Be.w(d, o + 2), loop1 = Be.w(d, o + 4), loop2 = Be.w(d, o + 6);
        int s = o + 8;
        byte[] pcm = deltaDecode(d, s, len1 + len2);
        int firstPeriod = 0;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Ambiance canal 2 : %d octets, boucle à %d ; canal 3 : %d octets, boucle à %d%n", len1, loop1, len2, loop2));
        sb.append("Le moteur joue chaque canal depuis début + boucle + 200, longueur (len − boucle)/2 − 100 mots.\n");
        int t = s + len1 + len2;
        for (int ch = 0; ch < 2; ch++) {
            sb.append(String.format("Enveloppe canal %d (trames, volume, période) :%n", ch + 2));
            while (t + 2 <= d.length && Be.w(d, t) != 0xffff) {
                int frames = Be.w(d, t), vol = Be.w(d, t + 2), per = Be.w(d, t + 4);
                if (firstPeriod == 0 && per != 0) {
                    firstPeriod = per;
                }
                sb.append(String.format("  %5d %3d %5d%n", frames, vol, per));
                t += 6;
            }
            t += 2;
        }
        int rate = firstPeriod > 0 ? 3546895 / firstPeriod : 8363; // horloge Paula PAL
        Audio.writeWav(td.resolve("ambiance_canal2.wav"), Arrays.copyOfRange(pcm, 0, len1), rate);
        Audio.writeWav(td.resolve("ambiance_canal3.wav"), Arrays.copyOfRange(pcm, len1, len1 + len2), rate);
        sb.append(String.format("WAV écrits à %d Hz (première période de l'enveloppe).%n", rate));
        Files.writeString(td.resolve("ambiance.txt"), sb);
    }

    static byte[] deltaDecode(byte[] d, int off, int len) {
        byte[] r = new byte[len];
        int acc = 0;
        for (int i = 0; i < len && off + i < d.length; i++) {
            acc = i == 0 ? d[off] : acc + d[off + i];
            r[i] = (byte) acc;
        }
        return r;
    }

    private void exportObjects(String n, byte[] d) throws IOException {
        int[] pal = engine != null ? engine.objectPalette() : Images.grayPalette(256);
        Path p = dir("objects");
        // Les objets sont rangés par colonnes : le moteur lit texel(u,v) = img[u·32 + v].
        BufferedImage sheet = Images.indexed(10 * 32, 2 * 32, pal, true);
        Path od = dir("objects/" + n);
        for (int t = 0; t < 20; t++) {
            BufferedImage img = Images.indexed(32, 32, pal, true);
            Images.blitColumns(img, d, t * 1024, 32, 32, 0, 0);
            Images.blitColumns(sheet, d, t * 1024, 32, 32, (t % 10) * 32, (t / 10) * 32);
            Images.write(img, od.resolve(String.format("%02d.png", t)));
        }
        Images.write(sheet, p.resolve(n + "_objets_32x32.png"));
        index.append(String.format("- `%s` : 20 objets 32×32 (armes à ramasser, soins, clés, explosions, tonneaux) → objects/%n", n));
    }

    private void exportLinkPicture(String n, byte[] d) throws IOException {
        int[] pal = menuImage != null ? ExeAssets.rgb8Palette(menuImage, ExeAssets.LINK_PALETTE, 8) : Images.grayPalette(8);
        BufferedImage img = Images.indexed(320, 85, pal, false);
        for (int y = 0; y < 85; y++) {
            for (int x = 0; x < 320; x++) {
                int v = 0;
                for (int pl = 0; pl < 3; pl++) {
                    int o = (y * 3 + pl) * 40 + (x >> 3);
                    v |= ((d[o] >> (7 - (x & 7))) & 1) << pl;
                }
                img.getRaster().setSample(x, y, 0, v);
            }
        }
        Images.write(img, dir("pictures").resolve(n + "_liaison_320x85.png"));
        index.append(String.format("- `%s` : écran de chargement du mode null-modem 320×85, 3 plans (palette du menu 0x1780e) → pictures/%n", n));
    }

    /** Module ProTracker du menu, codé en delta (le menu le décode par somme cumulée avant de le jouer). */
    private void exportDeltaSamples(String n, byte[] d) throws IOException {
        String title = ExeAssets.writeModule(d, 0, d.length, dir("music").resolve(n + "_menu.mod"));
        index.append(String.format("- `%s` : module ProTracker « %s » (musique du menu, codé en delta) → music/%s_menu.mod%n", n, title, n));
    }

    private void exportLevel(String n, byte[] d) throws IOException {
        Path p = dir("levels");
        Level lvl = new Level(d);
        int num = LevelCatalog.numberFromTitle(lvl.title);
        LevelCatalog.Entry cat = LevelCatalog.forLevel(num);
        String base = num >= 0 ? String.format("niveau%02d_%s", num, n) : "niveau_" + n;
        if (lvl.title.contains("TEST")) {
            base = "niveau_test_" + n;
            cat = null;
        }
        Files.write(p.resolve(base + ".lvl"), d);
        Files.writeString(p.resolve(base + ".json"), lvl.toJson(n, cat));
        Images.write(lvl.renderMap(10), p.resolve(base + "_carte.png"));
        String res = cat == null ? "jeu par câble / éditeur"
                : String.format("murs %s (palette %s), aliens %d/%d/%d", cat.walls(), cat.paletteB() ? "B" : "A",
                        cat.aliens()[0], cat.aliens()[1], cat.aliens()[2]);
        index.append(String.format("- `%s` : « %s » — %d objets, %d portes, %d déclencheurs ; %s → levels/%s.*%n", n, lvl.title,
                lvl.items.size(), lvl.doors.size(), lvl.triggers.size(), res, base));
    }

    // ------------------------------------------------------------------ utilitaires

    private static int[] falseColors() {
        int[] p = new int[256];
        p[0] = 0;
        for (int i = 1; i < 256; i++) {
            float h = (i * 0.618034f) % 1f;
            p[i] = java.awt.Color.HSBtoRGB(h, 0.7f, 0.95f) & 0xffffff;
        }
        return p;
    }

    private static BufferedImage scale(BufferedImage src, int f) {
        BufferedImage dst = new BufferedImage(src.getWidth() * f, src.getHeight() * f, BufferedImage.TYPE_BYTE_INDEXED,
                (java.awt.image.IndexColorModel) src.getColorModel());
        for (int y = 0; y < dst.getHeight(); y++) {
            for (int x = 0; x < dst.getWidth(); x++) {
                dst.getRaster().setSample(x, y, 0, src.getRaster().getSample(x / f, y / f, 0));
            }
        }
        return dst;
    }

    private static void writePalettePng(int[] pal, Path p) throws IOException {
        BufferedImage img = Images.indexed(16 * 8, 16 * 8, pal, false);
        var r = img.getRaster();
        for (int i = 0; i < 256; i++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    r.setSample((i % 16) * 8 + x, (i / 16) * 8 + y, 0, i);
                }
            }
        }
        Images.write(img, p);
    }
}
