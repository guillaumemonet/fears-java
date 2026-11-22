package fears.extract;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Lecteur d'exécutable AmigaDOS (format « hunk »).
 *
 * Produit, pour chaque hunk, ses octets bruts et ses relocations, et sait fabriquer une
 * image « liée » : tous les hunks bout à bout à partir d'une adresse de base, relocations
 * appliquées — c'est ce qu'il faut pour désassembler avec des adresses cohérentes.
 */
public final class HunkFile {

    public static final int HUNK_HEADER = 0x3f3, HUNK_CODE = 0x3e9, HUNK_DATA = 0x3ea, HUNK_BSS = 0x3eb,
            HUNK_RELOC32 = 0x3ec, HUNK_SYMBOL = 0x3f0, HUNK_DEBUG = 0x3f1, HUNK_END = 0x3f2, HUNK_NAME = 0x3e8,
            HUNK_RELOC32SHORT = 0x3fc, HUNK_DREL32 = 0x3f7;

    public static final class Hunk {
        public int index;
        public int type;            // HUNK_CODE / HUNK_DATA / HUNK_BSS
        public int memFlags;        // 0 = indifférent, 1 = chip, 2 = fast
        public int allocSize;       // taille réservée en octets
        public byte[] data = new byte[0];
        /** cible -> liste des positions (dans ce hunk) des longs à reloger vers le hunk cible. */
        public final Map<Integer, List<Integer>> relocs = new TreeMap<>();
        public final List<String> symbols = new ArrayList<>();
        public int base;            // adresse dans l'image liée

        public String typeName() {
            return switch (type) {
                case HUNK_CODE -> "CODE";
                case HUNK_DATA -> "DATA";
                case HUNK_BSS -> "BSS";
                default -> "0x" + Integer.toHexString(type);
            };
        }

        public String memName() {
            return switch (memFlags) {
                case 1 -> "CHIP";
                case 2 -> "FAST";
                default -> "ANY";
            };
        }
    }

    public final List<Hunk> hunks = new ArrayList<>();

    public static boolean isHunkFile(byte[] d) {
        return d.length > 24 && Be.l(d, 0) == HUNK_HEADER;
    }

