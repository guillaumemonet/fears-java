package fears.extract;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;

/**
 * Graphismes et musiques intégrés aux exécutables autres que le moteur (menu, game over,
 * intro de démarrage, séquence de fin). Adresses = image liée à la base 0x10000.
 *
 * Format de palette le plus courant dans ces programmes : 3 mots par couleur {@code 00RR 00GG 00BB}
 * (8 bits par composante), utilisés comme pas de fondu sur 64 étapes puis écrits en AGA 24 bits (LOCT).
 * Les musiques sont des modules ProTracker « M.K. » codés en delta octet par octet.
 */
final class ExeAssets {

    static final int BASE = 0x10000;

    /** Palette 3 mots/couleur 00RR 00GG 00BB. */
    static int[] rgb8Palette(byte[] img, int addr, int count) {
        int[] p = new int[256];
        int o = addr - BASE;
        for (int i = 0; i < count; i++) {
            int r = img[o + 6 * i + 1] & 0xff, g = img[o + 6 * i + 3] & 0xff, b = img[o + 6 * i + 5] & 0xff;
            p[i] = r << 16 | g << 8 | b;
        }
        return p;
    }

    /** Bitplanes entrelacés : pour chaque ligne, {@code planes} lignes de {@code w/8} octets. */
    static BufferedImage interleaved(byte[] img, int addr, int w, int h, int planes, int[] pal) {
        BufferedImage im = Images.indexed(w, h, pal, false);
        int bpr = w / 8, o = addr - BASE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = 0;
                for (int p = 0; p < planes; p++) {
                    int k = o + (y * planes + p) * bpr + (x >> 3);
                    if (k < img.length) {
                        v |= ((img[k] >> (7 - (x & 7))) & 1) << p;
                    }
                }
                im.getRaster().setSample(x, y, 0, v);
            }
        }
        return im;
    }

    /** Bitplanes séparés : plan p à {@code addr + p*planeStride}. */
    static BufferedImage separate(byte[] img, int addr, int w, int h, int planes, int planeStride, int[] pal) {
        BufferedImage im = Images.indexed(w, h, pal, false);
        int bpr = w / 8, o = addr - BASE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = 0;
                for (int p = 0; p < planes; p++) {
                    int k = o + p * planeStride + y * bpr + (x >> 3);
                    v |= ((img[k] >> (7 - (x & 7))) & 1) << p;
                }
                im.getRaster().setSample(x, y, 0, v);
            }
        }
        return im;
    }

    static byte[] deltaDecode(byte[] d, int from, int to) {
        byte[] r = new byte[to - from];
        int acc = 0;
        for (int i = 0; i < r.length; i++) {
            acc = i == 0 ? d[from] : acc + d[from + i];
            r[i] = (byte) acc;
        }
        return r;
    }

    /** Écrit un module delta-codé ; vérifie la signature M.K. à +1080. */
    static String writeModule(byte[] src, int from, int to, Path out) throws IOException {
        byte[] mod = deltaDecode(src, from, to);
        String sig = mod.length > 1084 ? new String(mod, 1080, 4, java.nio.charset.StandardCharsets.ISO_8859_1) : "";
        String title = new String(mod, 0, 20, java.nio.charset.StandardCharsets.ISO_8859_1).replace("\0", "").trim();
        if (!sig.equals("M.K.") && !sig.equals("SNT.")) {
            System.err.println("AVERTISSEMENT : module " + out.getFileName() + " sans signature M.K. (trouvé '" + sig + "')");
        }
        Files.write(out, mod);
        return title;
    }

    static int[] gray(int n) {
        return Images.grayPalette(n);
    }

    private static int[] mono(int c1) {
        int[] p = new int[256];
        p[1] = c1;
        return p;
    }

    /** Exporte les ressources connues de l'exécutable {@code name} ; rien si inconnu. */
    static void export(String name, byte[] img, Path out, StringBuilder index) throws IOException {
        switch (name) {
            case "d1_000400" -> menu(img, out.resolve("menu"), index);
            case "d1_0ae87e" -> gameOver(img, out.resolve("gameover"), index);
            case "d1_0d140c" -> boot(img, out.resolve("intro"), index);
            case "d2_0a9a00" -> ending(img, out.resolve("fin"), index);
            default -> {
            }
        }
    }

    private static void menu(byte[] img, Path dir, StringBuilder index) throws IOException {
        Files.createDirectories(dir);
        int[] logoPal = rgb8Palette(img, 0x112e2, 24);
        Images.write(interleaved(img, 0x1d6c0, 320, 191, 4, logoPal), dir.resolve("logo_fears_320x191.png"));
        Images.write(interleaved(img, 0x1d6c0, 320, 191, 4, gray(16)), dir.resolve("logo_fears_320x191_gris.png"));
        // Palette du menu principal : liste copper 0x2a450 (couleur 1 = $4E4040 en 24 bits, rampes grise et rouge)
        int[] menuPal = Copper.palette(img, MENU_COPPER, BASE, 400);
        Images.write(interleaved(img, 0x24e20, 208, 197, 1, mono(menuPal[1])), dir.resolve("logo_bomb_208x197.png"));
        // Police : 3 plans dessinés dans les plans 1–3 de l'écran → valeur v affichée en couleur 2v
        int[] fontPal = new int[256];
        for (int v = 1; v < 8; v++) {
            fontPal[v] = menuPal[2 * v];
        }
        int[] fontHi = new int[256];
        for (int v = 1; v < 8; v++) {
            fontHi[v] = menuPal[16 + 2 * v]; // surbrillance : plan 5 ajouté → rampe rouge
        }
        Images.write(withTransparent0(separate(img, 0x261e2, 960, 21, 3, 0x9d8, fontPal)), dir.resolve("police_16x21.png"));
        Images.write(withTransparent0(separate(img, 0x261e2, 960, 21, 3, 0x9d8, fontHi)), dir.resolve("police_16x21_surbrillance.png"));
        Images.write(EngineAssets.paletteSheet(menuPal), dir.resolve("palette_menu.png"));
        // Pointeur souris : sprite 16×17, couleurs 97–99 (BPLCON4 $66) = rouge f00/800/400
        int[] ptrPal = new int[256];
        ptrPal[1] = 0xff0000;
        ptrPal[2] = 0x880000;
        ptrPal[3] = 0x440000;
        BufferedImage ptr = Images.indexed(16, 17, ptrPal, true);
        int po = 0x29264 - BASE + 4;
        for (int y = 0; y < 17; y++) {
            int a = Be.w(img, po + y * 4), b = Be.w(img, po + y * 4 + 2);
            for (int x = 0; x < 16; x++) {
                ptr.getRaster().setSample(x, y, 0, ((a >> (15 - x)) & 1) | ((b >> (15 - x)) & 1) << 1);
            }
        }
        Images.write(ptr, dir.resolve("pointeur_16x17.png"));
        Images.write(interleaved(img, 0x27faa, 480, 7, 1, mono(0xffffff)), dir.resolve("police_8x7.png"));
        String[] msgs = {"load_error", "decrunching_error", "insert_disk1", "insert_disk2", "insert_save_disk", "insert_data_disk"};
        for (int i = 0; i < msgs.length; i++) {
            Images.write(interleaved(img, 0x2814e + i * 280, 320, 7, 1, mono(0xffddff)), dir.resolve("message_" + msgs[i] + ".png"));
        }
        index.append("- menu (d1_000400) : logo FEARS, logo bomb, polices 16×21 et 8×7, messages disque → menu/\n");
    }

    private static void gameOver(byte[] img, Path dir, StringBuilder index) throws IOException {
        Files.createDirectories(dir);
        int[] pal = rgb8Palette(img, 0x122a8, 128);
        BufferedImage im = Images.indexed(320, 256, pal, false);
        Images.blit(im, img, 0x128b8 - BASE, 320, 256, 0, 0);
        Images.write(im, dir.resolve("game_over_320x256.png"));
        String t = writeModule(img, 0x268b8 - BASE, 0x32518 - BASE, dir.resolve("dead_end.mod"));
        index.append("- game over (d1_0ae87e) : image 320×256 (128 couleurs) + module « " + t + " » → gameover/\n");
    }

    private static void boot(byte[] img, Path dir, StringBuilder index) throws IOException {
        Files.createDirectories(dir);
        int[] p4 = rgb8Palette(img, 0x10388, 4);
        Images.write(interleaved(img, 0x106a8, 400, 100, 2, p4), dir.resolve("manyk_presents_400x100.png"));
        Images.write(interleaved(img, 0x12db8, 176, 131, 2, p4), dir.resolve("a_game_developed_by_176x131.png"));
        Images.write(interleaved(img, 0x1443c, 416, 512, 5, rgb8Palette(img, 0x10478, 32)), dir.resolve("bomb_software_416x512.png"));
        index.append("- intro de démarrage (d1_0d140c) : MANYK presents, « A game developed by », mascotte BOMB software → intro/\n");
    }

    private static void ending(byte[] img, Path dir, StringBuilder index) throws IOException {
        Files.createDirectories(dir);
        int[] pal = rgb8Palette(img, 0x103a6, 32);
        // 17 tuiles 64×64, 5 plans entrelacés, 2560 octets chacune
        BufferedImage sheet = Images.indexed(64 * 6, 64 * 3, pal, false);
        for (int t = 0; t < 17; t++) {
            BufferedImage tile = interleaved(img, 0x12d2a + t * 2560, 64, 64, 5, pal);
            sheet.getRaster().setRect((t % 6) * 64, (t / 6) * 64, tile.getRaster());
        }
        Images.write(sheet, dir.resolve("boss_tuiles_64x64.png"));
        // Photos : palette 24 bits dans la liste copper 0x4fe56 ; la photo n (1..4) s'affiche avec
        // BPLCON4 = n<<13, donc ses 16 couleurs sont celles de la banque n (couleurs 32n..32n+15).
        int[] cop = Copper.palette(img, END_COPPER, BASE, 400);
        int[] photos = {0x1f750, 0x20610, 0x214d0, 0x22390};
        for (int i = 0; i < photos.length; i++) {
            int[] ppal = new int[256];
            System.arraycopy(cop, (i + 1) * 32, ppal, 0, 16);
            BufferedImage src = interleaved(img, photos[i], 64, 118, 4, ppal);
            // le programme double chaque pixel en largeur (0x10e38) : on exporte aussi la version affichée
            BufferedImage wide = Images.indexed(128, 118, ppal, false);
            for (int y = 0; y < 118; y++) {
                for (int x = 0; x < 128; x++) {
                    wide.getRaster().setSample(x, y, 0, src.getRaster().getSample(x / 2, y, 0));
                }
            }
            Images.write(src, dir.resolve(String.format("photo_auteur%d_64x118.png", i + 1)));
            Images.write(wide, dir.resolve(String.format("photo_auteur%d_128x118_affichee.png", i + 1)));
        }
        String t = writeModule(img, 0x23250 - BASE, 0x4fc9e - BASE, dir.resolve("fin.mod"));
        index.append("- fin (d2_0a9a00) : 17 tuiles de la tête du boss, 4 photos des auteurs (gris), module « " + t + " » → fin/\n");
    }

    // ------------------------------------------------------------------ fichiers disque dont la palette est dans le menu

    static final int CASTLE_PALETTE = 0x1660e, LINK_PALETTE = 0x1780e;
    static final int MENU_COPPER = 0x2a450, END_COPPER = 0x4fe56;
    /** Table des animations du château dans le menu : 5 zones × 0x20 octets (code 0x13686). */
    static final int CASTLE_ANIMS = 0x13714;

    private static BufferedImage withTransparent0(BufferedImage src) {
        var cm = (java.awt.image.IndexColorModel) src.getColorModel();
        int[] pal = new int[256];
        for (int i = 0; i < 256; i++) {
            pal[i] = cm.getRGB(i) & 0xffffff;
        }
        BufferedImage dst = Images.indexed(src.getWidth(), src.getHeight(), pal, true);
        dst.setData(src.getRaster());
        return dst;
    }

    /**
     * Animations du château (chauves-souris, torches, fenêtres) : pour chaque zone, 3 images de
     * 5 plans recopiées ligne par ligne dans l'image 8 plans entrelacés du château (320 octets par ligne).
     * Entrée : compteur.l, 3 pointeurs d'image, offset destination, lignes−1, mots−1, modulo.
     */
    static void castleAnimations(byte[] menu, byte[] castleChunky, int[] pal, Path dir, StringBuilder index) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("zone;image;x;y;largeur;hauteur;fichier\n");
        BufferedImage full = Images.indexed(320, 512, pal, false);
        Images.blit(full, castleChunky, 0, 320, 512, 0, 0);
        for (int z = 0; z < 5; z++) {
            int e = CASTLE_ANIMS - BASE + z * 0x20;
            int dest = Be.l(menu, e + 0x10);
            int lines = Be.l(menu, e + 0x14) + 1;
            int words = Be.l(menu, e + 0x18) + 1;
            int x0 = (dest % 320) * 8, y0 = dest / 320;
            int w = words * 16;
            for (int f = 0; f < 3; f++) {
                int src = Be.l(menu, e + 4 + 4 * f) - BASE;
                BufferedImage im = Images.indexed(w, lines, pal, false);
                for (int y = 0; y < lines; y++) {
                    for (int x = 0; x < w; x++) {
                        int v = 0;
                        for (int p = 0; p < 5; p++) {
                            int k = src + (y * 5 + p) * words * 2 + (x >> 3);
                            v |= ((menu[k] >> (7 - (x & 7))) & 1) << p;
                        }
                        // les plans 5–7 du château restent inchangés (nuls dans la zone)
                        int under = full.getRaster().getSample(Math.min(319, x0 + x), y0 + y, 0) & 0xe0;
                        im.getRaster().setSample(x, y, 0, v | under);
                    }
                }
                String name = String.format("zone%d_image%d.png", z, f);
                Images.write(im, dir.resolve(name));
                sb.append(String.format("%d;%d;%d;%d;%d;%d;%s%n", z, f, x0, y0, w, lines, name));
            }
        }
        Files.writeString(dir.resolve("positions.csv"), sb);
        index.append("- château : 5 zones animées × 3 images (positions dans positions.csv) → pictures/chateau_animations/\n");
    }
}
