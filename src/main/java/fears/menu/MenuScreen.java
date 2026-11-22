package fears.menu;

/**
 * L'écran du menu, sans dépendance graphique : il avance d'une VBL (1/50 s) à chaque appel
 * de {@link #tick()} et compose une image de 640×256 pixels hires dans {@link #frame()}.
 *
 * <p>Déroulement d'origine ({@code d1_000400}, boucle {@code L010092}) :
 * <ol>
 *   <li>fondu d'entrée ({@code L010238}) : seul le plan du logo ondulant est affiché, sa
 *       couleur monte de noir à $4E4040 en 64 VBL (sur 71) ;</li>
 *   <li>menu ({@code L0104b0}) : cinq plans hires — logo, texte sur trois plans, bande de
 *       surbrillance — et le pointeur en sprite ;</li>
 *   <li>à la sortie, fondu inverse ({@code L01027a}, ou {@code L0102d0} qui coupe aussi la
 *       musique avant le jeu).</li>
 * </ol>
 *
 * <p>Le pointeur se pilote à la souris ou au joystick ; la ligne sous le pointeur est
 * surlignée (le plan 5 rempli sur 21 lignes, {@code L0107ce}), le bouton gauche ou le feu
 * appelle la routine attachée à cette ligne ({@code L010976}).
 */
public final class MenuScreen implements Scene {

    public static final int WIDTH = MenuText.WIDTH;
    public static final int HEIGHT = MenuText.HEIGHT;

    /** Les pages : {@code L0109dc}, couples (routines, texte), indexés par {@code L0109d8}. */
    static final int PAGES = 0x109dc;
    static final int PAGE_MAIN = 0;
    static final int PAGE_OPTIONS = 1;

    /** Lignes de menu : la ligne survolée est y / 22, bornée à 10 ({@code L0107ce}). */
    static final int LAST_LINE = 10;
    /** Hauteur de la bande de surbrillance (BLTSIZE $568 : 21 lignes de 40 mots). */
    static final int HIGHLIGHT_ROWS = 21;


    /** Durée d'un fondu : dbra sur $46, et la couleur n'évolue que les 64 premières fois. */
    static final int FADE_FRAMES = 0x47;
    static final int FADE_STEPS = 0x40;
    /** Incréments de la couleur du logo par pas, en 8.6 ({@code L0103da}) : $4E, $40, $40. */
    static final int[] FADE_INCREMENT = {0x4E, 0x40, 0x40};

    /** Attente après un clic ({@code L0109ae}) : dix images. */
    static final int CLICK_DELAY = 10;

    // Routines des lignes, adresses de d1_000400.
    static final int GO_START_GAME = 0x10c9c;
    static final int GO_LOAD_GAME = 0x10be4;
    static final int GO_OPTIONS = 0x10cae;
    static final int GO_EDIT_MAZE = 0x10c68;
    static final int GO_NULL_MODEM = 0x10bf6;
    static final int GO_CREDITS = 0x10c8a;
    static final int TOGGLE_WALLS = 0x10cf8;
    static final int TOGGLE_CEILING = 0x10d24;
    static final int TOGGLE_DITHERING = 0x10d50;
    static final int CYCLE_SCREEN_SIZE = 0x10cbe;
    static final int TOGGLE_WALK = 0x10d74;
    static final int CYCLE_DIFFICULTY = 0x10d98;
    static final int CYCLE_MODEM_MAZE = 0x10c08;
    static final int TOGGLE_HERO = 0x10c36;
    static final int BACK_TO_MENU = 0x10c7a;

