package fears.engine;

import fears.extract.Level;
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

/** Le moteur contre les données d'origine (ignoré si les ressources extraites manquent). */
class EngineTest {

    private static Path resources() {
        String configured = System.getProperty("fears.ressources");
        Path path = configured != null ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".fears", "ressources");
        return Files.isDirectory(path.resolve("levels")) ? path : null;
    }

    private static Level level(int number) throws IOException {
        Path resources = resources();
        assumeTrue(resources != null, "ressources extraites absentes");
        String prefix = String.format("niveau%02d_", number);
        try (Stream<Path> files = Files.list(resources.resolve("levels"))) {
            Path file = files.filter(p -> p.getFileName().toString().startsWith(prefix)
                    && p.getFileName().toString().endsWith(".lvl")).findFirst().orElseThrow();
            return new Level(Files.readAllBytes(file));
        }
    }

    @Test
    void sineTableOfTheEngineMatchesTheFormula() throws IOException {
        Path resources = resources();
        assumeTrue(resources != null, "ressources extraites absentes");
        LinkedImage engine = LinkedImage.load(resources.resolve("raw").resolve("d1_0282da.bin"));
        int[] degrees = new int[361];
        for (int d = 0; d <= 360; d++) {
            degrees[d] = engine.s16(0x182D4 + 2 * d);
        }
        assertEquals(32767, degrees[90]);
        Trig read = new Trig(degrees);
        Trig computed = Trig.computed();
        for (int a = 0; a < Trig.FULL_TURN; a += 4) {
            assertEquals(computed.sin(a), read.sin(a), "angle " + a);
        }
    }

    @Test
    void walkingIntoAWallStopsAtTwentyThreeUnits() throws IOException {
        Level level = level(1);
        World world = new World(level);
        Player player = new Player(world, Trig.computed(), level.p1x, level.p1y, level.p1angle);
        int start = player.cell();
        for (int i = 0; i < 300; i++) {
            player.tick(1, 0, false, 0);
        }
        assertFalse(world.solid(player.cell()), "le joueur est entré dans un mur");
        // Départ vers l'est (angle 0xCA8) : il bute sur la face ouest du mur, à 23 unités.
        assertEquals(0xCA8, level.p1angle);
        int column = player.cell() % World.SIZE;
        double distanceToEastEdge = (column + 1) * 64.0 - player.worldX();
        assertEquals(23.0, distanceToEastEdge, 1e-6);
        assertTrue(player.cell() == start || !world.solid(player.cell()));
    }

    @Test
    void feetSettleOnTheFloor() throws IOException {
        Level level = level(1);
        World world = new World(level);
        Player player = new Player(world, Trig.computed(), level.p1x, level.p1y, level.p1angle);
        for (int i = 0; i < 200; i++) {
            player.tick(0, 0, false, 0);
        }
        double feet = player.eyeHeight() + Player.EYE / 65536.0;
        assertEquals(world.floor(player.cell()), feet, 0.2);
    }

    @Test
    void meshHasWallsAndFlats() throws IOException {
        LevelMesh mesh = new LevelMesh(new World(level(1)));
        int walls = 0;
        int flats = 0;
        for (LevelMesh.Batch batch : mesh.batches()) {
            if (batch.flat) {
                flats += batch.quads();
            } else {
                walls += batch.quads();
            }
            assertTrue(batch.texture >= 0 && batch.texture < 16);
        }
        assertTrue(walls > 100, "murs : " + walls);
        assertTrue(flats > 100, "sols et plafonds : " + flats);
        assertEquals(0, flats % 2, "un sol et un plafond par case ouverte");
    }

    @Test
    void spaceInFrontOfTheFirstDoorOpensItAndClosesIt() throws IOException {
        Level level = level(1);
        World world = new World(level);
        Player player = new Player(world, Trig.computed(), level.p1x, level.p1y, level.p1angle);
        Doors doors = new Doors(world, player, true);
        int[] events = new int[2];
        doors.setListener(new Doors.Listener() {
            @Override
            public void doorStarted(int door) {
                events[0] = door;
            }

            @Override
            public void doorStopped() {
                events[1]++;
            }
        });
        // La porte n° 1 est quatre cases à l'est du départ, déclencheur « ±x » : on y marche.
        for (int i = 0; i < 300; i++) {
            player.tick(1, 0, false, 0);
        }
        assertEquals(World.index(17, 49), player.cell());
        int revision = world.revision();
        doors.tick(true);
        int door = doors.activeDoor();
        assertEquals(1, door, "la porte devant le joueur ne s'est pas déclenchée");
        assertEquals(door, events[0]);
        int frames = 0;
        while (doors.activeDoor() != 0 && frames < 500) {
            doors.tick(false);
            frames++;
        }
        assertEquals(0, doors.activeDoor(), "la porte ne s'arrête pas");
        assertEquals(1, events[1]);
        assertTrue(world.revision() > revision, "rien n'a bougé");
        System.out.printf("Porte %d : aller en %d VBL%n", door, frames);

        // Second appui : retour à l'état A.
        int[] heights = snapshot(world, level.doors.get(door));
        doors.tick(true);
        while (doors.activeDoor() != 0) {
            doors.tick(false);
        }
        int[] back = snapshot(world, level.doors.get(door));
        for (int i = 0; i < back.length; i += 2) {
            int cell = level.doors.get(door).get(i / 2);
            assertEquals(level.a[cell].floor(), back[i], "sol revenu à l'état A");
        }
        assertFalse(java.util.Arrays.equals(heights, back));
    }

    private static int[] snapshot(World world, java.util.List<Integer> cells) {
        int[] out = new int[cells.size() * 2];
        for (int i = 0; i < cells.size(); i++) {
            out[2 * i] = world.floor(cells.get(i));
            out[2 * i + 1] = world.ceiling(cells.get(i));
        }
        return out;
    }

    @Test
    void objectTableMatchesTheDocumentation() throws IOException {
        Path resources = resources();
        assumeTrue(resources != null, "ressources extraites absentes");
        EngineData data = new EngineData(LinkedImage.load(resources.resolve("raw").resolve("d1_0282da.bin")));
        // MOTEUR.md, rendu §6.3 : n° → image, W, Hs, dy.
        assertEquals(16, data.object(4).image());
        assertEquals(18, data.object(14).image());
        assertEquals(5.469, data.object(14).height(), 0.001);
        assertEquals(67.0, data.object(12).dy(), 1e-9);
        assertEquals(720, data.sound(0x54).period());
        assertEquals(10721 * 2, data.sound(0x54).pcm().length);
    }
}
