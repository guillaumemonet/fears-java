package fears.menu;

import fears.engine.LevelResult;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Écrans entre deux niveaux ; images dans {@code build/interlude-test/}. */
class InterludeTest {

    @Test
    void statsScreenFadesFromBlueAndWaitsForTheClick() throws IOException {
        Path resources = TestResources.locate();
        assumeTrue(resources != null, "ressources extraites absentes");
        LevelResult result = new LevelResult(1, 50 * 754, 12, 9, 4, 1, 9, 9);
        StatsScreen screen = new StatsScreen(MenuAssets.load(resources), result, 50 * 754, 64);
        screen.tick();
        int background = screen.frame()[250 * StatsScreen.WIDTH + 600] & 0xFFFFFF;
        assertEquals(0, background >> 8, "l'écran s'ouvre en bleu pur");
        assertTrue((background & 0xFF) > 0xE0, "l'écran s'ouvre en bleu");
        for (int i = 0; i < 80; i++) {
            screen.tick();
        }
        assertEquals(0, screen.frame()[250 * StatsScreen.WIDTH + 600] & 0xFFFFFF, "fond noir après le fondu");
        write(screen, "stats");
        screen.setFire(true);
        screen.tick();
        assertFalse(screen.saveRequested());
        for (int i = 0; i < 70; i++) {
            screen.tick();
        }
        assertTrue(screen.finished());
        assertEquals("00:12:34", StatsScreen.clock(50 * 754));
    }

    @Test
    void castleScreenRuns() throws IOException {
        Path resources = TestResources.locate();
        assumeTrue(resources != null, "ressources extraites absentes");
        CastleScreen castle = new CastleScreen(resources);
        for (int i = 0; i < 100; i++) {
            castle.tick();
        }
        write(castle, "chateau");
        while (!castle.finished()) {
            castle.tick();
        }
    }

    @Test
    void gameOverWaitsForTheButtonThenFades() throws IOException {
        Path resources = TestResources.locate();
        assumeTrue(resources != null, "ressources extraites absentes");
        GameOverScreen screen = new GameOverScreen(resources);
        for (int i = 0; i < 100; i++) {
            screen.tick();
        }
        assertFalse(screen.finished(), "attend le bouton");
        write(screen, "game-over");
        screen.setFire(true);
        for (int i = 0; i < GameOverScreen.STEPS + 1; i++) {
            screen.tick();
        }
        assertTrue(screen.finished());
    }

    private static void write(Scene scene, String name) throws IOException {
        Path dir = Files.createDirectories(Path.of("build", "interlude-test"));
        int repeat = Math.max(1, Math.round(scene.pixelAspect()));
        int widen = scene.pixelAspect() < 1 ? 2 : 1;
        BufferedImage image = new BufferedImage(scene.width() * widen, scene.height() * repeat,
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < scene.height(); y++) {
            for (int x = 0; x < scene.width(); x++) {
                for (int r = 0; r < repeat; r++) {
                    for (int w = 0; w < widen; w++) {
                        image.setRGB(x * widen + w, y * repeat + r, scene.frame()[y * scene.width() + x]);
                    }
                }
            }
        }
        ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
    }
}
