package fears.menu;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * La fin du jeu, exécutable {@code d2_0a9a00}, sur le module « fin ».
 *
 * <p><b>Phase 1</b> ({@code L010092}, 390 trames) : la tête du boss en 3 × 3 tuiles de 64×64
 * (table {@code L01019c} : image et position). L'écran part des tuiles 0 à 5, 6, 9 et 11 ;
 * le script {@code L010226} pose ensuite d'autres tuiles à des trames précises — les yeux et la
 * bouche s'animent, puis la tête se défait.
 *
 * <p><b>Phase 2</b> ({@code L010530}) : un texte qui monte, comme la présentation du menu (même
 * police, même chasse, 23 lignes par chaîne, une ligne de défilement toutes les deux trames),
 * en boucle sans fin : le jeu s'arrête là. Une chaîne {@code $01 n} fait paraître la photo de
 * l'auteur n ({@code L010e86}, 376 trames). [S] Les photos défilent ici avec le texte, centrées,
 * là où le code apparaît ; le portage permet aussi de revenir au menu par un clic.
 */
public final class EndSequence implements Scene {

    public static final int WIDTH = 640;
    public static final int HEIGHT = 256;
    static final int PHASE_ONE = 0x186;
    static final int SCRIPT = 0x1022A;
    static final int TILES = 0x1019C;
    static final int TILE_IMAGES = 0x12D2A;
    static final int TEXT = 0x1082E;
    static final int ROWS_PER_STRING = 0x17;
    static final int GLYPH_ROWS = 0x15;
    static final int BIRTH_LINE = 256;
    static final int STRIP_LINES = 256 + 130;
    /** Gris du texte, comme la présentation du menu (couleurs 17 à 23). [S] */
    static final int[] GREYS = {0, 0xF0F0F0, 0xD0D0D0, 0xB0B0B0, 0x909090, 0x707070, 0x505050, 0x303030};

    private final LinkedImage program;
    private final MenuText font;
    private final int[] tiles;
    private final int tilesWidth;
    private final int[][] photos = new int[4][];
    private final int[] photoSize = new int[2];
    private final int[] frame = new int[WIDTH * HEIGHT];
    /** Phase 1 : écran 320×256 basse résolution, une valeur 0xRRGGBB par pixel. */
    private final int[] head = new int[320 * 256];
    private final int[] strip = new int[WIDTH * STRIP_LINES];

    private int clock;
    private int script = SCRIPT;
    private boolean longFrame;
    private int text = TEXT;
    private int row;
    private boolean fire;
    private boolean released;
    private boolean finished;

    public EndSequence(MenuAssets assets, Path resources) throws IOException {
        this.program = LinkedImage.load(resources.resolve("raw").resolve("d2_0a9a00.bin"));
        this.font = new MenuText(assets);
        BufferedImage sheet = read(resources.resolve("fin").resolve("boss_tuiles_64x64.png"));
        tilesWidth = sheet.getWidth();
        tiles = sheet.getRGB(0, 0, sheet.getWidth(), sheet.getHeight(), null, 0, sheet.getWidth());
        for (int i = 0; i < 4; i++) {
            BufferedImage photo = read(resources.resolve("fin").resolve(
                    String.format("photo_auteur%d_128x118_affichee.png", i + 1)));
            photoSize[0] = photo.getWidth();
            photoSize[1] = photo.getHeight();
            photos[i] = photo.getRGB(0, 0, photo.getWidth(), photo.getHeight(), null, 0, photo.getWidth());
        }
        // L010136 : l'écran de départ.
        for (int t = 5; t >= 0; t--) {
            placeTile(t);
        }
        placeTile(6);
        placeTile(9);
        placeTile(11);
    }

