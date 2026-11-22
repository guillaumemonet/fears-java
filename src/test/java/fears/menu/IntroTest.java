package fears.menu;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Intro de démarrage, présentation et enchaînement ; images dans {@code build/intro-test/}. */
class IntroTest {

    private static MenuAssets assets() throws IOException {
        Path resources = TestResources.locate();
        assumeTrue(resources != null, "ressources extraites absentes");
        return MenuAssets.load(resources);
    }

    @Test
    void bootIntroShowsThreeScenesThenEnds() throws IOException {
        BootIntro boot = new BootIntro(assets());
        // Tableau 1 tenu (durée 200), tableau 2 (100), tableau 3 (201) : 264 + 164 + 265 images.
        int frames = 0;
        int[] shots = {150, 264 + 90, 264 + 164 + 150};
        int shot = 0;
        while (!boot.finished() && frames < 2000) {
            boot.tick();
            frames++;
            if (shot < shots.length && frames == shots[shot]) {
                write(boot, "boot-" + (shot + 1));
                shot++;
            }
        }
        assertTrue(boot.finished());
        assertEquals(264 + 164 + 265, frames);
        assertEquals(3, shot);
    }

    @Test
    void presentationScrollsUntilClicked() throws IOException {
        Presentation presentation = new Presentation(assets());
        for (int i = 0; i < 600; i++) {
            presentation.tick();
        }
        write(presentation, "presentation-600");
        for (int i = 0; i < 900; i++) {
            presentation.tick();
        }
        write(presentation, "presentation-1500");
        assertFalse(presentation.finished());
        presentation.setFire(true);
        presentation.tick();
        presentation.setFire(false);
        int frames = 1;
        while (!presentation.finished()) {
            presentation.tick();
            frames++;
        }
        // La VBL du clic, puis 71 VBL de compte ({@code L011250} jusqu'à plus de $46).
        assertEquals(Presentation.END_AFTER_CLICK + 2, frames);
    }

    @Test
    void frontEndGoesBootPresentationMenuAndBack() throws IOException {
        FrontEnd frontEnd = new FrontEnd(assets(), true);
        assertFalse(frontEnd.musicPlaying());
        for (int i = 0; i < 264 + 164 + 265; i++) {
            frontEnd.tick();
        }
        assertTrue(frontEnd.scene() instanceof Presentation);
        assertTrue(frontEnd.musicPlaying());
        frontEnd.scene().setFire(true);
        frontEnd.tick();
        frontEnd.scene().setFire(false);
        for (int i = 0; i < 100; i++) {
            frontEnd.tick();
        }
        assertSame(frontEnd.menu(), frontEnd.scene());
    }

    private static void write(Scene scene, String name) throws IOException {
        Path dir = Files.createDirectories(Path.of("build", "intro-test"));
        int repeat = Math.round(scene.pixelAspect());
        BufferedImage image = new BufferedImage(scene.width(), scene.height() * repeat,
                BufferedImage.TYPE_INT_RGB);
        int[] frame = scene.frame();
        for (int y = 0; y < scene.height(); y++) {
            for (int x = 0; x < scene.width(); x++) {
                for (int r = 0; r < repeat; r++) {
                    image.setRGB(x, y * repeat + r, frame[y * scene.width() + x]);
                }
            }
        }
        ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
    }
}
