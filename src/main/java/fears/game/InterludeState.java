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
import fears.menu.Scene;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

/**
 * Une suite d'écrans fixes joués l'un après l'autre à 50 Hz — entre deux niveaux : les
 * statistiques puis le château. Chaque écran dit lui-même quand il est fini.
 */
final class InterludeState extends BaseAppState implements RawInputListener {

    private static final float VBL = 1f / 50f;
    private static final int MAX_CATCH_UP = 5;

    /** Un écran et le test de sa fin. */
    record Step(Scene scene, BooleanSupplier finished) {
    }

    private final Deque<Step> steps = new ArrayDeque<>();
    private final Runnable onDone;
    private ScreenView view;
    private float accumulated;
    private boolean keyUp;
    private boolean keyDown;
    private boolean keyLeft;
    private boolean keyRight;
    private boolean mouseFire;
    private boolean keyFire;
    private boolean done;

    InterludeState(Runnable onDone, Step... steps) {
        this.onDone = onDone;
        for (Step step : steps) {
            this.steps.add(step);
        }
    }

    @Override
    protected void initialize(Application app) {
        show();
    }

    @Override
    protected void cleanup(Application app) {
    }

    @Override
    protected void onEnable() {
        SimpleApplication app = (SimpleApplication) getApplication();
        view.attach(app.getGuiNode(), app.getAssetManager());
        app.getInputManager().addRawInputListener(this);
    }

    @Override
    protected void onDisable() {
        SimpleApplication app = (SimpleApplication) getApplication();
        if (view != null) {
            view.detach();
        }
        app.getInputManager().removeRawInputListener(this);
    }

    @Override
    public void update(float tpf) {
        if (done) {
            return;
        }
        accumulated += tpf;
        int count = 0;
        while (accumulated >= VBL) {
            accumulated -= VBL;
            if (count++ < MAX_CATCH_UP && !done) {
                vbl();
            }
        }
        if (!done) {
            show();
        }
        view.layout(getApplication().getCamera().getWidth(), getApplication().getCamera().getHeight());
    }

    private void vbl() {
        Step step = steps.peek();
        step.scene().setJoystick(keyUp, keyDown, keyLeft, keyRight);
        step.scene().setFire(mouseFire || keyFire);
        step.scene().tick();
        if (step.finished().getAsBoolean()) {
            steps.poll();
            if (steps.isEmpty()) {
                done = true;
                getApplication().enqueue(onDone::run);
            } else {
                // Le bouton encore tenu ne doit pas valider l'écran suivant.
                mouseFire = false;
                keyFire = false;
            }
        }
    }

    private void show() {
        Scene scene = steps.peek().scene();
        if (view == null || !view.fits(scene.width(), scene.height(), scene.pixelAspect())) {
            if (view != null) {
                view.detach();
            }
            view = new ScreenView(scene.width(), scene.height(), scene.pixelAspect());
            if (isEnabled() && isInitialized()) {
                SimpleApplication app = (SimpleApplication) getApplication();
                view.attach(app.getGuiNode(), app.getAssetManager());
            }
        }
        view.upload(scene.frame());
    }

    @Override
    public void onMouseMotionEvent(MouseMotionEvent event) {
        Step step = steps.peek();
        if (step != null && view != null) {
            step.scene().movePointer(event.getDX() / view.scaleX(), -event.getDY() / view.scaleY());
        }
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
            case KeyInput.KEY_RIGHT -> keyRight = pressed;
            case KeyInput.KEY_RETURN, KeyInput.KEY_NUMPADENTER, KeyInput.KEY_SPACE,
                 KeyInput.KEY_LCONTROL, KeyInput.KEY_RCONTROL -> keyFire = pressed;
            default -> {
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
