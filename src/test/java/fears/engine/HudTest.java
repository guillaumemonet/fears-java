package fears.engine;

import fears.menu.LinkedImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** HUD et arme en main ; images dans {@code build/hud-test/}. */
class HudTest {

    @Test
    void pistolSitsOnTheBottomAndItsMuzzleUsesTheHudBank() throws IOException {
        Path resources = Path.of(System.getProperty("user.home"), ".fears", "ressources");
        assumeTrue(Files.isDirectory(resources.resolve("engine")), "ressources extraites absentes");
        int[][] frames = new int[17][];
        int[][] indices = new int[17][];
        for (int i = 0; i < 17; i++) {
            BufferedImage image = ImageIO.read(resources.resolve("engine/armes").resolve(String.format("arme_%02d.png", i)).toFile());
            frames[i] = image.getRGB(0, 0, 128, 87, null, 0, 128);
            indices[i] = image.getRaster().getPixels(0, 0, 128, 87, (int[]) null);
        }
        Hud hud = new Hud(LinkedImage.load(resources.resolve("raw/d1_0282da.bin")), frames, indices);
        Stats stats = new Stats();
        Weapons weapons = new Weapons(stats);
        hud.update(stats, weapons);
        // La dernière ligne de l'arme est la dernière de l'écran.
        assertEquals(Weapons.RAISED + 87 - 0x2A, Hud.HEIGHT);
        write(hud, "pistolet-repos");
        weapons.tick(true);
        hud.update(stats, weapons);
        write(hud, "pistolet-tir");
    }

    private static void write(Hud hud, String name) throws IOException {
        Path dir = Files.createDirectories(Path.of("build", "hud-test"));
        BufferedImage image = new BufferedImage(Hud.WIDTH, Hud.HEIGHT * 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < Hud.HEIGHT; y++) {
            for (int x = 0; x < Hud.WIDTH; x++) {
                int c = hud.image()[y * Hud.WIDTH + x];
                int rgb = (c >>> 24) == 0 ? 0x203040 : c & 0xFFFFFF;
                image.setRGB(x, 2 * y, rgb);
                image.setRGB(x, 2 * y + 1, rgb);
            }
        }
        ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
    }
}
