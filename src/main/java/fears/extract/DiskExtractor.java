package fears.extract;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Extrait tous les fichiers des disquettes de Fears (version crackée PSG).
 *
 * Les disques n'ont pas de système de fichiers : les données sont rangées bout à bout,
 * chacune compressée avec l'en-tête {@code $18051973, mot, taille décompressée, taille compressée}.
 * Le loader lit ces blocs par position absolue dans l'image (pistes de $1600 octets).
 * Les zones entre deux blocs compressés sont exportées brutes.
 */
public final class DiskExtractor {

    public static final int MAGIC = 0x18051973;

    /** Un morceau du disque : compressé (packed) ou brut. */
    public record Chunk(int disk, int offset, int end, boolean packed, int flags, byte[] data) {
        public String name() {
            return String.format("d%d_%06x%s", disk, offset, packed ? "" : "_raw");
        }
    }

    public static List<Chunk> scan(int disk, byte[] adf) {
        List<Chunk> out = new ArrayList<>();
        int rawStart = 0x400; // le bootblock (2 secteurs) est exporté à part
        int o = 0x400;
        while (o + 14 <= adf.length) {
            if (Be.l(adf, o) == MAGIC) {
                int flags = Be.w(adf, o + 4);
                int unpacked = Be.l(adf, o + 6);
                int packed = Be.l(adf, o + 10);
                if (unpacked > 0 && unpacked < 0x200000 && packed > 0 && o + 14 + packed <= adf.length) {
                    if (o > rawStart) {
                        addRaw(out, disk, adf, rawStart, o);
                    }
                    byte[] data;
                    boolean ok = true;
                    try {
                        data = Decruncher.decrunch(adf, o + 6);
                    } catch (RuntimeException e) {
                        System.err.printf("AVERTISSEMENT : disque %d, bloc %06x indécompressable (%s) — exporté compressé%n",
                                disk, o, e);
                        data = Arrays.copyOfRange(adf, o, o + 14 + packed);
                        ok = false;
                    }
                    out.add(new Chunk(disk, o, o + 14 + packed, ok, flags, data));
                    o += 14 + packed;
                    rawStart = o;
                    continue;
                }
            }
            o += 2;
        }
        if (rawStart < adf.length) {
            addRaw(out, disk, adf, rawStart, adf.length);
        }
        return out;
    }

    private static void addRaw(List<Chunk> out, int disk, byte[] adf, int from, int to) {
        byte[] d = Arrays.copyOfRange(adf, from, to);
        boolean empty = true;
        for (byte b : d) {
            if (b != 0) {
                empty = false;
                break;
            }
        }
        if (!empty) {
            out.add(new Chunk(disk, from, to, false, 0, d));
        }
    }

    static String sha1(byte[] d) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(d);
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage : DiskExtractor <sortie> <disk1.adf> [disk2.adf]");
            System.exit(1);
        }
        Path outDir = Path.of(args[0]);
        Path raw = outDir.resolve("raw");
        Files.createDirectories(raw);
        StringBuilder manifest = new StringBuilder("disk;offset;end;packed;flags;size;sha1;file\n");
        for (int i = 1; i < args.length; i++) {
            byte[] adf = Files.readAllBytes(Path.of(args[i]));
            if (adf.length != 901120) {
                System.err.println("AVERTISSEMENT : " + args[i] + " ne fait pas 901120 octets");
            }
            String id = new String(adf, 0x3f8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            System.out.printf("Disque %d : identifiant '%s' n°%d%n", i, id, Be.l(adf, 0x3fc));
            Files.write(raw.resolve("d" + i + "_bootblock.bin"), Arrays.copyOf(adf, 0x400));
            for (Chunk c : scan(i, adf)) {
                String file = c.name() + ".bin";
                Files.write(raw.resolve(file), c.data());
                manifest.append(String.format("%d;%06x;%06x;%s;%d;%d;%s;%s%n", c.disk(), c.offset(), c.end(),
                        c.packed(), c.flags(), c.data().length, sha1(c.data()), file));
                System.out.printf("  %06x-%06x %-6s flags=%d -> %7d octets  %s%n", c.offset(), c.end(),
                        c.packed() ? "packed" : "brut", c.flags(), c.data().length, file);
            }
        }
        Files.writeString(outDir.resolve("manifest.csv"), manifest);
    }
}
