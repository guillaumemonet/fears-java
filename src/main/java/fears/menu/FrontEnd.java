package fears.menu;

/**
 * L'enchaînement des écrans avant le jeu.
 *
 * <ol>
 *   <li>l'intro de démarrage ({@code d1_0d140c}), sans musique ;</li>
 *   <li>la présentation du menu ({@code L010e7a}) : la musique démarre avec elle
 *       ({@code L010138} = 1 à 0x1008a) ;</li>
 *   <li>le menu, qui commence par son propre fondu d'entrée.</li>
 * </ol>
 *
 * <p>Quoi qu'on choisisse au menu, la boucle {@code L010092} repasse par la présentation :
 * CREDITS directement, les autres choix au retour du jeu. Tant que le moteur n'est pas
 * porté, le choix est remis à l'appelant par {@link #takeAction()} et l'on revient de même
 * à la présentation.
 */
public final class FrontEnd {

    private final MenuAssets assets;
    private final MenuScreen menu;
    private Scene current;
    private BootIntro boot;
    private Presentation presentation;
    private MenuAction action;

    /**
     * @param withBootIntro faux pour commencer directement à la présentation
     */
    public FrontEnd(MenuAssets assets, boolean withBootIntro) {
        this.assets = assets;
        this.menu = new MenuScreen(assets);
        if (withBootIntro) {
            boot = new BootIntro(assets);
            current = boot;
        } else {
            startPresentation();
        }
    }

    /** L'écran affiché. */
    public Scene scene() {
        return current;
    }

    public MenuScreen menu() {
        return menu;
    }

    /** La musique du menu doit-elle jouer ? Pas pendant l'intro de démarrage. */
    public boolean musicPlaying() {
        return current != boot;
    }

    /** Volume de la musique, de 0 à 1 : elle s'éteint pendant le fondu qui précède le jeu. */
    public float musicVolume() {
        return current == menu && menu.musicStopping() ? (float) (1 - menu.fadeOutProgress()) : 1f;
    }

    /** Le dernier choix fait au menu, une seule fois ; {@code null} sinon. */
    public MenuAction takeAction() {
        MenuAction taken = action;
        action = null;
        return taken;
    }

    public void tick() {
        current.tick();
        if (current == boot && boot.finished()) {
            boot = null;
            startPresentation();
        } else if (current == presentation && presentation.finished()) {
            presentation = null;
            menu.restart();
            current = menu;
        } else if (current == menu) {
            MenuAction chosen = menu.takeAction();
            if (chosen != null) {
                action = chosen;
                startPresentation();
            }
        }
    }

    private void startPresentation() {
        presentation = new Presentation(assets);
        current = presentation;
    }
}
