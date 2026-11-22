package fears.menu;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Les ressources du menu, lues dans le répertoire produit par l'extracteur.
 *
 * <p>Les images sont des PNG indexés : on en relit les <em>index</em>, pas les couleurs, pour
 * recomposer l'écran plan par plan comme le faisait l'Amiga.
 */
public final class MenuAssets {

    /** Palette de la liste copper du menu (0x2a450), 256 couleurs en 0xRRGGBB. */
    final int[] palette;
    /** Police 16×21 : 960×21 valeurs de 0 à 7 (trois plans). */
    final int[][] font;
    /** Logo « bomb » sur un plan : 208×197, vrai = pixel allumé. */
    final boolean[][] bomb;
    /** Pointeur de souris : 16×17 index de sprite 0..3. */
    final int[][] pointer;
    /** Couleurs du pointeur (sprite, couleurs 96..99), 0xRRGGBB. */
    final int[] pointerColors;
    /** L'exécutable du menu, {@code d1_000400}. */
    final LinkedImage program;
    /** Logo FEARS de la présentation : 320×191 index 0..15 (quatre plans). */
    final int[][] logo;
    /** Les trois images de l'intro de démarrage, en index de palette. */
    final int[][][] bootImages;
    /** L'exécutable de l'intro de démarrage, {@code d1_0d140c} : tables de palette. */
    final LinkedImage bootProgram;

    private MenuAssets(int[] palette, int[][] font, boolean[][] bomb, int[][] pointer,
                       int[] pointerColors, LinkedImage program, int[][] logo,
                       int[][][] bootImages, LinkedImage bootProgram) {
        this.palette = palette;
        this.font = font;
        this.bomb = bomb;
        this.pointer = pointer;
        this.pointerColors = pointerColors;
        this.program = program;
        this.logo = logo;
        this.bootImages = bootImages;
        this.bootProgram = bootProgram;
    }

    /** Charge tout depuis le répertoire racine des ressources extraites. */
    public static MenuAssets load(Path resources) throws IOException {
        Path menu = resources.resolve("menu");
        int[] palette = readPaletteSheet(read(menu.resolve("palette_menu.png")));
        int[][] font = indices(read(menu.resolve("police_16x21.png")));
        int[][] bombIndex = indices(read(menu.resolve("logo_bomb_208x197.png")));
        boolean[][] bomb = new boolean[bombIndex.length][bombIndex[0].length];
        for (int y = 0; y < bomb.length; y++) {
            for (int x = 0; x < bomb[y].length; x++) {
                bomb[y][x] = bombIndex[y][x] != 0;
            }
        }
        BufferedImage pointerImage = read(menu.resolve("pointeur_16x17.png"));
        int[][] pointer = indices(pointerImage);
        int[] pointerColors = new int[4];
        IndexColorModel model = (IndexColorModel) pointerImage.getColorModel();
        for (int i = 0; i < 4; i++) {
            pointerColors[i] = model.getRGB(i) & 0xFFFFFF;
        }
        LinkedImage program = LinkedImage.load(resources.resolve("raw").resolve("d1_000400.bin"));
        int[][] logo = indices(read(menu.resolve("logo_fears_320x191.png")));
        Path intro = resources.resolve("intro");
        int[][][] boot = {
            indices(read(intro.resolve("manyk_presents_400x100.png"))),
            indices(read(intro.resolve("a_game_developed_by_176x131.png"))),
            indices(read(intro.resolve("bomb_software_416x512.png"))),
        };
        LinkedImage bootProgram = LinkedImage.load(resources.resolve("raw").resolve("d1_0d140c.bin"));
        return new MenuAssets(palette, font, bomb, pointer, pointerColors, program, logo, boot,
                bootProgram);
    }

    private static BufferedImage read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("ressource manquante : " + file);
        }
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("image illisible : " + file);
        }
        return image;
    }

    /** Les index de palette d'une image indexée, ligne par ligne. */
    private static int[][] indices(BufferedImage image) throws IOException {
        if (!(image.getColorModel() instanceof IndexColorModel)) {
            throw new IOException("image non indexée");
        }
        Raster raster = image.getRaster();
        int[][] out = new int[image.getHeight()][image.getWidth()];
        for (int y = 0; y < out.length; y++) {
            raster.getPixels(0, y, image.getWidth(), 1, out[y]);
        }
        return out;
    }

    /** La planche de palette de l'extracteur : 16 cases de 8×8 par ligne, 16 lignes. */
    private static int[] readPaletteSheet(BufferedImage sheet) {
        int[] palette = new int[256];
        for (int i = 0; i < 256; i++) {
            palette[i] = sheet.getRGB((i % 16) * 8 + 4, (i / 16) * 8 + 4) & 0xFFFFFF;
        }
        return palette;
    }
}
