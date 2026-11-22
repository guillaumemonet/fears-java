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
import fears.menu.FrontEnd;
import fears.menu.MenuAction;
import fears.menu.Scene;

/**
 * Les écrans d'avant le jeu dans la fenêtre : avance {@link FrontEnd} à 50 Hz et affiche
 * l'image de l'écran en cours, à ses proportions (640×512 entrelacé pour l'intro de
 * démarrage, 640×256 ensuite).
 *
 * <p>Souris : le pointeur suit ses déplacements, à l'échelle de l'image affichée ; bouton
 * gauche pour choisir. Clavier : les flèches tiennent lieu de joystick, Entrée, Espace ou
 * Ctrl de bouton de tir.
 */
final class FrontEndState extends BaseAppState implements RawInputListener {

    /** Une VBL PAL. */
    private static final float VBL = 1f / 50f;
    /** Rattrapage maximal après un à-coup, en VBL. */
    private static final int MAX_CATCH_UP = 5;

    private final FrontEnd frontEnd;
    private final MusicPlayer music;
    private final java.util.function.Consumer<MenuAction> onAction;
    private ScreenView view;
    private float accumulated;

    private boolean keyUp;
    private boolean keyDown;
    private boolean keyLeft;
    private boolean keyRight;
    private boolean mouseFire;
    private boolean keyFire;

    /**
     * @param onAction reçoit les choix du menu qui mènent hors des écrans d'avant le jeu
     */
    FrontEndState(FrontEnd frontEnd, MusicPlayer music, java.util.function.Consumer<MenuAction> onAction) {
        this.frontEnd = frontEnd;
        this.music = music;
        this.onAction = onAction;
    }

    @Override
    protected void initialize(Application app) {
        frontEnd.tick();
        showScene();
    }

    @Override
    protected void cleanup(Application app) {
    }

    @Override
    protected void onEnable() {
        SimpleApplication app = (SimpleApplication) getApplication();
        view.attach(app.getGuiNode(), app.getAssetManager());
        app.getInputManager().addRawInputListener(this);
        app.getInputManager().setCursorVisible(false);
    }

    @Override
    protected void onDisable() {
        SimpleApplication app = (SimpleApplication) getApplication();
        view.detach();
        app.getInputManager().removeRawInputListener(this);
        music.stop();
        mouseFire = false;
        keyFire = false;
    }

    @Override
    public void update(float tpf) {
        accumulated += tpf;
        int steps = 0;
        while (accumulated >= VBL) {
            accumulated -= VBL;
            if (steps++ < MAX_CATCH_UP) {
                vbl();
            }
        }
        if (steps > 0) {
            showScene();
        }
        view.layout(getApplication().getCamera().getWidth(), getApplication().getCamera().getHeight());
    }

    /** Affiche l'image de l'écran en cours, en changeant de texture si sa taille a changé. */
    private void showScene() {
        Scene scene = frontEnd.scene();
        if (view == null || !view.fits(scene.width(), scene.height(), scene.pixelAspect())) {
            SimpleApplication app = (SimpleApplication) getApplication();
            if (view != null) {
                view.detach();
            }
            view = new ScreenView(scene.width(), scene.height(), scene.pixelAspect());
            if (isEnabled() && isInitialized()) {
                view.attach(app.getGuiNode(), app.getAssetManager());
            }
        }
        view.upload(scene.frame());
    }

    private void vbl() {
        Scene scene = frontEnd.scene();
        scene.setJoystick(keyUp, keyDown, keyLeft, keyRight);
        scene.setFire(mouseFire || keyFire);
        frontEnd.tick();
        if (frontEnd.musicPlaying()) {
            music.play();
        }
        music.setVolume(frontEnd.musicVolume());
        MenuAction action = frontEnd.takeAction();
        if (action != null) {
            chosen(action);
        }
    }

    private void chosen(MenuAction action) {
        switch (action) {
            case CREDITS -> {
                // La présentation tient lieu de crédits : FrontEnd y est déjà passé.
            }
            case START_GAME -> getApplication().enqueue(() -> onAction.accept(action));
            default -> System.out.println(action + " : pas encore porte, retour a la presentation.");
        }
    }

    // ------------------------------------------------------------------ entrées brutes

    @Override
    public void onMouseMotionEvent(MouseMotionEvent event) {
        // Une unité de l'écran d'origine par pixel de l'image affichée ; jME compte y vers le haut.
        frontEnd.scene().movePointer(event.getDX() / view.scaleX(), -event.getDY() / view.scaleY());
    }

    @Override
    public void onMouseButtonEvent(MouseButtonEvent event) {
        if (event.getButtonIndex() == MouseInput.BUTTON_LEFT) {
            mouseFire = event.isPressed();
        }
    }

    @Override
    public void onKeyEvent(KeyInputEvent event) {
        if (event.isRepeating()) {
            return;
        }
        boolean pressed = event.isPressed();
        switch (event.getKeyCode()) {
            case KeyInput.KEY_UP -> keyUp = pressed;
            case KeyInput.KEY_DOWN -> keyDown = pressed;
            case KeyInput.KEY_LEFT -> keyLeft = pressed;
            case KeyInput.KEY_ESCAPE -> {
                if (pressed) {
                    getApplication().stop();
                }
            }
            case KeyInput.KEY_RIGHT -> keyRight = pressed;
            case KeyInput.KEY_RETURN, KeyInput.KEY_NUMPADENTER, KeyInput.KEY_SPACE,
                 KeyInput.KEY_LCONTROL, KeyInput.KEY_RCONTROL -> keyFire = pressed;
            default -> {
                // Les autres touches ne servent pas au menu.
            }
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