    // Variables d'options (mots), et emplacements des valeurs dans les chaînes.
    static final int VAR_WALLS_CEILING = 0x11cb8;
    static final int VAR_DITHERING = 0x11cba;
    static final int VAR_WALK = 0x11cbc;
    static final int VAR_SCREEN_SIZE = 0x11cbe;
    static final int VAR_DIFFICULTY = 0x11cc0;
    static final int VAR_MODEM_MAZE = 0x11cc4;
    static final int VAR_HERO = 0x11cc6;
    static final int TEXT_WALLS = 0x10a6c;
    static final int TEXT_CEILING = 0x10a78;
    static final int TEXT_DITHERING = 0x10a86;
    static final int TEXT_SCREEN_SIZE = 0x10a96;
    static final int TEXT_WALK = 0x10aa6;
    static final int TEXT_DIFFICULTY = 0x10ab5;
    static final int TEXT_MODEM_MAZE = 0x10ac9;
    static final int TEXT_HERO = 0x10ad1;
    static final int SCREEN_SIZE_NAMES = 0x10ce8;
    static final int DIFFICULTY_NAMES = 0x10dd2;
    static final int HERO_NAMES = 0x10c5c;
    /** «   ON » et « OFF » tels que le code les écrit (move.l #$20204f4e / #$204f4646). */
    static final int ON = 0x20204F4E;
    static final int OFF = 0x204F4646;

    private enum Phase { FADE_IN, MENU, FADE_OUT }

    private final MenuAssets assets;
    private final LinkedImage program;
    private final MenuText text;
    private final BombBackground background;
    private final int[] frame = new int[WIDTH * HEIGHT];

    private Phase phase = Phase.FADE_IN;
    private int phaseFrame;
    /** Accumulateurs de couleur du logo ({@code L0103e0}). */
    private final int[] fadeColor = new int[3];

    private int page = PAGE_MAIN;
    private final Pointer pointer = new Pointer();
    private boolean fire;
    private int clickDelay;

    private MenuAction leaving;
    private MenuAction pendingAction;
    private boolean musicStopping;

    public MenuScreen(MenuAssets assets) {
        this.assets = assets;
        this.program = assets.program;
        this.text = new MenuText(assets);
        this.background = new BombBackground(assets);
        restart();
    }

    /** Reprend au fondu d'entrée, sur la page principale ({@code L010092}). */
    public void restart() {
        phase = Phase.FADE_IN;
        phaseFrame = 0;
        java.util.Arrays.fill(fadeColor, 0);
        leaving = null;
        musicStopping = false;
        clickDelay = 0;
    }

    // ------------------------------------------------------------------ entrées

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    @Override
    public float pixelAspect() {
        return 2f;
    }

    /** Déplacement de la souris, en pixels hires (x) et en lignes (y). */
    @Override
    public void movePointer(double dx, double dy) {
        pointer.move(dx, dy);
    }

    /** État du joystick (ou des flèches du clavier). */
    @Override
    public void setJoystick(boolean up, boolean down, boolean left, boolean right) {
        pointer.joystick(up, down, left, right);
    }

    /** Bouton gauche de la souris ou feu du joystick. */
    @Override
    public void setFire(boolean pressed) {
        this.fire = pressed;
    }

    /** Le choix fait au menu, une fois le fondu de sortie achevé ; {@code null} sinon. */
    public MenuAction takeAction() {
        MenuAction action = pendingAction;
        pendingAction = null;
        return action;
    }

    /** Vrai pendant le fondu qui précède le jeu : la musique doit s'éteindre ({@code L018128}). */
    public boolean musicStopping() {
        return musicStopping;
    }

    /** Progression du fondu de sortie, de 0 à 1. */
    public double fadeOutProgress() {
        return phase == Phase.FADE_OUT ? Math.min(1.0, phaseFrame / (double) FADE_FRAMES) : 0;
    }

    public MenuOptions options() {
        int wallsCeiling = program.u16(VAR_WALLS_CEILING);
        return new MenuOptions((wallsCeiling & 2) != 0, (wallsCeiling & 1) != 0,
                program.u16(VAR_DITHERING) != 0, program.u16(VAR_SCREEN_SIZE),
                program.u16(VAR_WALK) != 0, program.u16(VAR_DIFFICULTY),
                program.u16(VAR_MODEM_MAZE), program.u16(VAR_HERO) != 0);
    }

