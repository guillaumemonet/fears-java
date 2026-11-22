package fears.menu;

/**
 * Un écran Amiga porté sans dépendance graphique : il avance d'une VBL (1/50 s) à chaque
 * {@link #tick()} et compose son image en 0xRRGGBB.
 */
public interface Scene {

    int width();

    int height();

    /** Hauteur d'un pixel rapportée à sa largeur : 2 en hires non entrelacé, 1 en entrelacé. */
    float pixelAspect();

    void tick();

    int[] frame();

    /** Déplacement de la souris, en pixels de l'image. */
    default void movePointer(double dx, double dy) {
    }

    /** Joystick, ou flèches du clavier. */
    default void setJoystick(boolean up, boolean down, boolean left, boolean right) {
    }

    /** Bouton gauche de la souris ou feu du joystick. */
    default void setFire(boolean pressed) {
    }
}