    private static BufferedImage read(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("image illisible : " + file);
        }
        return image;
    }

    /** Fini quand le joueur clique pendant le générique (l'original ne rend jamais la main). */
    public boolean finished() {
        return finished;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    @Override
    public float pixelAspect() {
        return 2f;
    }

    @Override
    public int[] frame() {
        return frame;
    }

    @Override
    public void setFire(boolean pressed) {
        fire = pressed;
    }

    /** {@code L010172} : la tuile n à sa place (position = offset dans l'écran à 200 octets par ligne). */
    private void placeTile(int n) {
        int offset = program.u32(TILES + 8 * n + 4);
        int x0 = (offset % 200) * 8;
        int y0 = offset / 200;
        int image = (program.u32(TILES + 8 * n) - TILE_IMAGES) / 0xA00;
        int sx = (image % (tilesWidth / 64)) * 64;
        int sy = (image / (tilesWidth / 64)) * 64;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int c = tiles[(sy + y) * tilesWidth + sx + x];
                if (y0 + y < 256 && x0 + x < 320) {
                    head[(y0 + y) * 320 + x0 + x] = c & 0xFFFFFF;
                }
            }
        }
    }

    @Override
    public void tick() {
        clock++;
        if (clock <= PHASE_ONE) {
            // L01010c : les événements du script, à la trame dite.
            if (program.u16(script) == clock) {
                int count = program.u16(script + 2) + 1;
                for (int i = 0; i < count; i++) {
                    placeTile(program.u16(script + 4 + 2 * i));
                }
                script += 4 + 2 * count;
            }
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    frame[y * WIDTH + x] = head[y * 320 + x / 2];
                }
            }
            return;
        }
        if (!fire) {
            released = true;
        } else if (released) {
            finished = true;
        }
        longFrame = !longFrame;
        if (longFrame) {
            scroll();
        }
        System.arraycopy(strip, 0, frame, 0, frame.length);
    }

    private void scroll() {
        System.arraycopy(strip, WIDTH, strip, 0, WIDTH * (STRIP_LINES - 1));
        Arrays.fill(strip, WIDTH * (STRIP_LINES - 1), strip.length, 0);
        String line = program.string(text);
        if (row == 0 && line.length() == 2 && line.charAt(0) == 1) {
            showPhoto(line.charAt(1) - 1);
        } else if (row < GLYPH_ROWS && (line.isEmpty() || line.charAt(0) != 1)) {
            drawRow(line, row);
        }
        row++;
        // [S] La ligne d'une photo occupe la hauteur de la photo, pour que le texte suivant ne la
        // recouvre pas (le placement exact de L010e86 reste à relever).
        boolean photo = line.length() == 2 && line.charAt(0) == 1;
        if (row >= (photo ? photoSize[1] + 6 : ROWS_PER_STRING)) {
            row = 0;
            text += line.length() + 1;
            if (program.u8(text) == LinkedImage.END_OF_PAGE) {
                text = TEXT;
            }
        }
    }

    private void showPhoto(int index) {
        if (index < 0 || index >= photos.length) {
            return;
        }
        int w = photoSize[0];
        int h = Math.min(photoSize[1], STRIP_LINES - BIRTH_LINE);
        int left = (WIDTH - w) / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                strip[(BIRTH_LINE + y) * WIDTH + left + x] = photos[index][y * w + x] & 0xFFFFFF;
            }
        }
    }

    private void drawRow(String line, int glyphRow) {
        int x = ((WIDTH - font.width(line)) & 0xFFFF) >> 1;
        int at = BIRTH_LINE * WIDTH;
        for (int i = 0; i < line.length(); i++) {
            int c = line.charAt(i);
            if (c != ' ' && c >= 0x20) {
                for (int px = 0; px < 16; px++) {
                    int sx = x + px;
                    int value = font.glyphPixel(c, glyphRow, px);
                    if (value != 0 && sx >= 0 && sx < WIDTH) {
                        strip[at + sx] = GREYS[value];
                    }
                }
            }
            if (c >= 0x20) {
                x += font.charWidth(c);
            }
        }
    }
}
