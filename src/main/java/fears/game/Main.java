package fears.game;

import com.jme3.system.AppSettings;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Point d'entrée du jeu.
 *
 * <p>Les ressources viennent des disquettes d'origine, extraites au premier lancement (voir
 * {@link Resources}) : {@code gradle run -Dfears.adf=<répertoire des ADF>}.
 */
public final class Main {

    /** Taille de fenêtre par défaut : l'écran 640×512 (lignes doublées) agrandi d'une moitié. */
    private static final int WIDTH = 960;
    private static final int HEIGHT = 768;

    private Main() {
    }

    public static void main(String[] args) {
        Path resources;
        try {
            resources = Resources.obtain();
        } catch (IOException e) {
            System.err.println("ERREUR : " + e.getMessage());
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                javax.swing.JOptionPane.showMessageDialog(null, e.getMessage(), "Fears",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
            }
            System.exit(1);
            return;
        }
        System.out.println("Ressources : " + resources);
        System.out.println("Menu : souris ou fleches pour viser, clic gauche / Entree / Espace pour choisir,"
                + " Echap pour quitter");

        AppSettings settings = new AppSettings(true);
        settings.setTitle("Fears");
        settings.setResolution(WIDTH, HEIGHT);
        settings.setResizable(true);
        settings.setVSync(true);
        settings.setGammaCorrection(false);

        FearsApp app = new FearsApp(resources);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }
}
