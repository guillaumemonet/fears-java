package fears.game;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.event.JoyAxisEvent;
import com.jme3.input.event.JoyButtonEvent;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.input.event.TouchEvent;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;
import fears.engine.Aliens;
import fears.engine.Doors;
import fears.engine.EngineData;
import fears.engine.Hud;
import fears.engine.Stats;
import fears.engine.Weapons;
import fears.engine.LevelMesh;
import fears.engine.Player;
import fears.engine.Trig;
import fears.engine.World;
import fears.extract.Level;
import fears.extract.LevelCatalog;
import fears.menu.LinkedImage;
import fears.menu.MenuOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Un niveau en 3D : la géométrie tirée de la grille, le joueur et ses déplacements portés du
 * moteur, à 50 Hz.
 *
 * <p>Commandes : flèches ou Z/S (W/S) pour avancer et reculer, flèches gauche/droite pour
 * tourner, Q/D (A/D) pour le pas de côté, Maj pour courir, souris pour tourner, bouton droit
 * pour avancer (comme sur Amiga), clic gauche ou Ctrl pour tirer, 1 à 6 pour choisir
 * l'arme, Espace pour actionner une porte ou un interrupteur. Échap
 * rend la main au menu ; la sortie du niveau mène au suivant.
 */
final class GameState extends BaseAppState implements RawInputListener {

    private static final float VBL = 1f / 50f;
    private static final int MAX_CATCH_UP = 5;
    /** Champ de vision vertical par défaut, en degrés ({@code -Dfears.fov}). */
    private static final float DEFAULT_FOV = 50f;

    private final Path resources;
    private final int levelNumber;
    private final MenuOptions options;
    /** Réglages d'affichage modifiables en jeu (F1 à F7), rendus au menu à la sortie. */
    private boolean wallsOff;
    private boolean ceilingOff;
    private boolean dithering;
    private int screenSize;
    /** Anti-rebond des touches F (10 VBL pour F1-F3, 20 pour F4-F7). */
    private int keyDelay;
    private int pendingKey = -1;
    /** Couleur dominante de chaque texture de mur (mode WALLS OFF), en textures 1×1. */
    private Texture2D[] flatWalls;
    private Texture2D floorFlat1;
    private Texture2D floorFlatOther;
    /** Pixels « chunky » et tramage ; {@code -Dfears.chunky=on} les impose sans tramage. */
    private final ChunkyFilter chunky = new ChunkyFilter();
    private com.jme3.post.FilterPostProcessor postProcessor;
    private static final boolean CHUNKY_ALWAYS = "on".equals(System.getProperty("fears.chunky"));
    private final Runnable onQuit;
    private Runnable onGameOver = () -> { };

    /** Ce qui suit la dernière vie perdue. */
    void setOnGameOver(Runnable onGameOver) {
        this.onGameOver = onGameOver;
    }
    private final java.util.function.Consumer<fears.engine.LevelResult> onFinished;
    /** Comptes au chargement (L0155a4) et durée du niveau en VBL (L015ed8). */
    private int aliensTotal;
    private int secretsTotal;
    private int goldTotal;
    private int levelTime;

    private final Node scene = new Node("niveau");
    private final java.util.Map<String, Geometry> geometries = new java.util.HashMap<>();
    private Texture2D[] textures;
    private World world;
    private Player player;
    private Doors doors;
    private EngineData data;
    private EngineSounds sounds;
    /** Ambiances des murs (voies 2 et 3), ou null si le fichier de murs manque. */
    private AmbienceSound ambience;
    private ObjectView objects;
    private Aliens aliens;
    /** Le boss du niveau 30, ou null. */
    private fears.engine.Boss boss;
    /** La carte (TAB) : cases vues, et son écran quand elle est ouverte. */
    private fears.engine.AutoMap automap;
    private ScreenView mapView;
    private boolean mapOpen;
    private boolean tab;
    /** Toutes les touches de sortie ont été relâchées depuis l'ouverture (ou la fermeture). */
    private boolean mapReleased;
    private boolean closingWait;
    private AlienView alienView;
    private final Stats stats;
    private final Weapons weapons;
    private Hud hud;
    private ScreenView hudView;
    private boolean fire;
    private boolean mouseFire;
    private boolean hudChanged;
    private final boolean[] taken = new boolean[1];
    private int lastCell = -1;
    private Level level;
    private int builtRevision = -1;
    private boolean activate;
    /** Entrées scriptées ({@code -Dfears.script}), pour les captures automatiques. */
    private final java.util.ArrayDeque<String[]> script = parseScript(System.getProperty("fears.script"));
    private int scriptFrames;
    /** Sortie atteinte : plus rien ne bouge en attendant le niveau suivant. */
    private boolean finished;
    /** Séquence de mort en cours : VBL écoulées, −1 sinon. */
    private int dying = -1;
    private boolean fireReleased;
    private float accumulated;
    private double mouseTurn;

