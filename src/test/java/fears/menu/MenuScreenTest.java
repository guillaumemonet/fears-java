package fears.menu;

import fears.audio.ModModule;
import fears.audio.ModPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Le menu contre les ressources extraites. Ignoré si elles sont absentes ; pointer leur
 * répertoire avec {@code gradle test -Dfears.ressources=<chemin>}. Les images rendues vont
 * dans {@code build/menu-test/}, pour les regarder.
 */
class MenuScreenTest {

    private static Path resources;

    @BeforeAll
    static void locate() {
        resources = TestResources.locate();
    }

    private static MenuScreen screen() throws IOException {
        assumeTrue(resources != null, "ressources extraites absentes");
        return new MenuScreen(MenuAssets.load(resources));
    }

    @Test
    void pagesAreReadFromTheProgram() throws IOException {
        MenuScreen menu = screen();
        LinkedImage program = MenuAssets.load(resources).program;
        int text = program.u32(MenuScreen.PAGES + 4);
        assertEquals("", program.string(text));
        assertEquals("START GAME", program.string(text + 3));
        // Table de sinus : quart de tour au maximum.
        assertEquals(32767, program.s16(BombBackground.SINE + 2 * 90));
        assertEquals(0, menu.page());
    }

    @Test
    void fadeThenMenuThenOptions() throws IOException {
        MenuScreen menu = screen();
        for (int i = 0; i < 30; i++) {
            menu.tick();
        }
        write(menu, "1-fondu");
        for (int i = 0; i < MenuScreen.FADE_FRAMES; i++) {
            menu.tick();
        }
        // Le pointeur sur START GAME (ligne 3).
        menu.pointTo(300, 3 * MenuText.LINE_HEIGHT + 8);
        menu.tick();
        assertEquals(3, menu.selectedLine());
        write(menu, "2-menu");

        // OPTIONS (ligne 5).
        click(menu, 5);
        assertEquals(MenuScreen.PAGE_OPTIONS, menu.page());

        // WALLS, puis DIFFICULTY (NORMAL au départ) : OFF, HIGH.
        click(menu, 1);
        click(menu, 6);
        menu.pointTo(320, 6 * MenuText.LINE_HEIGHT + 4);
        menu.tick();
        write(menu, "3-options");
        MenuOptions options = menu.options();
        assertTrue(options.wallsOff());
        assertEquals(2, options.difficulty());

        click(menu, 10);
        assertEquals(MenuScreen.PAGE_MAIN, menu.page());
    }

    @Test
    void startGameFadesOutThenReports() throws IOException {
        MenuScreen menu = screen();
        for (int i = 0; i < MenuScreen.FADE_FRAMES; i++) {
            menu.tick();
        }
        menu.pointTo(300, 3 * MenuText.LINE_HEIGHT + 2);
        menu.setFire(true);
        menu.tick();
        menu.setFire(false);
        MenuAction action = null;
        for (int i = 0; i < 200 && action == null; i++) {
            menu.tick();
            action = menu.takeAction();
        }
        assertEquals(MenuAction.START_GAME, action);
    }

    @Test
    void menuMusicRenders() throws IOException {
        assumeTrue(resources != null, "ressources extraites absentes");
        Path file;
        try (var list = Files.list(resources.resolve("music"))) {
            file = list.filter(p -> p.toString().endsWith("_menu.mod")).findFirst().orElseThrow();
        }
        ModModule module = ModModule.read(Files.readAllBytes(file));
        short[] pcm = ModPlayer.render(module, 44_100, 600);
        double seconds = pcm.length / 2 / 44_100.0;
        System.out.printf("Module « %s » : %.1f s%n", module.title(), seconds);
        assertTrue(seconds > 10, "morceau trop court : " + seconds);
        int peak = 0;
        for (short s : pcm) {
            peak = Math.max(peak, Math.abs(s));
        }
        assertTrue(peak > 1000, "morceau muet");
    }

    private static void click(MenuScreen menu, int line) {
        menu.pointTo(300, line * MenuText.LINE_HEIGHT + 8);
        menu.setFire(true);
        menu.tick();
        menu.setFire(false);
        for (int i = 0; i < MenuScreen.CLICK_DELAY; i++) {
            menu.tick();
        }
    }

    private static void write(MenuScreen menu, String name) throws IOException {
        Path dir = Files.createDirectories(Path.of("build", "menu-test"));
        BufferedImage image = new BufferedImage(MenuScreen.WIDTH, MenuScreen.HEIGHT * 2,
                BufferedImage.TYPE_INT_RGB);
        int[] frame = menu.frame();
        for (int y = 0; y < MenuScreen.HEIGHT; y++) {
            for (int x = 0; x < MenuScreen.WIDTH; x++) {
                // Lignes doublées : un pixel hires PAL est deux fois plus haut que large.
                image.setRGB(x, 2 * y, frame[y * MenuScreen.WIDTH + x]);
                image.setRGB(x, 2 * y + 1, frame[y * MenuScreen.WIDTH + x]);
            }
        }
        ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
    }
}
