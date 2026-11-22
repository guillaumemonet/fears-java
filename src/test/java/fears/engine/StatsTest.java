package fears.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ramassage et usure, d'après MOTEUR.md « monde » §6 et §7. */
class StatsTest {

    private final boolean[] taken = new boolean[1];

    @Test
    void fullHealthRefusesTheMedkitWhichStaysInPlace() {
        Stats stats = new Stats();
        assertEquals(Stats.SOUND_FULL, stats.pickUp(9, taken));
        assertFalse(taken[0]);
        assertEquals(99, stats.get(Stats.HEALTH));
    }

    @Test
    void ammoAddsEightUpToNinetyNine() {
        Stats stats = new Stats();
        assertEquals(Stats.SOUND_AMMO, stats.pickUp(6, taken));
        assertTrue(taken[0]);
        assertEquals(2, stats.get(Stats.AMMO_2));
    }

    @Test
    void aWeaponIsSelectedWhenPickedUp() {
        Stats stats = new Stats();
        assertEquals(Stats.SOUND_WEAPON, stats.pickUp(2, taken));
        assertEquals(3, stats.takePickedWeapon());
        assertEquals(-1, stats.takePickedWeapon());
        assertEquals(1, stats.get(Stats.ROCKET));
        assertEquals(Stats.SOUND_FULL, stats.pickUp(2, taken));
    }

    @Test
    void shieldLastsNineHundredVblAndProtectsFromAcid() {
        Stats stats = new Stats();
        assertEquals(Stats.SOUND_HEALTH, stats.pickUp(10, taken));
        assertEquals(Stats.SOUND_FULL, stats.pickUp(10, taken));
        for (int i = 0; i < 900; i++) {
            stats.tick(true);
        }
        assertEquals(0, stats.get(Stats.SHIELD));
        assertEquals(99, stats.get(Stats.HEALTH));
        for (int i = 0; i < 100; i++) {
            stats.tick(true);
        }
        assertEquals(89, stats.get(Stats.HEALTH), "acide : 1 point toutes les 10 VBL");
    }

    @Test
    void decorIsNeverPickedUp() {
        Stats stats = new Stats();
        for (int type = 12; type <= 14; type++) {
            assertEquals(0, stats.pickUp(type, taken));
            assertFalse(taken[0]);
        }
    }
}
