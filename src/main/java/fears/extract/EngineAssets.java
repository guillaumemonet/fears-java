package fears.extract;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;

/**
 * Ressources du moteur de jeu (exécutable d1_0282da) et formats qui en dépendent.
 *
 * Toutes les adresses ci-dessous sont celles de l'image liée à la base 0x10000
 * (voir {@link HunkFile#layout}) : offset dans l'image = adresse − 0x10000.
 * Elles ont été établies par désassemblage du moteur et vérifiées visuellement.
 */
final class EngineAssets {

    static final int BASE = 0x10000;

    /** Palettes fixes, 256 mots $0RGB. A : tous les niveaux sauf le 30 ; B : niveau 30 ; objets 32×32. */
    static final int PAL_WALLS_A = 0x17CD4, PAL_WALLS_B = 0x17ED4, PAL_OBJECTS = 0x180D4;

    /** Armes en main : 17 images de 4 sprites matériels 64 px (2 paires attachées = 128 px, 16 couleurs). */
    static final int WEAPON_FRAMES = 0x21850, WEAPON_FRAME_SIZE = 0x1640, WEAPON_SPRITE_SIZE = 0x590, WEAPON_LINES = 87;
    /** Palettes 24 bits des armes (15 mots quartets hauts puis 15 mots quartets bas) et première image de chacune. */
    static final int[] WEAPON_PALS = {0x13560, 0x1359C, 0x135D8, 0x13614, 0x13650, 0x1368C};
    static final int[] WEAPON_PAL_FIRST_FRAME = {0, 1, 4, 8, 9, 13};

    /** Cadres du HUD : sprites 64 px, 2 plans, 106 lignes. */
    static final int[][] HUD_SPRITES = {{0x20410, 6}, {0x20AD0, 4}, {0x21190, 5}};
    /** Couleurs 112–127 (banque 3 du gabarit copper) en 24 bits : MOVE quartets hauts puis bas. */
    static final int HUD_PAL_HI = 0x1501E, HUD_PAL_LO = 0x15062;

    final byte[] img;

    EngineAssets(byte[] linkedImage) {
        this.img = linkedImage;
    }

    int at(int addr) {
        return addr - BASE;
    }

    int[] wallPalette(boolean levelThirty) {
        return Images.rgb4Palette(img, at(levelThirty ? PAL_WALLS_B : PAL_WALLS_A), 256);
    }

    int[] objectPalette() {
        return Images.rgb4Palette(img, at(PAL_OBJECTS), 256);
    }

    /** Couleur 24 bits AGA à partir des mots $0RGB haut et bas. */
    static int rgb24(int hi, int lo) {
        int r = ((hi >> 8) & 15) << 4 | ((lo >> 8) & 15);
        int g = ((hi >> 4) & 15) << 4 | ((lo >> 4) & 15);
        int b = (hi & 15) << 4 | (lo & 15);
        return r << 16 | g << 8 | b;
    }

    /** Lit une ligne de sprite 64 px (2 plans de 8 octets) et ajoute ses bits à {@code px} (décalés de {@code shift}). */
    private void spriteLine(int off, int[] px, int x0, int shift) {
        for (int x = 0; x < 64; x++) {
            int byteI = x >> 3, bit = 7 - (x & 7);
            int a = (img[off + byteI] >> bit) & 1;
            int b = (img[off + 8 + byteI] >> bit) & 1;
            px[x0 + x] |= (a | b << 1) << shift;
        }
    }

    void exportAll(Path out, StringBuilder index) throws IOException {
        Path dir = out.resolve("engine");
        Files.createDirectories(dir);
        exportWeapons(dir, index);
        exportHud(dir, index);
        exportFonts(dir, index);
        exportMessages(dir, index);
        Images.write(paletteSheet(wallPalette(false)), dir.resolve("palette_murs_A.png"));
        Images.write(paletteSheet(wallPalette(true)), dir.resolve("palette_murs_B_niveau30.png"));
        Images.write(paletteSheet(objectPalette()), dir.resolve("palette_objets.png"));
        index.append("- moteur : palettes des murs A/B et des objets → engine/palette_*.png\n");
    }

    private void exportWeapons(Path dir, StringBuilder index) throws IOException {
        Path wd = dir.resolve("armes");
        Files.createDirectories(wd);
        for (int f = 0; f < 17; f++) {
            int p = 0;
            for (int k = 0; k < WEAPON_PAL_FIRST_FRAME.length; k++) {
                if (f >= WEAPON_PAL_FIRST_FRAME[k]) {
                    p = k;
                }
            }
            int[] pal = new int[256];
            int po = at(WEAPON_PALS[p]);
            for (int c = 0; c < 15; c++) {
                pal[c + 1] = rgb24(Be.w(img, po + 2 * c), Be.w(img, po + 30 + 2 * c));
            }
            BufferedImage im = Images.indexed(128, WEAPON_LINES, pal, true);
            int base = at(WEAPON_FRAMES) + f * WEAPON_FRAME_SIZE;
            for (int y = 0; y < WEAPON_LINES; y++) {
                int[] px = new int[128];
                for (int half = 0; half < 2; half++) {
                    int even = base + (half * 2) * WEAPON_SPRITE_SIZE + 16 + y * 16;
                    int odd = base + (half * 2 + 1) * WEAPON_SPRITE_SIZE + 16 + y * 16;
                    spriteLine(even, px, half * 64, 0);
                    spriteLine(odd, px, half * 64, 2);
                }
                im.getRaster().setPixels(0, y, 128, 1, px);
            }
            Images.write(im, wd.resolve(String.format("arme_%02d.png", f)));
        }
        index.append("- moteur : 17 images d'armes en main 128×87, 16 couleurs 24 bits → engine/armes/\n");
    }