    /** L'image courante, 640×256 en 0xRRGGBB. */
    @Override
    public int[] frame() {
        return frame;
    }

    public int page() {
        return page;
    }

    /** La ligne de menu sous le pointeur. */
    public int selectedLine() {
        return Math.min(pointer.y() / MenuText.LINE_HEIGHT, LAST_LINE);
    }

    public int pointerX() {
        return pointer.x();
    }

    public int pointerY() {
        return pointer.y();
    }

    /** Place le pointeur, pour les tests. */
    void pointTo(int x, int y) {
        pointer.set(x, y);
    }

    // ------------------------------------------------------------------ une VBL

    @Override
    public void tick() {
        background.tick();
        switch (phase) {
            case FADE_IN -> {
                fadeStep(+1);
                if (++phaseFrame >= FADE_FRAMES) {
                    enterMenu();
                }
            }
            case MENU -> menuTick();
            case FADE_OUT -> {
                fadeStep(-1);
                if (++phaseFrame >= FADE_FRAMES) {
                    pendingAction = leaving;
                    restart();
                }
            }
        }
        compose();
    }

    /** {@code L010336} / {@code L010360} : un pas de fondu, limité aux 64 premiers. */
    private void fadeStep(int direction) {
        if (phaseFrame + 1 > FADE_STEPS) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            fadeColor[i] += direction * FADE_INCREMENT[i];
        }
    }

    /** {@code L0104b0} : la page principale, le pointeur gardé où il était. */
    private void enterMenu() {
        phase = Phase.MENU;
        phaseFrame = 0;
        showPage(PAGE_MAIN);
    }

    private void showPage(int number) {
        page = number;
        text.drawPage(program.u32(PAGES + 8 * page + 4));
    }

    private void menuTick() {
        pointer.tick();
        if (clickDelay > 0) {
            clickDelay--;
            if (clickDelay == 0 && leaving != null) {
                startFadeOut();
            }
            return;
        }
        if (fire) {
            click();
        }
    }

    /** {@code L01098c} : appelle la routine de la ligne survolée, s'il y en a une. */
    private void click() {
        int routines = program.u32(PAGES + 8 * page);
        int routine = program.u32(routines + 4 * selectedLine());
        if (routine == 0) {
            return;
        }
        run(routine);
        clickDelay = CLICK_DELAY;
    }

    private void run(int routine) {
        switch (routine) {
            case GO_START_GAME -> leave(MenuAction.START_GAME);
            case GO_LOAD_GAME -> leave(MenuAction.LOAD_GAME);
            case GO_EDIT_MAZE -> leave(MenuAction.EDIT_MAZE);
            case GO_NULL_MODEM -> leave(MenuAction.NULL_MODEM);
            case GO_CREDITS -> leave(MenuAction.CREDITS);
            case GO_OPTIONS -> showPage(PAGE_OPTIONS);
            case BACK_TO_MENU -> showPage(PAGE_MAIN);
            case TOGGLE_WALLS -> {
                program.put32(TEXT_WALLS, ON);
                program.put16(VAR_WALLS_CEILING, program.u16(VAR_WALLS_CEILING) ^ 2);
                if ((program.u16(VAR_WALLS_CEILING) & 2) != 0) {
                    program.put32(TEXT_WALLS, OFF);
                }
                redraw();
            }
            case TOGGLE_CEILING -> {
                program.put32(TEXT_CEILING, ON);
                program.put16(VAR_WALLS_CEILING, program.u16(VAR_WALLS_CEILING) ^ 1);
                if ((program.u16(VAR_WALLS_CEILING) & 1) != 0) {
                    program.put32(TEXT_CEILING, OFF);
                }
                redraw();
            }
            case TOGGLE_DITHERING -> {
                program.put32(TEXT_DITHERING, OFF);
                program.put16(VAR_DITHERING, program.u16(VAR_DITHERING) ^ 1);
                if (program.u16(VAR_DITHERING) != 0) {
                    program.put32(TEXT_DITHERING, ON);
                }
                redraw();
            }
            case TOGGLE_WALK -> {
                program.put32(TEXT_WALK, ON);
                program.put16(VAR_WALK, program.u16(VAR_WALK) ^ 1);
                if (program.u16(VAR_WALK) == 0) {
                    program.put32(TEXT_WALK, OFF);
                }
                redraw();
            }
            case CYCLE_SCREEN_SIZE -> {
                int size = (program.u16(VAR_SCREEN_SIZE) + 1) & 3;
                program.put16(VAR_SCREEN_SIZE, size);
                program.copy(SCREEN_SIZE_NAMES + 4 * size, TEXT_SCREEN_SIZE, 4);
                redraw();
            }
            case CYCLE_DIFFICULTY -> {
                int level = (program.u16(VAR_DIFFICULTY) + 1) % 3;
                program.put16(VAR_DIFFICULTY, level);
                program.copy(DIFFICULTY_NAMES + 8 * level, TEXT_DIFFICULTY, 8);
                redraw();
            }
            case CYCLE_MODEM_MAZE -> {
                int maze = (program.u16(VAR_MODEM_MAZE) + 1) % 3;
                program.put16(VAR_MODEM_MAZE, maze);
                program.put8(TEXT_MODEM_MAZE, '1' + maze);
                redraw();
            }
            case TOGGLE_HERO -> {
                int hero = program.u16(VAR_HERO) ^ 1;
                program.put16(VAR_HERO, hero);
                program.copy(HERO_NAMES + (hero != 0 ? 6 : 0), TEXT_HERO, 6);
                redraw();
            }
            default -> System.err.println(String.format(
                    "Menu : routine $%06x inconnue sur la ligne %d", routine, selectedLine()));
        }
    }

    private void redraw() {
        showPage(page);
    }

    /** Le drapeau de sortie ({@code L01089c}) : on quitte après l'attente du clic. */
    private void leave(MenuAction action) {
        leaving = action;
    }

    private void startFadeOut() {
        phase = Phase.FADE_OUT;
        phaseFrame = 0;
        // Le fondu d'entrée a laissé la couleur à son maximum : on repart de là.
        for (int i = 0; i < 3; i++) {
            fadeColor[i] = FADE_STEPS * FADE_INCREMENT[i];
        }
        musicStopping = leaving != MenuAction.CREDITS;
    }

    // ------------------------------------------------------------------ composition

    private void compose() {
        int[] palette = assets.palette;
        if (phase != Phase.MENU) {
            // Liste copper 0x29648 : un seul plan, couleur 1 en fondu.
            int logo = fadedLogoColor();
            for (int y = 0; y < HEIGHT; y++) {
                int row = y * WIDTH;
                for (int x = 0; x < WIDTH; x++) {
                    frame[row + x] = background.at(x, y) ? logo : palette[0];
                }
            }
            return;
        }
        int selected = selectedLine();
        int bandTop = selected * MenuText.LINE_HEIGHT;
        int bandBottom = bandTop + HIGHLIGHT_ROWS;
        for (int y = 0; y < HEIGHT; y++) {
            int row = y * WIDTH;
            int highlight = (y >= bandTop && y < bandBottom) ? 16 : 0;
            for (int x = 0; x < WIDTH; x++) {
                int index = (background.at(x, y) ? 1 : 0) | (text.at(x, y) << 1) | highlight;
                frame[row + x] = palette[index];
            }
        }
        pointer.draw(frame, WIDTH, HEIGHT, assets);
    }

    /** Couleur du logo pendant un fondu : la liste copper garde 8 bits par composante. */
    private int fadedLogoColor() {
        int r = (fadeColor[0] >> 6) & 0xFF;
        int g = (fadeColor[1] >> 6) & 0xFF;
        int b = (fadeColor[2] >> 6) & 0xFF;
        return (r << 16) | (g << 8) | b;
    }
}
