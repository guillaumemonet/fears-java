package fears.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cadence, munitions et changement d'arme (MOTEUR.md « acteurs » §3). */
class WeaponsTest {

    @Test
    void pistolFiresEveryFifteenVblAndUsesBullets() {
        Stats stats = new Stats();
        Weapons weapons = new Weapons(stats);
        int shots = 0;
        for (int i = 0; i < 150; i++) {
            if (weapons.tick(true) != 0) {
                shots++;
            }
        }
        assertEquals(10, shots);
        assertEquals(99 - 10, stats.get(Stats.AMMO_1));
    }

    @Test
    void firingAnimatesThroughTheShotImages() {
        Weapons weapons = new Weapons(new Stats());
        assertEquals(1, weapons.image());
        weapons.tick(true);
        assertEquals(2, weapons.image());
        for (int i = 0; i < 3; i++) {
            weapons.tick(false);
        }
        assertEquals(3, weapons.image());
        for (int i = 0; i < 3; i++) {
            weapons.tick(false);
        }
        assertEquals(1, weapons.image());
    }

    @Test
    void switchingLowersThenRaisesTheWeapon() {
        Stats stats = new Stats();
        Weapons weapons = new Weapons(stats);
        weapons.select(Weapons.FIST);
        int lowest = 0;
        int frames = 0;
        do {
            weapons.tick(false);
            lowest = Math.max(lowest, weapons.vstart());
            frames++;
        } while ((weapons.weapon() != Weapons.FIST || weapons.vstart() != Weapons.RAISED) && frames < 200);
        assertEquals(Weapons.LOWERED, lowest);
        assertEquals(Weapons.FIST, weapons.weapon());
        assertTrue(frames > 30 && frames < 45, "changement en " + frames + " VBL");
    }

    @Test
    void anUnownedWeaponCannotBeSelected() {
        Weapons weapons = new Weapons(new Stats());
        weapons.select(Weapons.PLASMA);
        for (int i = 0; i < 60; i++) {
            weapons.tick(false);
        }
        assertEquals(Weapons.PISTOL, weapons.weapon());
    }

    @Test
    void fistRecoilReturnsToItsPlace() {
        Stats stats = new Stats();
        Weapons weapons = new Weapons(stats);
        weapons.select(Weapons.FIST);
        for (int i = 0; i < 60; i++) {
            weapons.tick(false);
        }
        weapons.tick(true);
        for (int i = 0; i < 20; i++) {
            weapons.tick(false);
        }
        assertEquals(Weapons.RAISED, weapons.vstart());
    }
}
