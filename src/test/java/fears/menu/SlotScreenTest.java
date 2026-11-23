package fears.menu;

import fears.engine.SaveGame;
import fears.engine.Stats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Sauvegardes et choix d'emplacement ; image dans {@code build/slot-test/}. */
class SlotScreenTest {

    @TempDir
    Path saves;

    @Test
    void aSaveComesBackIdentical() throws IOException {
        SaveGame save = new SaveGame(4, 1234, 98765, 321, 5, true, false, true, false, 77, 12, 3, 2,
                true, false, false, 2);
        save.write(saves, 2);
        assertEquals(save, SaveGame.read(saves, 2));
        assertNull(SaveGame.read(saves, 3));
        Stats stats = save.toStats();
        assertEquals(99, stats.get(Stats.HEALTH), "la santé n'est pas sauvegardée");
        assertEquals(5, stats.get(Stats.LIVES));
        assertEquals(321, stats.score());
    }

    @Test
    void choosingASlotByItsLine() throws IOException {
        Path resources = TestResources.locate();
        assumeTrue(resources != null, "ressources extraites absentes");
        new SaveGame(6, 0, 50 * 600, 1987, 3, true, true, false, false, 99, 4, 0, 1, false, false, false, 0)
                .write(saves, 0);
        SlotScreen screen = new SlotScreen(MenuAssets.load(resources), saves, false);
        // Ligne de l'emplacement 2 (vide) : refusé au chargement.
        screen.pointTo(300, 0x40 + 8 * 1 + 2);
        click(screen);
        assertFalse(screen.finished());
        // Emplacement 1, occupé.
        screen.pointTo(300, 0x40 + 2);
        screen.tick();
        write(screen);
        click(screen);
        assertEquals(0, screen.chosen());
        assertEquals(6, screen.slot(0).level());

        SlotScreen cancel = new SlotScreen(MenuAssets.load(resources), saves, true);
        cancel.pointTo(300, 0x40 + 8 * SlotScreen.CANCEL + 2);
        click(cancel);
        assertTrue(cancel.finished());
        assertEquals(SlotScreen.CANCEL, cancel.chosen());
    }

    private static void click(SlotScreen screen) {
        screen.setFire(false);
        screen.tick();
        screen.setFire(true);
        screen.tick();
        screen.setFire(false);
    }

    private static void write(SlotScreen screen) throws IOException {
        Path dir = Files.createDirectories(Path.of("build", "slot-test"));
        BufferedImage image = new BufferedImage(SlotScreen.WIDTH, SlotScreen.HEIGHT * 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SlotScreen.HEIGHT; y++) {
            for (int x = 0; x < SlotScreen.WIDTH; x++) {
                image.setRGB(x, 2 * y, screen.frame()[y * SlotScreen.WIDTH + x]);
                image.setRGB(x, 2 * y + 1, screen.frame()[y * SlotScreen.WIDTH + x]);
            }
        }
        ImageIO.write(image, "png", dir.resolve("chargement.png").toFile());
    }
}
