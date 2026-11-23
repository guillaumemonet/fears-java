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
        Path walls = resources.resolve("walls").resolve(entry.walls());
        for (int i = 0; i < textures.length; i++) {
            textures[i] = Textures.load(walls.resolve(String.format("%02d.png", i)));
        }
        rebuild();
    }

    /** Le joueur à la case et à l'angle de départ du niveau. */
    private void spawn() {
        if (player == null) {
            player = new Player(world, data.trig(), level.p1x, level.p1y, level.p1angle);
            player.setBobbing(options.walkEffect());
        } else {
            player.respawn(level.p1x, level.p1y, level.p1angle);
        }
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
    }

    /** Niveau 30 : les murs passent par la table de remappage animée du boss ({@code L011308}). */
    private void applyRemap() {
        if (boss == null) {
            return;
        }
        for (var entry : geometries.entrySet()) {
            if (entry.getKey().startsWith("w")) {
                int texture = Integer.parseInt(entry.getKey().substring(1));
                entry.getValue().getMaterial().setTexture("ColorMap", textures[boss.remap(texture)]);
            }
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
        app.getCamera().setViewPort(0, 1, 0, 1);
        app.getInputManager().removeRawInputListener(this);
        if (sounds != null) {
            sounds.stopAll();
        }
        if (ambience != null) {
            ambience.stop();
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
        placeCamera();
        if (alienView != null) {
            alienView.update();
        }
        if (hudChanged) {
            hudView.upload(hud.image());
            hudChanged = false;
        }
    }

    private void vbl() {
        if (ambience != null) {
            ambience.tick();
        }
        if (finished) {
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
                forward = back = left = right = activate = run = fire = false;
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
        cam.setViewPort(r[0] / w, (r[0] + r[2]) / w, r[1] / h, (r[1] + r[3]) / h);
        float fov = Float.parseFloat(System.getProperty("fears.fov", String.valueOf(DEFAULT_FOV)));
        cam.setFrustumPerspective(fov, r[2] / r[3], 0.02f, 12f);
        hudView.layout(w, h);
    }

    private void placeCamera() {
        if (player == null) {
            return;
        }
        Camera cam = getApplication().getCamera();
        float x = (float) (player.worldX() / 64.0);
        float z = (float) (player.worldY() / 64.0);
        float y = (float) (-player.eyeHeight() * LevelMesh.HEIGHT_UNIT);
        cam.setLocation(new Vector3f(x, y, z));
        double a = Math.toRadians(player.angle() * 360.0 / Trig.FULL_TURN);
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
