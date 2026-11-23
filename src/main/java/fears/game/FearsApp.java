package fears.game;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import fears.menu.MenuAssets;
import fears.menu.FrontEnd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * La coquille jMonkeyEngine du jeu : les écrans d'avant le jeu (intro, présentation, menu)
 * et le jeu lui-même, l'un ou l'autre attaché.
 *
 * <p>{@code -Dfears.niveau=N} lance directement le niveau N, sans passer par le menu.
 */
final class FearsApp extends SimpleApplication {

    private final Path resources;
    private FrontEndState frontEndState;
    private FrontEnd frontEnd;
    private GameState gameState;
    private MenuAssets assets;
    private InterludeState interlude;
    /** Difficulté de la partie en cours : celle du menu, ou celle de la partie chargée. */
    private int difficulty = 1;
    /** Réglages d'affichage de la partie en cours : ils passent d'un niveau à l'autre. */
    private fears.menu.MenuOptions display;
    /** Les stats de la partie en cours, transmises d'un niveau au suivant. */
    private fears.engine.Stats session;

    /** Capture demandée par {@code -Dfears.capture=<fichier.png>}, prise au bout de quatre secondes. */
    private final String capture = System.getProperty("fears.capture");
    private float captureDelay = Float.parseFloat(System.getProperty("fears.capture.delay", "4"));

    FearsApp(Path resources) {
        // Ni caméra libre, ni statistiques : le jeu gère lui-même ses écrans.
        super(new AppState[0]);
        this.resources = resources;
    }