    private boolean forward;
    private boolean back;
    private boolean left;
    private boolean right;
    private boolean strafeLeft;
    private boolean strafeRight;
    private boolean run;
    private boolean mouseForward;

    /**
     * @param onQuit     Échap : retour au menu
     * @param onFinished sortie du niveau atteinte : reçoit le n° du niveau achevé
     */
    GameState(Path resources, int levelNumber, MenuOptions options, Stats stats, Runnable onQuit,
              java.util.function.Consumer<fears.engine.LevelResult> onFinished) {
        this.stats = stats;
        this.weapons = new Weapons(stats);
        this.resources = resources;
        this.levelNumber = levelNumber;
        this.options = options;
        this.wallsOff = options.wallsOff();
        this.ceilingOff = options.ceilingOff();
        this.dithering = options.dithering();
        this.screenSize = options.screenSize();
        this.onQuit = onQuit;
        this.onFinished = onFinished;
    }

    @Override
    protected void initialize(Application app) {
        try {
            load(app);
        } catch (IOException e) {
            System.err.println("ERREUR : niveau " + levelNumber + " illisible : " + e.getMessage());
            app.enqueue(onQuit::run);
        }
    }

    private void load(Application app) throws IOException {
        level = new Level(Files.readAllBytes(levelFile(levelNumber)));
        LevelCatalog.Entry entry = LevelCatalog.forLevel(levelNumber);
        System.out.println("Niveau " + levelNumber + " : " + level.title + " (murs " + entry.walls() + ")");

        LinkedImage engine = LinkedImage.load(resources.resolve("raw").resolve("d1_0282da.bin"));
        data = new EngineData(engine);
        world = new World(level);
        spawn();
        sounds = new EngineSounds(data);
        // Les interrupteurs ne basculent pas au niveau 30 (L017ca2).
        doors = new Doors(world, player, !entry.paletteB());
        doors.setListener(sounds);
        int[][] frames = new int[17][];
        int[][] indices = new int[17][];
        for (int i = 0; i < frames.length; i++) {
            Path file = resources.resolve("engine").resolve("armes").resolve(String.format("arme_%02d.png", i));
            frames[i] = Textures.argb(file);
            indices[i] = Textures.indices(file);
        }
        hud = new Hud(LinkedImage.load(resources.resolve("raw").resolve("d1_0282da.bin")), frames, indices);
        hud.update(stats, weapons);
        hudView = new ScreenView(Hud.WIDTH, Hud.HEIGHT, 2f, true);
        hudView.upload(hud.image());
        // Les apparitions d'aliens (objets 15 à 17) sont converties avant d'afficher les objets.
        int[] sets = entry.aliens();
        aliens = new Aliens(world, player, data, sets, options.difficulty());
        aliens.setListener(sounds);
        weapons.setProjectileFlying(aliens::playerShotFlying);
        automap = new fears.engine.AutoMap(engine, world);
        mapView = new ScreenView(fears.engine.AutoMap.WIDTH, fears.engine.AutoMap.HEIGHT, 1f);
        if (entry.paletteB()) {
            boss = new fears.engine.Boss(engine, world, doors, aliens);
            aliens.setBoss(boss);
        }
        alienView = new AlienView(app.getAssetManager(), world, aliens, sets,
                resources.resolve("sprites"), resources.resolve("objects").resolve("d1_00bf00"));
        scene.attachChild(alienView.node());
        aliensTotal = aliens.alive();
        secretsTotal = world.secretsLeft();
        goldTotal = world.goldLeft();
        objects = new ObjectView(app.getAssetManager(), world, data,
                resources.resolve("objects").resolve("d1_00bf00"));
        scene.attachChild(objects.node());

        Path wallsFile = resources.resolve("raw").resolve(entry.walls() + ".bin");
        if (Files.isRegularFile(wallsFile)) {
            ambience = new AmbienceSound(new fears.engine.Ambience(Files.readAllBytes(wallsFile)));
        } else {
            System.err.println("AVERTISSEMENT : ambiances des murs introuvables : " + wallsFile);
        }

        textures = new Texture2D[16];
        flatWalls = new Texture2D[16];
        Path walls = resources.resolve("walls").resolve(entry.walls());
        for (int i = 0; i < textures.length; i++) {
            Path file = walls.resolve(String.format("%02d.png", i));
            textures[i] = Textures.load(file);
            flatWalls[i] = Textures.solid(Textures.dominantColor(file));
        }
        floorFlat1 = Textures.solid(0x111122);
        floorFlatOther = Textures.solid(0x221111);
        rebuild();
    }

