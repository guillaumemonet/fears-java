package fears.extract;

/**
 * Décompresseur utilisé par le crack PSG de Fears (bootblock → $1E0000).
 *
 * Format : en-tête de deux longs big-endian (taille décompressée, taille compressée),
 * puis les données compressées. Le flux est lu À L'ENVERS (mots de 16 bits depuis la fin)
 * et la sortie est écrite à l'envers aussi. Chaque bloc porte deux tables de Huffman
 * canoniques : littéraux/longueurs (symboles de 9 bits) et classes de distance (4 bits).
 *
 * Traduction fidèle de la routine 68000 située à $1E0020 dans la mémoire du jeu.
 */
public final class Decruncher {

    private final byte[] src;
    private int srcPos;        // position (en octets) du prochain mot à lire, en reculant
    private long buf;          // réservoir de bits (les bits valides sont en bas)
    private int valid;         // nombre de bits valides dans buf

    private Decruncher(byte[] src, int end) {
        this.src = src;
        this.srcPos = end;
    }

    /** Décompresse le bloc dont l'en-tête (taille décompressée, taille compressée) commence à {@code headerOffset}. */
    public static byte[] decrunch(byte[] data, int headerOffset) {
        int unpacked = Be.l(data, headerOffset);
        int packed = Be.l(data, headerOffset + 4);
        int start = headerOffset + 8;
        if (unpacked <= 0 || packed <= 0 || start + packed > data.length) {
            throw new IllegalArgumentException("En-tête de compression invalide à 0x" + Integer.toHexString(headerOffset));
        }
        return new Decruncher(data, start + packed).run(unpacked, start);
    }

    private int readWord() {
        srcPos -= 2;
        if (srcPos < 0) {
            return 0;
        }
        return ((src[srcPos] & 0xff) << 8) | (src[srcPos + 1] & 0xff);
    }

    private void refillIfNeeded() {
        if (valid <= 16) {
            buf |= ((long) readWord()) << valid;
            valid += 16;
        }
    }

    private int getBits(int n) {
        int v = (int) (buf & ((1L << n) - 1));
        buf >>>= n;
        valid -= n;
        refillIfNeeded();
        return v;
    }

    private int getBit() {
        return getBits(1);
    }

    private byte[] run(int unpacked, int start) {
        // Initialisation : un mot = nombre de bits utiles dans le premier mot, puis un long.
        int first = readWord();              // move.w -(a2),d0
        int lowWord = readWord();            // move.l -(a2),d6 : mot faible (adresse haute)
        int highWord = readWord();           //                   mot fort (adresse basse)
        long d6 = (((long) highWord) << 16 | lowWord) & 0xffffffffL;
        int shift = 16 - first;
        buf = d6 >>> shift;
        valid = 32 - shift;
        refillIfNeeded();

        byte[] out = new byte[unpacked];
        int dst = unpacked;

        int[] litLimits = new int[16], litOffsets = new int[16];
        int[] distLimits = new int[16], distOffsets = new int[16];

        do {
            int[] distCounts = new int[16];
            int[] litCounts = new int[16];
            int[] litSyms = readTable(litCounts, 9);
            int[] distSyms = readTable(distCounts, 4);
            buildCanonical(litCounts, litLimits, litOffsets);
            buildCanonical(distCounts, distLimits, distOffsets);

            int count = getBits(16);
            for (int i = 0; i <= count; i++) {
                int sym = decodeSymbol(litLimits, litOffsets, litSyms);
                if ((sym & 0x100) != 0) {
                    out[--dst] = (byte) sym;
                    continue;
                }
                int len = sym;
                int cls = decodeSymbol(distLimits, distOffsets, distSyms);
                int nbits = cls, setBit = cls;
                if (cls == 0) {
                    nbits = 1;
                    setBit = 16;
                }
                int off = (getBits(nbits) | (1 << setBit)) & 0xffff; // classe 0 : bit 16 perdu → distance 1 ou 2
                // La copie du loader fait lea 1(a1,d0.w) (16 bits signés) mais certains fichiers du
                // disque 2 ont des distances >= 32 Ko : la distance réelle est non signée.
                int ref = dst + off + 1;
                for (int k = 0; k < len + 3; k++) {
                    out[--dst] = out[--ref];
                }
            }
        } while (getBit() != 0);

        if (dst != 0) {
            System.err.println("Avertissement : décompression incomplète, " + dst + " octets non écrits");
        }
        return out;
    }

    /** Lit le nombre de codes par longueur puis la liste des symboles (readTable à $1E016A). */
    private int[] readTable(int[] counts, int symBits) {
        int n = getBits(4);
        if (n == 0) {
            n = 0x10000; // comportement du dbra sur -1 ; ne devrait jamais arriver
        }
        int total = 0;
        for (int len = 1; len <= n && len <= 15; len++) {
            int c = getBits(Math.min(len, symBits));
            counts[len - 1] = c;
            total += c;
        }
        int[] syms = new int[Math.max(total, 1)];
        for (int i = 0; i < total; i++) {
            syms[i] = getBits(symBits);
        }
        return syms;
    }

    /** Huffman canonique : limites exclusives et décalages par longueur (buildCanon à $1E01A2). */
    private static void buildCanonical(int[] counts, int[] limits, int[] offsets) {
        int code = 0, before = 0, prevLimit = 0;
        for (int i = 0; i < 15; i++) {
            offsets[i] = (before - 2 * prevLimit) & 0xffff;
            before += counts[i];
            code += counts[i];
            limits[i] = code & 0xffff;
            prevLimit = limits[i];
            code = (code << 1) & 0xffff;
        }
        limits[15] = 0;
    }

    private int decodeSymbol(int[] limits, int[] offsets, int[] syms) {
        int code = 0;
        for (int i = 0; i < 16; i++) {
            code = ((code << 1) | getBit()) & 0xffff;
            if (code < limits[i]) {
                int idx = (code + offsets[i]) & 0xffff;
                return idx < syms.length ? syms[idx] : 0;
            }
        }
        throw new IllegalStateException("Code de Huffman invalide");
    }
}
