package fears.menu;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Séquence de fin ; images dans {@code build/fin-test/}. */
class EndSequenceTest {

    @Test
    void headThenCredits() throws IOException {
        Path resources = TestResources.locate();
        assumeTrue(resources != null && Files.isDirectory(resources.resolve("fin")), "ressources absentes");
        EndSequence end = new EndSequence(MenuAssets.load(resources), resources);
        int[] shots = {50, 250, 330, 390 + 400, 390 + 1500};
        int clock = 0;
        for (int shot : shots) {
            while (clock < shot) {
                end.tick();
                clock++;
            }
            write(end, "fin-" + shot);
        }
        assertFalse(end.finished());
        end.setFire(true);
        end.tick();
        assertTrue(end.finished());
    }

    private static void write(Scene scene, String name) throws IOException {
        Path dir = Files.createDirectories(Path.of("build", "fin-test"));
        BufferedImage image = new BufferedImage(scene.width(), scene.height() * 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < scene.height(); y++) {
            for (int x = 0; x < scene.width(); x++) {
                image.setRGB(x, 2 * y, scene.frame()[y * scene.width() + x]);
                image.setRGB(x, 2 * y + 1, scene.frame()[y * scene.width() + x]);
            }
        }
        ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
    }
}
