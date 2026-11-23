package fears.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Ambiances du premier jeu de murs, comparées à ambiance.txt de l'extracteur. */
class AmbienceTest {

    @Test
    void envelopeFollowsTheTableAndLoops() throws IOException {
        Path raw = Path.of(System.getProperty("user.home"), ".fears", "ressources", "raw", "d1_06c5c0.bin");
        assumeTrue(Files.isRegularFile(raw), "ressources extraites absentes");
        Ambience ambience = new Ambience(Files.readAllBytes(raw));
        Ambience.Channel two = ambience.channel(0);
        // 32746 octets, boucle à 150 : jouée de 350 à la fin.
        assertEquals(32746 - 350, two.pcm().length);
        assertEquals(400, two.basePeriod());
        assertEquals(10, two.volume(), 1e-9);
        for (int i = 0; i < 150; i++) {
            ambience.tick();
        }
        // À mi-chemin de (0, 10, 400) → (300, 30, 450).
        assertEquals(20, two.volume(), 1e-9);
        assertEquals(425, two.period(), 1e-9);
        for (int i = 150; i < 4100; i++) {
            ambience.tick();
        }
        assertEquals(10, two.volume(), 1e-9, "retour à la première entrée");
    }
}
