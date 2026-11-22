package fears.extract;

/**
 * Lecture des couleurs d'une liste copper AGA.
 *
 * Simule les écritures dans COLOR00–31 ($180–$1BE) selon BPLCON3 ($106) : banque = bits 13–15,
 * LOCT = bit 9. Une écriture avec LOCT=0 fixe les quartets hauts ET recopie la valeur dans les bas
 * (comportement AGA) ; avec LOCT=1 elle ne fixe que les quartets bas.
 */
final class Copper {
    private Copper() {
    }

    /** Palette 24 bits obtenue en exécutant les MOVE de la liste jusqu'à $FFFF,$FFFE (ou {@code maxInsns}). */
    static int[] palette(byte[] img, int addr, int base, int maxInsns) {
        int[] hi = new int[256], lo = new int[256];
        int bplcon3 = 0;
        int o = addr - base;
        for (int i = 0; i < maxInsns && o + 4 <= img.length; i++, o += 4) {
            int a = Be.w(img, o), v = Be.w(img, o + 2);
            if (a == 0xffff && v == 0xfffe) {
                break;
            }
            if ((a & 1) != 0) {
                continue; // WAIT / SKIP
            }
            if (a == 0x106) {
                bplcon3 = v;
            } else if (a >= 0x180 && a <= 0x1be) {
                int idx = ((bplcon3 >> 13) & 7) * 32 + (a - 0x180) / 2;
                if ((bplcon3 & 0x200) == 0) {
                    hi[idx] = v & 0xfff;
                    lo[idx] = v & 0xfff;
                } else {
                    lo[idx] = v & 0xfff;
                }
            }
        }
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = EngineAssets.rgb24(hi[i], lo[i]);
        }
        return p;
    }
}