    /**
     * La carte ({@code L01602a}) : TAB l'ouvre et suspend le jeu ; TAB, le clic ou le feu la
     * referment, une fois relâchés. Vrai tant que la carte occupe la VBL.
     */
    private boolean mapStep() {
        boolean exitKeys = tab || fire || mouseFire;
        if (closingWait) {
            // En sortie, le moteur attend que tout soit relâché avant de reprendre.
            if (exitKeys) {
                return true;
            }
            closingWait = false;
            return false;
        }
        if (!mapOpen) {
            automap.markSeen(player, horizontalFov());
            if (tab) {
                mapOpen = true;
                mapReleased = false;
                automap.open(player);
                SimpleApplication app = (SimpleApplication) getApplication();
                hudView.detach();
                mapView.attach(app.getGuiNode(), app.getAssetManager());
                return true;
            }
            return false;
        }
        if (!exitKeys) {
            mapReleased = true;
        } else if (mapReleased) {
            mapOpen = false;
            closingWait = true;
            SimpleApplication app = (SimpleApplication) getApplication();
            mapView.detach();
            hudView.attach(app.getGuiNode(), app.getAssetManager());
            return true;
        }
        automap.tick(player);
        mapView.upload(automap.image());
        return true;
    }

    /** Champ de vision horizontal de la caméra, en degrés. */
    private double horizontalFov() {
        double vertical = Math.toRadians(Float.parseFloat(System.getProperty("fears.fov", String.valueOf(DEFAULT_FOV))));
        return Math.toDegrees(2 * Math.atan(Math.tan(vertical / 2) * Hud.WIDTH / (2.0 * Hud.HEIGHT)));
    }

    /** Le joueur à la case et à l'angle de départ du niveau. */
    private void spawn() {
        if (player == null) {
            player = new Player(world, data.trig(), level.p1x, level.p1y, level.p1angle);
            player.setBobbing(options.walkEffect());
        } else {
            player.respawn(level.p1x, level.p1y, level.p1angle);
        }
        viewKnown = false;
        lastCell = player.cell();
    }