    public static HunkFile parse(byte[] d) {
        HunkFile hf = new HunkFile();
        int p = 4;
        // noms de bibliothèques résidentes (toujours vide pour un exécutable)
        while (true) {
            int n = Be.l(d, p);
            p += 4;
            if (n == 0) {
                break;
            }
            p += n * 4;
        }
        int table = Be.l(d, p);
        int first = Be.l(d, p + 4);
        int last = Be.l(d, p + 8);
        p += 12;
        for (int i = first; i <= last; i++) {
            int s = Be.l(d, p);
            p += 4;
            Hunk h = new Hunk();
            h.index = i;
            h.memFlags = (s >>> 30) & 3;
            h.allocSize = (s & 0x3fffffff) * 4;
            if (h.memFlags == 3) {
                p += 4; // attributs étendus
            }
            hf.hunks.add(h);
        }
        int cur = -1;
        Hunk h = null;
        while (p + 4 <= d.length) {
            int id = Be.l(d, p) & 0x3fffffff;
            p += 4;
            switch (id) {
                case HUNK_CODE, HUNK_DATA -> {
                    cur++;
                    h = hf.hunks.get(cur);
                    h.type = id;
                    int n = Be.l(d, p) * 4;
                    p += 4;
                    h.data = Arrays.copyOfRange(d, p, p + n);
                    p += n;
                }
                case HUNK_BSS -> {
                    cur++;
                    h = hf.hunks.get(cur);
                    h.type = id;
                    p += 4;
                }
                case HUNK_RELOC32 -> {
                    while (true) {
                        int n = Be.l(d, p);
                        p += 4;
                        if (n == 0) {
                            break;
                        }
                        int target = Be.l(d, p);
                        p += 4;
                        List<Integer> l = h.relocs.computeIfAbsent(target, k -> new ArrayList<>());
                        for (int k = 0; k < n; k++) {
                            l.add(Be.l(d, p));
                            p += 4;
                        }
                    }
                }
                case HUNK_RELOC32SHORT, HUNK_DREL32 -> {
                    int start = p;
                    while (true) {
                        int n = Be.w(d, p);
                        p += 2;
                        if (n == 0) {
                            break;
                        }
                        int target = Be.w(d, p);
                        p += 2;
                        List<Integer> l = h.relocs.computeIfAbsent(target, k -> new ArrayList<>());
                        for (int k = 0; k < n; k++) {
                            l.add(Be.w(d, p));
                            p += 2;
                        }
                    }
                    if (((p - start) & 3) != 0) {
                        p += 2;
                    }
                }
                case HUNK_SYMBOL -> {
                    while (true) {
                        int n = Be.l(d, p);
                        p += 4;
                        if (n == 0) {
                            break;
                        }
                        String name = new String(d, p, n * 4, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
                        p += n * 4;
                        int val = Be.l(d, p);
                        p += 4;
                        h.symbols.add(String.format("%08x %s", val, name.replace("\0", "")));
                    }
                }
                case HUNK_DEBUG, HUNK_NAME -> p += Be.l(d, p) * 4 + 4;
                case HUNK_END -> {
                }
                default -> {
                    if (cur >= hf.hunks.size() - 1) {
                        return hf; // fin du fichier (remplissage)
                    }
                    throw new IllegalStateException(String.format("Hunk inconnu 0x%x à 0x%x", id, p - 4));
                }
            }
            if (cur == hf.hunks.size() - 1 && id == HUNK_END && p + 4 > d.length) {
                break;
            }
        }
        return hf;
    }

    /** Place les hunks bout à bout (alignés sur 8) à partir de {@code base}. */
    public void layout(int base) {
        int a = base;
        for (Hunk h : hunks) {
            h.base = a;
            a += (h.allocSize + 7) & ~7;
        }
    }

    public int imageEnd() {
        Hunk l = hunks.get(hunks.size() - 1);
        return l.base + l.allocSize;
    }

    /** Image mémoire relogée, commençant à l'adresse du premier hunk. */
    public byte[] linkedImage() {
        int base = hunks.get(0).base;
        byte[] img = new byte[imageEnd() - base];
        for (Hunk h : hunks) {
            System.arraycopy(h.data, 0, img, h.base - base, Math.min(h.data.length, h.allocSize));
        }
        for (Hunk h : hunks) {
            for (var e : h.relocs.entrySet()) {
                int targetBase = hunks.get(e.getKey()).base;
                for (int off : e.getValue()) {
                    int at = h.base - base + off;
                    int v = Be.l(img, at) + targetBase;
                    img[at] = (byte) (v >>> 24);
                    img[at + 1] = (byte) (v >>> 16);
                    img[at + 2] = (byte) (v >>> 8);
                    img[at + 3] = (byte) v;
                }
            }
        }
        return img;
    }

    /** Écrit les hunks, l'image liée et un résumé texte. */
    public void export(Path dir, String name, int base) throws IOException {
        Files.createDirectories(dir);
        layout(base);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Exécutable %s : %d hunks, image liée à 0x%x-0x%x%n", name, hunks.size(), base, imageEnd()));
        for (Hunk h : hunks) {
            int nrel = h.relocs.values().stream().mapToInt(List::size).sum();
            sb.append(String.format("  hunk %d %-4s %-4s taille %7d (données %7d) base 0x%06x relocs %d%n",
                    h.index, h.typeName(), h.memName(), h.allocSize, h.data.length, h.base, nrel));
            if (h.data.length > 0) {
                Files.write(dir.resolve(String.format("%s_hunk%d_%s.bin", name, h.index, h.typeName().toLowerCase())), h.data);
            }
            for (String s : h.symbols) {
                sb.append("    sym ").append(s).append('\n');
            }
        }
        Files.write(dir.resolve(name + "_linked.bin"), linkedImage());
        // table des relocations, utile pour distinguer pointeurs et données au désassemblage
        StringBuilder rel = new StringBuilder();
        for (Hunk h : hunks) {
            for (var e : h.relocs.entrySet()) {
                for (int off : e.getValue()) {
                    rel.append(String.format("%06x\n", h.base + off));
                }
            }
        }
        Files.writeString(dir.resolve(name + "_relocs.txt"), rel);
        sb.append(String.format("hunks:%s%n", String.join(",", hunks.stream()
                .map(h -> String.format("%s@%x+%x", h.typeName(), h.base, h.data.length)).toList())));
        Files.writeString(dir.resolve(name + "_hunks.txt"), sb);
    }
}
