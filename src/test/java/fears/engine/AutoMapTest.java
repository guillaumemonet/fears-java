package fears.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Table de couleurs de l'aiguille (L016414). */
class AutoMapTest {

    @Test
    void shadeTablePeaksAtHalfTurn() {
        assertEquals(0, AutoMap.shade(0));
        assertEquals(0, AutoMap.shade(360));
        // V[180] = (126, 84, 91).
        assertEquals((126 << 16) | (84 << 8) | 91, AutoMap.shade(180));
        assertEquals(AutoMap.shade(90), AutoMap.shade(270));
    }
}