    /**
     * Refait les maillages d'après la grille vivante. Une porte ne bouge que de quelques
     * unités en quelques trames : tout reconstruire coûte moins d'une milliseconde.
     */
    private void rebuild() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (LevelMesh.Batch batch : new LevelMesh(world).batches()) {
            String key = (batch.flat ? "f" : "w") + batch.texture;
            seen.add(key);
            Geometry geometry = geometries.get(key);
            if (geometry == null) {
                geometry = geometry(getApplication(), batch, textures[batch.texture]);
                geometries.put(key, geometry);
                scene.attachChild(geometry);
            } else {
                geometry.setMesh(mesh(batch));
                if (geometry.getParent() == null) {
                    scene.attachChild(geometry);
                }
            }
        }
        for (var entry : geometries.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                entry.getValue().removeFromParent();
            }
        }
        builtRevision = world.revision();
        applyRemap();
        applyDetail();
    }

    /**
     * Modes de détail (MOTEUR.md, rendu §4.4) : WALLS OFF → murs de texture &lt; 12 en couleur
     * unie, la plus fréquente de la texture, assombrie d'un cran par 1024 au-delà de 7000 ;
     * CEILING OFF → sols et plafonds de texture non nulle en $112 (texture 1) ou $211.
     */
    private void applyDetail() {
        for (var entry : geometries.entrySet()) {
            boolean flat = entry.getKey().startsWith("f");
            int texture = Integer.parseInt(entry.getKey().substring(1));
            Material material = entry.getValue().getMaterial();
            int shown = boss != null && !flat ? boss.remap(texture) : texture;
            if (!flat && wallsOff && texture < 12) {
                material.setTexture("ColorMap", flatWalls[shown]);
                material.setFloat("ShadeStart", 7000f);
                material.setFloat("ShadeStep", 1024f);
                material.setFloat("ShadeAmount", 1f / 15f);
            } else if (flat && ceilingOff && texture != 0) {
                material.setTexture("ColorMap", texture == 1 ? floorFlat1 : floorFlatOther);
            } else {
                material.setTexture("ColorMap", textures[shown]);
                material.setFloat("ShadeStart", flat ? 9000f : 7500f);
                material.setFloat("ShadeStep", 2048f);
                material.setFloat("ShadeAmount", 2f / 15f);
            }
        }
    }

    /** Les réglages d'affichage tels qu'ils sont à la sortie du niveau (rendus au menu, L017CC2). */
    MenuOptions displayOptions() {
        return new MenuOptions(wallsOff, ceilingOff, dithering, screenSize, options.walkEffect(),
                options.difficulty(), options.modemMaze(), options.alienHero());
    }

    /**
     * F1 tramage, F2 murs unis, F3 sols et plafonds unis ({@code L01377a}, anti-rebond commun de
     * 10 VBL) ; F4 à F7 : taille de la vue (20 VBL). Le tramage passe par {@link ChunkyFilter}.
     */
    private void displayKeys() {
        if (keyDelay > 0) {
            keyDelay--;
            return;
        }
        int key = pendingKey;
        if (key < 0) {
            return;
        }
        switch (key) {
            case 1 -> dithering = !dithering;        // $50 : L017cc4
            case 2 -> wallsOff = !wallsOff;          // $51 : bit 1 de L017cc3
            case 3 -> ceilingOff = !ceilingOff;      // $52 : bit 0 de L017cc3
            default -> screenSize = key - 4;
        }
        keyDelay = key <= 3 ? 10 : 20;
        applyDetail();
    }

    /** Niveau 30 : les murs passent par la table de remappage animée du boss ({@code L011308}). */
    private void applyRemap() {
        if (boss != null) {
            applyDetail();
        }
    }

    private static Mesh mesh(LevelMesh.Batch batch) {
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(batch.positions()));
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(batch.texCoords()));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(batch.indices()));
        mesh.updateBound();
        return mesh;
    }

    private Path levelFile(int number) throws IOException {
        String prefix = String.format("niveau%02d_", number);
        try (Stream<Path> files = Files.list(resources.resolve("levels"))) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".lvl"))
                    .findFirst().orElseThrow(() -> new IOException("niveau " + number + " introuvable"));
        }
    }

    private static Geometry geometry(Application app, LevelMesh.Batch batch, Texture2D texture) {
        Mesh mesh = mesh(batch);
        Material material = new Material(app.getAssetManager(), "Fears/Shaders/Level.j3md");
        material.setTexture("ColorMap", texture);
        material.setFloat("ShadeStart", batch.flat ? 9000f : 7500f);
        if (batch.texture == 0) {
            // Texture 0 animée (§4.2, §4.3) : un texel par image de rendu, à 25 images/s.
            float speed = 25f / 64f;
            material.setVector2("Scroll", batch.flat
                    ? new com.jme3.math.Vector2f(0, -speed) : new com.jme3.math.Vector2f(0, speed));
        }
        Geometry geometry = new Geometry((batch.flat ? "sol-plafond " : "murs ") + batch.texture, mesh);
        geometry.setMaterial(material);
        return geometry;
    }

    @Override
    protected void cleanup(Application app) {
    }

    @Override
    protected void onEnable() {
        SimpleApplication app = (SimpleApplication) getApplication();
        app.getRootNode().attachChild(scene);
        app.getViewPort().setBackgroundColor(ColorRGBA.Black);
        app.getInputManager().addRawInputListener(this);
        app.getInputManager().setCursorVisible(false);
        if (ambience != null) {
            ambience.start();
        }
        postProcessor = new com.jme3.post.FilterPostProcessor(app.getAssetManager());
        postProcessor.addFilter(chunky);
        app.getViewPort().addProcessor(postProcessor);
        if (hudView != null) {
            hudView.attach(app.getGuiNode(), app.getAssetManager());
            frame(app.getCamera());
        }
        placeCamera();
    }

    @Override
    protected void onDisable() {
        SimpleApplication app = (SimpleApplication) getApplication();
        scene.removeFromParent();
        if (hudView != null) {
            hudView.detach();
        }
        if (mapView != null) {
            mapView.detach();
            mapOpen = false;
        }
        app.getCamera().setViewPort(0, 1, 0, 1);
        app.getInputManager().removeRawInputListener(this);
        if (sounds != null) {
            sounds.stopAll();
        }
        if (ambience != null) {
            ambience.stop();
        }
        if (postProcessor != null) {
            app.getViewPort().removeProcessor(postProcessor);
            postProcessor = null;
        }
    }

    @Override
    public void update(float tpf) {
        if (player == null) {
            return;
        }
        Camera cam = getApplication().getCamera();
        accumulated += tpf;
        int steps = 0;
        while (accumulated >= VBL) {
            accumulated -= VBL;
            if (steps++ < MAX_CATCH_UP) {
                vbl();
            }
        }
        frame(cam);
        if (mapOpen) {
            mapView.layout(cam.getWidth(), cam.getHeight());
        }
        // L'affichage interpole entre les deux dernières VBL : la logique reste à 50 Hz fixes.
        double t = Math.min(1.0, accumulated / VBL);
        placeCamera(t);
        if (alienView != null) {
            alienView.update(t);
        }
        if (hudChanged) {
            hudView.upload(hud.image());
            hudChanged = false;
        }
    }

    /** État de la caméra à la VBL précédente : x, y (unités), hauteur des yeux, angle (degrés). */
    private final double[] previousView = new double[4];
    private final double[] currentView = new double[4];
    private boolean viewKnown;

    private void snapshotView(double[] view) {
        view[0] = player.worldX();
        view[1] = player.worldY();
        view[2] = player.eyeHeight();
        view[3] = player.angle() * 360.0 / Trig.FULL_TURN;
    }

    /** Les sauts voulus (réapparition) ne sont pas lissés. */
    private void resetView() {
        snapshotView(currentView);
        System.arraycopy(currentView, 0, previousView, 0, 4);
        viewKnown = true;
    }

    private void vbl() {
        if (player != null) {
            if (!viewKnown) {
                resetView();
            }
            System.arraycopy(currentView, 0, previousView, 0, 4);
        }
        vblLogic();
        if (player != null) {
            if (viewKnown) {
                snapshotView(currentView);
            } else {
                resetView();        // réapparition pendant la VBL : pas de glissé
            }
        }
    }

    private void vblLogic() {
        if (ambience != null) {
            ambience.tick();
        }
        displayKeys();
        if (finished) {
            return;
        }
        if (mapStep()) {
            return;
        }
        if (dying >= 0) {
            deathStep();
            return;
        }
        playScript();
        levelTime++;
        int move = (forward || mouseForward) ? 1 : back ? -1 : 0;
        int strafe = strafeRight ? 1 : strafeLeft ? -1 : 0;
        int turn = (right ? Player.TURN : 0) - (left ? Player.TURN : 0);
        // Souris : 1/6° par compte, soit deux octets de table ; l'angle reste multiple de 4.
        int mouse = (int) (mouseTurn / 2);
        mouseTurn -= mouse * 2;
        turn += mouse * 4;
        player.tick(move, strafe, run, turn);
        if (player.cell() != lastCell) {
            lastCell = player.cell();
            pickUp(lastCell);
        }
        if (stats.tick(world.acid(player.cell()))) {
            died();
        }
        int sound = weapons.tick(fire || mouseFire);
        if (sound != 0) {
            sounds.play(sound);
        }
        int fired = weapons.takeFired();
        if (fired >= 0) {
            aliens.playerFired(fired);
        }
        if (boss != null) {
            boss.tick();
            if (boss.takeRemapChanged()) {
                applyRemap();
            }
        }
        int damage = aliens.tick();
        sounds.tick();
        if (damage > 0) {
            sounds.play(Aliens.SOUND_HIT);
            hud.flash();
            if (stats.damage(damage)) {
                died();
            }
        }
        hud.update(stats, weapons);
        hudChanged = true;
        doors.tick(activate);
        if (world.revision() != builtRevision) {
            rebuild();
        }
        if (doors.exitReached()) {
            stats.setWeaponInHand(weapons.weapon());
            sounds.doorStopped();
            fears.engine.LevelResult result = new fears.engine.LevelResult(levelNumber, levelTime,
                    aliensTotal, aliensTotal - aliens.alive(), secretsTotal, secretsTotal - world.secretsLeft(),
                    goldTotal, goldTotal - world.goldLeft());
            getApplication().enqueue(() -> onFinished.accept(result));
            finished = true;
        }
    }

    /**
     * {@code avancer:150,gauche:30,espace:1,attendre:60} : chaque étape tient une commande
     * pendant N VBL (avancer, reculer, gauche, droite, espace, courir, attendre).
     */
    private static java.util.ArrayDeque<String[]> parseScript(String text) {
        java.util.ArrayDeque<String[]> steps = new java.util.ArrayDeque<>();
        if (text != null) {
            for (String step : text.split(",")) {
                String[] parts = step.trim().split(":");
                steps.add(new String[]{parts[0], parts.length > 1 ? parts[1] : "1"});
            }
        }
        return steps;
    }

    private void playScript() {
        if (script.isEmpty()) {
            return;
        }
        String[] step = script.peek();
        forward = "avancer".equals(step[0]);
        back = "reculer".equals(step[0]);
        left = "gauche".equals(step[0]);
        right = "droite".equals(step[0]);
        activate = "espace".equals(step[0]);
        fire = "tirer".equals(step[0]);
        tab = "carte".equals(step[0]);
        pendingKey = step[0].matches("f[1-7]") ? step[0].charAt(1) - '0' : -1;
        if ("mourir".equals(step[0]) && dying < 0 && scriptFrames == 0 && stats.damage(999)) {
            died();
        }
        if (step[0].startsWith("arme")) {
            weapons.select(Integer.parseInt(step[0].substring(4)) - 1);
        }
        run = "courir".equals(step[0]);
        forward |= run;
        if (++scriptFrames >= Integer.parseInt(step[1])) {
            script.poll();
            scriptFrames = 0;
            if (script.isEmpty()) {
                forward = back = left = right = activate = run = fire = tab = false;
            }
        }
    }

    /**
     * Mort (MOTEUR.md « acteurs » §4) : s'il reste des vies, retour au départ du niveau avec
     * santé et balles à 99 ; les aliens restent où ils sont. Sinon, fin de partie.
     * [à porter] message de vies, fondu rouge, attente du bouton ; écran de game over.
     */
    private void died() {
        dying = 0;
        fireReleased = false;
        sounds.stopAll();
    }

    /**
     * Fondu rouge (64 VBL [S]), puis, s'il reste des vies, le message et l'attente du bouton ;
     * ensuite retour au départ (L01779a) : santé et balles à 99, projectiles effacés, aliens,
     * objets et portes inchangés, pistolet repris si l'arme était le poing ou la tronçonneuse.
     * Sans vie : fin de partie (game over).
     */
    private void deathStep() {
        dying++;
        int red = Math.min(64, dying);
        boolean over = stats.gameOver();
        hud.death(red, dying >= 64 && !over ? stats.get(Stats.LIVES) : -1);
        hud.update(stats, weapons);
        hudChanged = true;
        if (dying < 64) {
            return;
        }
        if (over) {
            System.out.println("Plus de vie : fin de partie.");
            finished = true;
            getApplication().enqueue(onGameOver::run);
            return;
        }
        boolean pressed = fire || mouseFire;
        if (!pressed) {
            fireReleased = true;
            return;
        }
        if (!fireReleased) {
            return;
        }
        dying = -1;
        hud.death(0, -1);
        stats.revive();
        aliens.clearShots();
        if (weapons.weapon() == Weapons.FIST || weapons.weapon() == Weapons.CHAINSAW) {
            weapons.select(stats.get(Stats.ROLLING) != 0 ? Weapons.ROLLING : Weapons.PISTOL);
        }
        spawn();
    }

    /** {@code L013866} : l'objet de la case où l'on vient d'entrer. */
    private void pickUp(int cell) {
        int type = world.item(cell);
        if (type == 0) {
            return;
        }
        int sound = stats.pickUp(type, taken);
        if (sound != 0) {
            sounds.play(sound);
        }
        if (taken[0]) {
            weapons.select(stats.takePickedWeapon());
            world.removeItem(cell);
            objects.remove(cell);
            System.out.printf("Ramasse %s : sante %d, vies %d, munitions %d/%d/%d, bouclier %d%n",
                    Level.OBJECT_NAMES[type], stats.get(Stats.HEALTH), stats.get(Stats.LIVES),
                    stats.get(Stats.AMMO_1), stats.get(Stats.AMMO_2), stats.get(Stats.AMMO_3),
                    stats.get(Stats.SHIELD));
        }
    }

    /**
     * L'écran d'origine fait 320×256 : la vue 3D et le HUD occupent ce même rectangle 5:4,
     * agrandi au mieux et centré dans la fenêtre.
     */
    private void frame(Camera cam) {
        int w = cam.getWidth();
        int h = cam.getHeight();
        float[] r = hudView.rectangle(w, h);
        // Taille de vue (table 0x10948) : 92, 76, 60 ou 40 colonnes ; fenêtre verticale en rangées
        // relatives à l'horizon (ligne 64) : −64..63, −54..63, −50..55, −45..50.
        int[] columns = {92, 76, 60, 40};
        int[][] rows = {{-64, 63}, {-54, 63}, {-50, 55}, {-45, 50}};
        int size = Math.max(0, Math.min(3, screenSize));
        float widthFraction = columns[size] / 92f;
        float top = (64 + rows[size][0]) / 128f;
        float bottom = (64 + rows[size][1] + 1) / 128f;
        float vx = r[0] + r[2] * (1 - widthFraction) / 2;
        float vw = r[2] * widthFraction;
        float vyTop = r[1] + r[3] * (1 - top);
        float vyBottom = r[1] + r[3] * (1 - bottom);
        cam.setViewPort(vx / w, (vx + vw) / w, vyBottom / h, vyTop / h);
        // Même échelle qu'en plein écran : la vue réduite coupe l'image, elle ne la rétrécit pas.
        float fov = Float.parseFloat(System.getProperty("fears.fov", String.valueOf(DEFAULT_FOV)));
        float halfHeight = (float) Math.tan(Math.toRadians(fov / 2));
        float near = 0.02f;
        float scale = near * halfHeight / (r[3] / 2);
        float horizon = r[1] + r[3] / 2;
        float centerX = r[0] + r[2] / 2;
        cam.setFrustum(near, 12f, (vx - centerX) * scale, (vx + vw - centerX) * scale,
                (vyTop - horizon) * scale, (vyBottom - horizon) * scale);
        chunky.setEnabled(dithering || CHUNKY_ALWAYS);
        chunky.configure(vw, vyTop - vyBottom, r[2] / Hud.WIDTH * 2, r[3] / Hud.HEIGHT, dithering);
        hudView.layout(w, h);
    }

    private void placeCamera() {
        placeCamera(1.0);
    }

    private void placeCamera(double t) {
        if (player == null) {
            return;
        }
        if (!viewKnown) {
            resetView();
        }
        Camera cam = getApplication().getCamera();
        double[] p = previousView;
        double[] c = currentView;
        float x = (float) ((p[0] + (c[0] - p[0]) * t) / 64.0);
        float z = (float) ((p[1] + (c[1] - p[1]) * t) / 64.0);
        float y = (float) (-(p[2] + (c[2] - p[2]) * t) * LevelMesh.HEIGHT_UNIT);
        cam.setLocation(new Vector3f(x, y, z));
        // Angle par le plus court chemin.
        double turn = ((c[3] - p[3] + 540) % 360) - 180;
        double a = Math.toRadians(p[3] + turn * t);
        cam.lookAtDirection(new Vector3f((float) -Math.sin(a), 0, (float) Math.cos(a)), Vector3f.UNIT_Y);
    }

    // ------------------------------------------------------------------ entrées

    @Override
    public void onKeyEvent(KeyInputEvent event) {
        if (event.isRepeating()) {
            return;
        }
        boolean pressed = event.isPressed();
        switch (event.getKeyCode()) {
            case KeyInput.KEY_UP, KeyInput.KEY_W, KeyInput.KEY_Z -> forward = pressed;
            case KeyInput.KEY_DOWN, KeyInput.KEY_S -> back = pressed;
            case KeyInput.KEY_LEFT -> left = pressed;
            case KeyInput.KEY_RIGHT -> right = pressed;
            case KeyInput.KEY_A, KeyInput.KEY_Q -> strafeLeft = pressed;
            case KeyInput.KEY_D -> strafeRight = pressed;
            case KeyInput.KEY_LSHIFT, KeyInput.KEY_RSHIFT -> run = pressed;
            case KeyInput.KEY_SPACE -> activate = pressed;
            case KeyInput.KEY_TAB -> tab = pressed;
            case KeyInput.KEY_F1, KeyInput.KEY_F2, KeyInput.KEY_F3, KeyInput.KEY_F4, KeyInput.KEY_F5,
                 KeyInput.KEY_F6, KeyInput.KEY_F7 -> {
                int number = event.getKeyCode() - KeyInput.KEY_F1 + 1;
                if (pressed) {
                    pendingKey = number;
                } else if (pendingKey == number) {
                    pendingKey = -1;
                }
            }
            case KeyInput.KEY_LCONTROL, KeyInput.KEY_RCONTROL -> fire = pressed;
            case KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3, KeyInput.KEY_4, KeyInput.KEY_5,
                 KeyInput.KEY_6 -> {
                if (pressed) {
                    weapons.select(event.getKeyCode() - KeyInput.KEY_1);
                }
            }
            case KeyInput.KEY_ESCAPE -> {
                if (pressed) {
                    getApplication().enqueue(onQuit::run);
                }
            }
            default -> {
                // Les autres touches viendront avec les armes, les portes et la carte.
            }
        }
    }

    @Override
    public void onMouseMotionEvent(MouseMotionEvent event) {
        mouseTurn += event.getDX();
    }

    @Override
    public void onMouseButtonEvent(MouseButtonEvent event) {
        if (event.getButtonIndex() == MouseInput.BUTTON_RIGHT) {
            mouseForward = event.isPressed();
        } else if (event.getButtonIndex() == MouseInput.BUTTON_LEFT) {
            mouseFire = event.isPressed();
        }
    }

    @Override
    public void beginInput() {
    }

    @Override
    public void endInput() {
    }

    @Override
    public void onJoyAxisEvent(JoyAxisEvent event) {
    }

    @Override
    public void onJoyButtonEvent(JoyButtonEvent event) {
    }

    @Override
    public void onTouchEvent(TouchEvent event) {
    }
}
