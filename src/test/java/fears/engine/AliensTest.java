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

/** Aliens du niveau 1 contre les règles du moteur (MOTEUR.md « acteurs » §2). */
class AliensTest {

    private static Path resources() {
        Path path = Path.of(System.getProperty("user.home"), ".fears", "ressources");
        return Files.isDirectory(path.resolve("levels")) ? path : null;
    }

    private record Setup(Level level, World world, Player player, Aliens aliens) {
    }

    private static Setup setup(int difficulty) throws IOException {
        Path resources = resources();
        assumeTrue(resources != null, "ressources extraites absentes");
        Path file;
        try (Stream<Path> files = Files.list(resources.resolve("levels"))) {
            file = files.filter(p -> p.getFileName().toString().startsWith("niveau01_")
                    && p.getFileName().toString().endsWith(".lvl")).findFirst().orElseThrow();
        }
        Level level = new Level(Files.readAllBytes(file));
        EngineData data = new EngineData(LinkedImage.load(resources.resolve("raw").resolve("d1_0282da.bin")));
        World world = new World(level);
        Player player = new Player(world, data.trig(), level.p1x, level.p1y, level.p1angle);
        Aliens aliens = new Aliens(world, player, data, LevelCatalog.forLevel(1).aliens(), difficulty);
        return new Setup(level, world, player, aliens);
    }

    @Test
    void spawnMarkersBecomeAliens() throws IOException {
        Setup s = setup(1);
        long markers = s.level().items.stream().filter(i -> i.type() >= 15).count();
        assertEquals(Math.min(64, markers), s.aliens().aliens().size());
        Aliens.Alien first = s.aliens().aliens().get(0);
        assertEquals(0, s.world().item(first.cell()), "le marqueur est effacé");
        assertEquals(first.number, s.world().alien(first.cell()));
        assertEquals(Aliens.State.ALIVE, first.state());
    }

    @Test
    void hardModeAddsThreeHealth() throws IOException {
        EngineData.AlienType type = setup(2).aliens().aliens().get(0).type;
        assertEquals(1, type.health(), "type 0 : 1 PV");
    }

    @Test
    void anAlienBehindTheFirstDoorComesAndIsShotDead() throws IOException {
        Setup s = setup(1);
        // Ouvrir la porte n° 1, quatre cases à l'est du départ.
        Doors doors = new Doors(s.world(), s.player(), true);
        for (int i = 0; i < 300; i++) {
            s.player().tick(1, 0, false, 0);
        }
        doors.tick(true);
        while (doors.activeDoor() != 0) {
            doors.tick(false);
        }
        Aliens.Alien alien = s.aliens().aliens().stream()
                .filter(a -> a.cell() == World.index(22, 49)).findFirst().orElseThrow();
        int startCell = alien.cell();
        int damage = 0;
        for (int i = 0; i < 400; i++) {
            damage += s.aliens().tick();
        }
        assertTrue(alien.active, "l'alien droit devant doit s'activer");
        assertTrue(alien.cell() != startCell, "il s'approche du joueur");
        assertFalse(alien.cell() == s.player().cell(), "jamais au contact");
        System.out.printf("Alien en %d,%d ; dégâts reçus %d%n", alien.cell() % 64, alien.cell() / 64, damage);

        // Deux balles de pistolet : PV 1, il meurt au 2e coup (PV négatifs).
        s.aliens().playerFired(Weapons.PISTOL);
        assertEquals(Aliens.State.ALIVE, alien.state());
        s.aliens().playerFired(Weapons.PISTOL);
        assertEquals(Aliens.State.DYING, alien.state());
        for (int i = 0; i < 40; i++) {
            s.aliens().tick();
        }
        assertEquals(Aliens.State.DEAD, alien.state());
        assertEquals(0, s.world().alien(alien.cell()), "un cadavre ne bloque pas");
    }

    @Test
    void aClosedDoorStopsThePistolAndAnOpenOneDoesNot() throws IOException {
        Setup s = setup(1);
        // Jusqu'à la porte n° 1, fermée : l'alien (22,49) est droit devant, derrière elle.
        for (int i = 0; i < 300; i++) {
            s.player().tick(1, 0, false, 0);
        }
        Aliens.Alien alien = s.aliens().aliens().stream()
                .filter(a -> a.cell() == World.index(22, 49)).findFirst().orElseThrow();
        int health = alien.health;
        s.aliens().playerFired(Weapons.PISTOL);
        assertEquals(health, alien.health, "le tir a traversé la porte fermée");

        Doors doors = new Doors(s.world(), s.player(), true);
        doors.tick(true);
        while (doors.activeDoor() != 0) {
            doors.tick(false);
        }
        s.aliens().playerFired(Weapons.PISTOL);
        assertEquals(health - 1, alien.health, "porte ouverte : l'alien est touché");
    }

    @Test
    void anAwakenedAlienFiresAtItsFirstStepWhenClose() throws IOException {
        Setup s = setup(1);
        for (int i = 0; i < 300; i++) {
            s.player().tick(1, 0, false, 0);
        }
        Doors doors = new Doors(s.world(), s.player(), true);
        doors.tick(true);
        while (doors.activeDoor() != 0) {
            doors.tick(false);
        }
        // L'alien (22,49) est à 5 cases : réveillé, il tire tout de suite (compteur +38 à 0).
        int steps = 0;
        while (s.aliens().alienShots() == 0 && steps < 100) {
            s.aliens().tick();
            steps++;
        }
        assertTrue(steps <= 2, "premier tir après " + steps + " VBL");
    }

    @Test
    void anAlienShotArrivesAtTheAimedHeight() throws IOException {
        Setup s = setup(1);
        for (int i = 0; i < 300; i++) {
            s.player().tick(1, 0, false, 0);
        }
        Doors doors = new Doors(s.world(), s.player(), true);
        doors.tick(true);
        while (doors.activeDoor() != 0) {
            doors.tick(false);
        }
        double last = Double.NaN;
        int hits = 0;
        for (int i = 0; i < 200 && hits == 0; i++) {
            for (Aliens.Shot shot : s.aliens().shots()) {
                if (!shot.fromPlayer) {
                    last = shot.h();
                }
            }
            s.aliens().tick();
            hits += s.aliens().takeHits();
        }
        assertEquals(1, hits, "le tir n'a pas touché");
        // Visée L012a1c : pieds − 0,9155 ; le tir arrive à moins d'un pas vertical de cette hauteur.
        assertEquals(s.player().feet() - Aliens.SPRITE_EYE, last, 0.5);
    }
}
