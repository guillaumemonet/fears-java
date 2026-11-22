package fears.menu;

import java.util.Arrays;

/**
 * Le pointeur des écrans du menu : souris ({@code L0105b8}) et joystick ({@code L0106e8}).
 *
 * <p>x de 0 à 0x278 (pixels hires), y de 0 à 0xF7 (lignes). Au joystick, une direction seule
 * accélère de 1 par image jusqu'à 10 ; en diagonale, la seconde de 2 jusqu'à 20 ; changer de
 * direction remet les vitesses à zéro.
 */
public final class Pointer {

    static final int MAX_X = 0x278;
    static final int MAX_Y = 0xF7;

    private int x;
    private int y;
    private double pendingDx;
    private double pendingDy;
    /** Vitesses : haut, bas, droite, gauche ({@code L01079a}). */
    private final int[] speed = new int[4];
    private boolean up;
    private boolean down;
    private boolean left;
    private boolean right;

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    void set(int x, int y) {
        this.x = clamp(x, MAX_X);
        this.y = clamp(y, MAX_Y);
    }

    /** Déplacement de la souris, en pixels hires (x) et en lignes (y). */
    public void move(double dx, double dy) {
        pendingDx += dx;
        pendingDy += dy;
    }

    public void joystick(boolean up, boolean down, boolean left, boolean right) {
        this.up = up;
        this.down = down;
        this.left = left;
        this.right = right;
    }

    /** Une image : la souris, puis le joystick. */
    public void tick() {
        int dx = (int) pendingDx;
        int dy = (int) pendingDy;
        pendingDx -= dx;
        pendingDy -= dy;
        x = clamp(x + dx, MAX_X);
        y = clamp(y + dy, MAX_Y);
        joystickStep();
    }

    private void joystickStep() {
        int primary;
        int secondary = -1;
        if (up && !down && !left && !right) {
            primary = 0;
        } else if (down && !up && !left && !right) {
            primary = 1;
        } else if (right && !left && !up && !down) {
            primary = 2;
        } else if (left && !right && !up && !down) {
            primary = 3;
        } else if (down && right && !up && !left) {
            primary = 1;
            secondary = 2;
        } else if (down && left && !up && !right) {
            primary = 1;
            secondary = 3;
        } else if (up && right && !down && !left) {
            primary = 0;
            secondary = 2;
        } else if (up && left && !down && !right) {
            primary = 0;
            secondary = 3;
        } else {
            Arrays.fill(speed, 0);
            return;
        }
        int first = speed[primary];
        int second = secondary >= 0 ? speed[secondary] : 0;
        Arrays.fill(speed, 0);
        speed[primary] = first == 10 ? first : first + 1;
        if (secondary >= 0) {
            speed[secondary] = second == 20 ? second : second + 2;
        }
        y = clamp(y - speed[0] + speed[1], MAX_Y);
        x = clamp(x + speed[2] - speed[3], MAX_X);
    }

    /**
     * Dessine le sprite du pointeur (basse résolution : chaque pixel couvre deux pixels hires).
     */
    void draw(int[] frame, int width, int height, MenuAssets assets) {
        int[][] sprite = assets.pointer;
        int left0 = x & ~1;
        for (int sy = 0; sy < sprite.length; sy++) {
            int py = y + sy;
            if (py >= height) {
                break;
            }
            for (int sx = 0; sx < sprite[sy].length; sx++) {
                int index = sprite[sy][sx];
                if (index == 0) {
                    continue;
                }
                for (int dup = 0; dup < 2; dup++) {
                    int px = left0 + 2 * sx + dup;
                    if (px < width) {
                        frame[py * width + px] = assets.pointerColors[index];
                    }
                }
            }
        }
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }
}