    @Override
    public void simpleInitApp() {
        try {
            assets = MenuAssets.load(resources);
        } catch (IOException e) {
            System.err.println("ERREUR : ressources du menu illisibles : " + e.getMessage());
            System.err.println("Supprimez " + resources + " pour forcer une nouvelle extraction.");
            stop();
            return;
        }
        MusicPlayer music = new MusicPlayer(this);
        Path module = menuModule();
        if (module != null) {
            music.load(module);
        } else {
            System.err.println("AVERTISSEMENT : musique du menu introuvable dans " + resources.resolve("music"));
        }
        // Une vue préalable vide toute la fenêtre en noir : la vue du jeu, réduite par la taille
        // d'écran ou centrée en 5:4, ne vide que son propre rectangle.
        com.jme3.renderer.Camera full = cam.clone();
        full.setViewPort(0, 1, 0, 1);
        com.jme3.renderer.ViewPort background = renderManager.createPreView("fond", full);
        background.setClearFlags(true, true, true);
        background.setBackgroundColor(com.jme3.math.ColorRGBA.Black);

        // Échap n'arrête plus tout : en jeu, il ramène au menu.
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);
        boolean boot = !"off".equals(System.getProperty("fears.intro"));
        frontEnd = new FrontEnd(assets, boot);
        frontEndState = new FrontEndState(frontEnd, music, action -> {
            switch (action) {
                case START_GAME -> newGame();
                case LOAD_GAME -> loadGame();
                default -> {
                }
            }
        });
        String direct = System.getProperty("fears.niveau");
        if (direct != null) {
            session = new fears.engine.Stats();
            startGame(Integer.parseInt(direct));
        } else {
            stateManager.attach(frontEndState);
        }
    }

    /** Les sauvegardes, à côté du cache des ressources : {@code ~/.fears/sauvegardes}. */
    private Path saves() {
        String configured = System.getProperty("fears.sauvegardes");
        return configured != null ? Path.of(configured)
                : Resources.cacheDirectory().getParent().resolve("sauvegardes");
    }

    /** Joue une suite d'écrans fixes, puis enchaîne. */
    private void play(Runnable then, InterludeState.Step... steps) {
        stateManager.detach(frontEndState);
        if (interlude != null) {
            stateManager.detach(interlude);
        }
        interlude = new InterludeState(() -> {
            stateManager.detach(interlude);
            interlude = null;
            then.run();
        }, steps);
        stateManager.attach(interlude);
    }

    /** START GAME : une nouvelle partie au niveau 1, avec les réglages de la page OPTIONS. */
    private void newGame() {
        session = new fears.engine.Stats();
        difficulty = frontEnd.menu().options().difficulty();
        display = frontEnd.menu().options();
        startGame(1);
    }

    /** LOAD GAME ({@code L014524}) : choix d'un emplacement, puis reprise à son niveau. */
    private void loadGame() {
        fears.menu.SlotScreen slots = new fears.menu.SlotScreen(assets, saves(), false);
        play(() -> {
            int chosen = slots.chosen();
            if (chosen < 0 || chosen >= fears.engine.SaveGame.SLOTS) {
                backToFrontEnd();
                return;
            }
            fears.engine.SaveGame save = slots.slot(chosen);
            session = save.toStats();
            difficulty = save.difficulty();
            fears.menu.MenuOptions menu = frontEnd.menu().options();
            display = new fears.menu.MenuOptions(save.wallsOff(), save.ceilingOff(), save.dithering(),
                    save.screenSize(), menu.walkEffect(), save.difficulty(), menu.modemMaze(), menu.alienHero());
            frontEnd.menu().applyOptions(display);
            System.out.printf("Partie %d chargee : niveau %d, score %d%n", chosen + 1, save.level() + 1, save.score());
            startGame(save.level() + 1);
        }, new InterludeState.Step(slots, slots::finished));
    }

    /** Un niveau de la partie en cours. */
    private void startGame(int level) {
        stateManager.detach(frontEndState);
        if (interlude != null) {
            stateManager.detach(interlude);
            interlude = null;
        }
        if (gameState != null) {
            stateManager.detach(gameState);
        }
        fears.menu.MenuOptions menu = display != null ? display : frontEnd.menu().options();
        fears.menu.MenuOptions options = new fears.menu.MenuOptions(menu.wallsOff(), menu.ceilingOff(),
                menu.dithering(), menu.screenSize(), menu.walkEffect(), difficulty, menu.modemMaze(),
                menu.alienHero());
        gameState = new GameState(resources, level, options, session, this::backToFrontEnd, this::levelFinished);
        gameState.setOnGameOver(this::gameOver);
        stateManager.attach(gameState);
    }

    /** Plus de vie ({@code L011b96} → {@code L012cfc}) : l'écran de game over, puis le menu. */
    private void gameOver() {
        display = gameState.displayOptions();
        frontEnd.menu().applyOptions(display);
        stateManager.detach(gameState);
        gameState = null;
        session = null;
        try {
            fears.menu.GameOverScreen screen = new fears.menu.GameOverScreen(resources);
            MusicPlayer deadEnd = new MusicPlayer(this);
            deadEnd.load(resources.resolve("gameover").resolve("dead_end.mod"));
            deadEnd.play();
            play(() -> {
                deadEnd.stop();
                backToFrontEnd();
            }, new InterludeState.Step(screen, screen::finished));
        } catch (java.io.IOException e) {
            System.err.println("AVERTISSEMENT : ecran de game over illisible : " + e.getMessage());
            backToFrontEnd();
        }
    }

    /**
     * Sortie atteinte ({@code L011b7a}) : score, écran des statistiques, sauvegarde si elle est
     * demandée ({@code L014444}), puis le château pendant le « chargement » du niveau suivant ;
     * après le trentième, la séquence de fin.
     */
    private void levelFinished(fears.engine.LevelResult result) {
        int level = result.level();
        session.endLevel(result);
        System.out.printf("Niveau %d termine : aliens %d/%d, secrets %d/%d, tresors %d/%d, score %d%n",
                level, result.aliensKilled(), result.aliensTotal(), result.secretsFound(),
                result.secretsTotal(), result.goldTaken(), result.goldTotal(), session.score());
        display = gameState.displayOptions();
        stateManager.detach(gameState);
        gameState = null;
        fears.menu.StatsScreen stats = new fears.menu.StatsScreen(assets, result,
                session.totalTime(), session.score());
        play(() -> {
            if (level >= 30) {
                theEnd();
            } else if (stats.saveRequested()) {
                saveThenContinue(level, result);
            } else {
                castleThenLevel(level + 1);
            }
        }, new InterludeState.Step(stats, stats::finished));
    }

    /** Après le niveau 30 : la séquence de fin (d2_0a9a00) sur le module « fin », puis le menu. */
    private void theEnd() {
        session = null;
        try {
            fears.menu.EndSequence end = new fears.menu.EndSequence(assets, resources);
            MusicPlayer music = new MusicPlayer(this);
            music.load(resources.resolve("fin").resolve("fin.mod"));
            music.play();
            play(() -> {
                music.stop();
                backToFrontEnd();
            }, new InterludeState.Step(end, end::finished));
        } catch (IOException e) {
            System.err.println("AVERTISSEMENT : sequence de fin illisible : " + e.getMessage());
            backToFrontEnd();
        }
    }

    /** SAVE GAME : choix d'un emplacement, écriture, puis le niveau suivant. */
    private void saveThenContinue(int level, fears.engine.LevelResult result) {
        fears.menu.SlotScreen slots = new fears.menu.SlotScreen(assets, saves(), true);
        play(() -> {
            int chosen = slots.chosen();
            if (chosen >= 0 && chosen < fears.engine.SaveGame.SLOTS) {
                try {
                    // Le niveau suivant, compté de 0 comme L011cc8 + 1.
                    fears.engine.SaveGame.of(session, level, result.time(), difficulty, display.wallsOff(),
                            display.ceilingOff(), display.dithering(), display.screenSize()).write(saves(), chosen);
                    System.out.println("Partie sauvegardee dans l'emplacement " + (chosen + 1));
                } catch (IOException e) {
                    System.err.println("AVERTISSEMENT : sauvegarde impossible : " + e.getMessage());
                }
            }
            castleThenLevel(level + 1);
        }, new InterludeState.Step(slots, slots::finished));
    }

    private void castleThenLevel(int next) {
        try {
            fears.menu.CastleScreen castle = new fears.menu.CastleScreen(resources);
            play(() -> startGame(next), new InterludeState.Step(castle, castle::finished));
        } catch (IOException e) {
            System.err.println("AVERTISSEMENT : ecran du chateau illisible : " + e.getMessage());
            startGame(next);
        }
    }

    /** Retour du jeu : la boucle du menu reprend à la présentation, avec les réglages du jeu. */
    private void backToFrontEnd() {
        if (gameState != null) {
            display = gameState.displayOptions();
        }
        if (display != null) {
            frontEnd.menu().applyOptions(display);
        }
        if (gameState != null) {
            stateManager.detach(gameState);
            gameState = null;
        }
        stateManager.attach(frontEndState);
    }

    private Path menuModule() {
        try (Stream<Path> files = Files.list(resources.resolve("music"))) {
            return files.filter(p -> p.getFileName().toString().endsWith("_menu.mod"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (capture != null && captureDelay > 0) {
            captureDelay -= tpf;
            if (captureDelay <= 0) {
                captureFrame(Path.of(capture));
            }
        }
    }

    /**
     * Écrit l'image de la fenêtre dans un PNG, puis quitte si {@code -Dfears.capture.quit=true} :
     * de quoi vérifier l'affichage sans regarder l'écran.
     */
    private void captureFrame(Path file) {
        int w = cam.getWidth();
        int h = cam.getHeight();
        java.nio.ByteBuffer pixels = com.jme3.util.BufferUtils.createByteBuffer(w * h * 4);
        renderer.readFrameBufferWithFormat(null, pixels, com.jme3.texture.Image.Format.RGBA8);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = ((h - 1 - y) * w + x) * 4;
                image.setRGB(x, y, ((pixels.get(at) & 0xFF) << 16) | ((pixels.get(at + 1) & 0xFF) << 8)
                        | (pixels.get(at + 2) & 0xFF));
            }
        }
        try {
            javax.imageio.ImageIO.write(image, "png", file.toFile());
            System.out.println("Capture : " + file.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Capture impossible : " + e.getMessage());
        }
        if (Boolean.getBoolean("fears.capture.quit")) {
            stop();
        }
    }
}
