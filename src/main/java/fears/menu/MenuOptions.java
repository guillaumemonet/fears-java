package fears.menu;

/**
 * Les réglages de la page OPTIONS, tels que le menu les passe au moteur (registre a4,
 * {@code L011cb6}).
 *
 * @param wallsOff    {@code L011cb8} bit 1 : murs non texturés
 * @param ceilingOff  {@code L011cb8} bit 0 : plafond non texturé
 * @param dithering   {@code L011cba}
 * @param screenSize  {@code L011cbe} : 0 MAX, 1 BIG, 2 LOW, 3 MIN
 * @param walkEffect  {@code L011cbc} : balancement de la marche
 * @param difficulty  {@code L011cc0} : 0 EASY, 1 NORMAL, 2 HIGH
 * @param modemMaze   {@code L011cc4} : labyrinthe du jeu par câble, 0..2
 * @param alienHero   {@code L011cc6} : en jeu par câble, jouer l'alien
 */
public record MenuOptions(boolean wallsOff, boolean ceilingOff, boolean dithering, int screenSize,
                          boolean walkEffect, int difficulty, int modemMaze, boolean alienHero) {
}