    private void exportHud(Path dir, StringBuilder index) throws IOException {
        int[] cols = new int[16];
        for (int i = 0; i < 16; i++) {
            // MOVE $01A0+2i,valeur : la valeur est le 2e mot de chaque paire
            cols[i] = rgb24(Be.w(img, at(HUD_PAL_HI) + 4 * i + 2), Be.w(img, at(HUD_PAL_LO) + 4 * i + 2));
        }
        for (int[] s : HUD_SPRITES) {
            int num = s[1];
            int pairBase = (num / 2) * 4; // couleurs 112 + 4 × paire
            int[] pal = new int[256];
            for (int c = 1; c < 4; c++) {
                pal[c] = cols[pairBase + c];
            }
            BufferedImage im = Images.indexed(64, 106, pal, true);
            for (int y = 0; y < 106; y++) {
                int[] px = new int[64];
                spriteLine(at(s[0]) + 16 + y * 16, px, 0, 0);
                im.getRaster().setPixels(0, y, 64, 1, px);
            }
            Images.write(im, dir.resolve(String.format("hud_sprite%d_%05x.png", num, s[0])));
        }
        index.append("- moteur : 3 cadres du HUD (AMMO, HEALTH/LIVES…) 64×106 → engine/hud_*.png\n");
    }

    private void exportFonts(Path dir, StringBuilder index) throws IOException {
        int[] mono = {0, 0xffffff};
        int[] four = {0, 0x555555, 0xaaaaaa, 0xffffff};
        int[] p2 = new int[256], p4 = new int[256];
        System.arraycopy(mono, 0, p2, 0, 2);
        System.arraycopy(four, 0, p4, 0, 4);
        // Grands chiffres : 10 × 16×32, 1 plan, 64 octets chacun
        BufferedImage big = Images.indexed(160, 32, p2, true);
        for (int d = 0; d < 10; d++) {
            for (int y = 0; y < 32; y++) {
                int w = Be.w(img, at(0x1C668) + d * 64 + y * 2);
                for (int x = 0; x < 16; x++) {
                    big.getRaster().setSample(d * 16 + x, y, 0, (w >> (15 - x)) & 1);
                }
            }
        }
        Images.write(big, dir.resolve("chiffres_16x32.png"));
        // Petits chiffres : 80×7, 2 plans (10 octets plan A puis 10 octets plan B par ligne)
        BufferedImage small = Images.indexed(80, 7, p4, true);
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 80; x++) {
                int o = at(0x1CF08) + y * 20 + (x >> 3);
                int bit = 7 - (x & 7);
                int v = ((img[o] >> bit) & 1) | ((img[o + 10] >> bit) & 1) << 1;
                small.getRaster().setSample(x, y, 0, v);
            }
        }
        Images.write(small, dir.resolve("chiffres_8x7.png"));
        // Noms d'armes : 5 × 2 variantes, 64×7, lignes de sprite 2 plans
        BufferedImage names = Images.indexed(128, 35, p4, true);
        for (int n = 0; n < 10; n++) {
            for (int y = 0; y < 7; y++) {
                int[] px = new int[64];
                spriteLine(at(0x1C8E8) + n * 0x70 + y * 16, px, 0, 0);
                names.getRaster().setPixels((n % 2) * 64, (n / 2) * 7 + y, 64, 1, px);
            }
        }
        Images.write(names, dir.resolve("noms_armes.png"));
        index.append("- moteur : chiffres du HUD (16×32, 8×7) et noms d'armes → engine/\n");
    }

    private void exportMessages(Path dir, StringBuilder index) throws IOException {
        int[] p2 = new int[256];
        p2[1] = 0xccccdd;
        int[] addrs = {0x20018, 0x201B0, 0x202C8};
        BufferedImage im = Images.indexed(320, 7 * addrs.length + 2 * (addrs.length - 1), p2, false);
        for (int m = 0; m < addrs.length; m++) {
            for (int y = 0; y < 7; y++) {
                for (int x = 0; x < 320; x++) {
                    int o = at(addrs[m]) + y * 40 + (x >> 3);
                    im.getRaster().setSample(x, m * 9 + y, 0, (img[o] >> (7 - (x & 7))) & 1);
                }
            }
        }
        Images.write(im, dir.resolve("messages_hires.png"));
        index.append("- moteur : messages hires (PRESS MOUSE…, WAITING…, LINK ERROR) → engine/messages_hires.png\n");
    }

    static BufferedImage paletteSheet(int[] pal) {
        BufferedImage img = Images.indexed(128, 128, pal, false);
        for (int i = 0; i < 256; i++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    img.getRaster().setSample((i % 16) * 8 + x, (i / 16) * 8 + y, 0, i);
                }
            }
        }
        return img;
    }
}
