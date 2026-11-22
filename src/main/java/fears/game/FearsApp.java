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
        // Échap n'arrête plus tout : en jeu, il ramène au menu.
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);
        boolean boot = !"off".equals(System.getProperty("fears.intro"));
        frontEnd = new FrontEnd(assets, boot);
        frontEndState = new FrontEndState(frontEnd, music, action -> startGame(1));
        String direct = System.getProperty("fears.niveau");
        if (direct != null) {
            startGame(Integer.parseInt(direct));
        } else {
            stateManager.attach(frontEndState);
        }
    }

    /** START GAME : le niveau, avec les réglages de la page OPTIONS. */
    private void startGame(int level) {
        stateManager.detach(frontEndState);
        if (interlude != null) {
            stateManager.detach(interlude);
            interlude = null;
        }
        if (gameState != null) {
            stateManager.detach(gameState);
        }
        if (level == 1 || session == null) {
            session = new fears.engine.Stats();   // nouvelle partie
        }
        gameState = new GameState(resources, level, frontEnd.menu().options(), session,
                this::backToFrontEnd, this::levelFinished);
        gameState.setOnGameOver(this::gameOver);
        stateManager.attach(gameState);
    }

    /** Plus de vie ({@code L011b96} → {@code L012cfc}) : l'écran de game over, puis le menu. */
    private void gameOver() {
        stateManager.detach(gameState);
        gameState = null;
        session = null;
        try {
            fears.menu.GameOverScreen screen = new fears.menu.GameOverScreen(resources);
            MusicPlayer deadEnd = new MusicPlayer(this);
            deadEnd.load(resources.resolve("gameover").resolve("dead_end.mod"));
            deadEnd.play();
            interlude = new InterludeState(() -> {
                deadEnd.stop();
                stateManager.detach(interlude);
                interlude = null;
                backToFrontEnd();
            }, new InterludeState.Step(screen, screen::finished));
            stateManager.attach(interlude);
        } catch (java.io.IOException e) {
            System.err.println("AVERTISSEMENT : ecran de game over illisible : " + e.getMessage());
            backToFrontEnd();
        }
    }

    /**
     * Sortie atteinte ({@code L011b7a}) : score, écran des statistiques, puis le château pendant
     * le « chargement » du niveau suivant ; après le trentième, retour au menu.
     * [à porter] SAVE GAME, et la séquence de fin après le niveau 30 (d2_0a9a00).
     */
    private void levelFinished(fears.engine.LevelResult result) {
        int level = result.level();
        session.endLevel(result);
        System.out.printf("Niveau %d termine : aliens %d/%d, secrets %d/%d, tresors %d/%d, score %d%n",
                level, result.aliensKilled(), result.aliensTotal(), result.secretsFound(),
                result.secretsTotal(), result.goldTaken(), result.goldTotal(), session.score());
        stateManager.detach(gameState);
        gameState = null;
        try {
            fears.menu.StatsScreen stats = new fears.menu.StatsScreen(assets, result,
                    session.totalTime(), session.score());
            if (level >= 30) {
                interlude = new InterludeState(() -> {
                    stateManager.detach(interlude);
                    interlude = null;
                    backToFrontEnd();
                }, new InterludeState.Step(stats, stats::finished));
                stateManager.attach(interlude);
                return;
            }
            fears.menu.CastleScreen castle = new fears.menu.CastleScreen(resources);
            interlude = new InterludeState(() -> {
                if (stats.saveRequested()) {
                    System.out.println("SAVE GAME : pas encore porte.");
                }
                startGame(level + 1);
            }, new InterludeState.Step(stats, stats::finished),
                    new InterludeState.Step(castle, castle::finished));
            stateManager.attach(interlude);
        } catch (java.io.IOException e) {
            System.err.println("AVERTISSEMENT : ecrans de fin de niveau illisibles : " + e.getMessage());
            startGame(level + 1);
        }
    }

    /** Retour du jeu : la boucle du menu reprend à la présentation. */
    private void backToFrontEnd() {
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
