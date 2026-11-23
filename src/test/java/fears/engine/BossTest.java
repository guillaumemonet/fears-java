package fears.engine;

import fears.extract.Level;
import fears.extract.LevelCatalog;
import fears.menu.LinkedImage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Le boss du niveau 30 (MOTEUR.md « acteurs » §2.10). */
class BossTest {

    @Test
    void bossFiresVolleysOpensDoorsAndDies() throws IOException {
        Path resources = Path.of(System.getProperty("user.home"), ".fears", "ressources");
        assumeTrue(Files.isDirectory(resources.resolve("levels")), "ressources extraites absentes");
        Path file;
        try (Stream<Path> files = Files.list(resources.resolve("levels"))) {
            file = files.filter(p -> p.getFileName().toString().startsWith("niveau30_")
                    && p.getFileName().toString().endsWith(".lvl")).findFirst().orElseThrow();
        }
        Level level = new Level(Files.readAllBytes(file));
        LinkedImage engine = LinkedImage.load(resources.resolve("raw/d1_0282da.bin"));
        EngineData data = new EngineData(engine);
        World world = new World(level);
        Player player = new Player(world, data.trig(), level.p1x, level.p1y, level.p1angle);
        Doors doors = new Doors(world, player, false);
        Aliens aliens = new Aliens(world, player, data, LevelCatalog.forLevel(30).aliens(), 1);
        Boss boss = new Boss(engine, world, doors, aliens);
        aliens.setBoss(boss);
        assertEquals(11, world.wallDown(World.index(14, 25)), "le visage du boss");

        // 15 crans de 40 VBL : 7 tirs entre les crans 8 et 14.
        int fired = 0;
        for (int i = 0; i < 15 * 40; i++) {
            int before = aliens.alienShots();
            boss.tick();
            if (aliens.alienShots() > before) {
                fired++;
            }
            aliens.clearAlienShots();
        }
        assertEquals(7, fired);

        boss.hurt(101, null);
        assertEquals(6, doors.activeDoor(), "sous 400 points, la porte 6 part");
        boss.hurt(400, null);
        boss.tick();
        assertTrue(boss.dead());
        assertEquals(12, world.wallDown(World.index(14, 25)));
        assertEquals(10, doors.activeDoor(), "la porte 10 s'ouvre à sa mort");
        assertFalse(boss.health() >= 0);
    }
}
