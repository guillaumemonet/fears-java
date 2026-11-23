package fears.menu;

import fears.engine.SaveGame;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Le choix d'un emplacement de sauvegarde ({@code L014524}), pour charger (LOAD GAME) ou
 * sauvegarder (SAVE GAME, depuis l'écran des statistiques).
 *
 * <p>Écran hires d'un plan (liste copper 0x2b3cc) : la petite police 8×7 ({@code L027faa}),
 * une ligne tous les 8 pixels à partir de la ligne 48 ({@code L01475a}) — titre, ligne vide,
 * 11 emplacements « GAME nn: LEVEL nn SCORE … », trois lignes vides, REREAD, CANCEL. Un
 * emplacement occupé s'écrit en $FFF, un vide en $CCC ({@code L014890}). La ligne pointée vaut
 * (y − $40) / 8 ({@code L0147be}) : 0 à 10 pour les emplacements, 14 REREAD, 15 CANCEL.
 *
 * <p>Le score s'écrit sur 10 chiffres dans une chaîne qui en compte 11 : le dernier « 0 » de
 * la chaîne reste toujours en place.
 */
public final class SlotScreen implements Scene {

    public static final int WIDTH = 640;
    public static final int HEIGHT = 256;
    static final int TOP = 48;
    static final int LINE = 8;
    static final int FIRST_SLOT_LINE = 2;
    public static final int REREAD = 14;
    public static final int CANCEL = 15;
    /** [S] Couleur du titre et de REREAD/CANCEL. */
    static final int WHITE = 0xFFFFFF;
    static final int GREY = 0xCCCCCC;

    private final MenuAssets assets;
    private final Path directory;
    private final boolean saving;
    private final Pointer pointer = new Pointer();
    private final int[] frame = new int[WIDTH * HEIGHT];
    private final SaveGame[] slots = new SaveGame[SaveGame.SLOTS];
    private boolean fire;
    private boolean released;
    private int chosen = -1;

    public SlotScreen(MenuAssets assets, Path directory, boolean saving) {
        this.assets = assets;
        this.directory = directory;
        this.saving = saving;
        reread();
    }

    /** REREAD : relit la « disquette ». */
    private void reread() {
        for (int i = 0; i < slots.length; i++) {
            slots[i] = SaveGame.read(directory, i);
        }
    }

    /** L'emplacement choisi (0 à 10), {@link #CANCEL}, ou −1 tant que rien n'est choisi. */
    public int chosen() {
        return chosen;
    }

    public boolean finished() {
        return chosen >= 0;
    }

    public SaveGame slot(int index) {
        return slots[index];
    }

    /** La ligne sous le pointeur, comme {@code L0147be} : −1 au-dessus de $40. */
    int pointedLine() {
        return pointer.y() < 0x40 ? -1 : (pointer.y() - 0x40) >> 3;
    }

    void pointTo(int x, int y) {
        pointer.set(x, y);
    }

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

    @Override
    public int[] frame() {
        return frame;
    }

    @Override
    public void movePointer(double dx, double dy) {
        pointer.move(dx, dy);
    }

    @Override
    public void setJoystick(boolean up, boolean down, boolean left, boolean right) {
        pointer.joystick(up, down, left, right);
    }

    @Override
    public void setFire(boolean pressed) {
        fire = pressed;
    }

    @Override
    public void tick() {
        pointer.tick();
        if (!fire) {
            released = true;
        } else if (released) {
            released = false;
            int line = pointedLine();
            if (line == REREAD) {
                reread();
            } else if (line == CANCEL) {
                chosen = CANCEL;
            } else if (line >= 0 && line < SaveGame.SLOTS && (saving || slots[line] != null)) {
                chosen = line;
            }
        }
        compose();
    }

    private List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add(saving ? "       SELECT A GAME TO SAVE" : "          SELECT A GAME TO LOAD");
        lines.add("");
        for (int i = 0; i < SaveGame.SLOTS; i++) {
            SaveGame save = slots[i];
            int level = save != null ? save.level() + 1 : 0;
            long score = save != null ? save.score() : 0;
            lines.add(String.format("   GAME %02d: LEVEL %02d SCORE %010d0  ", i + 1, level, score % 10_000_000_000L));
        }
        lines.add("");
        lines.add("");
        lines.add("");
        lines.add("                 REREAD");
        lines.add("                 CANCEL");
        return lines;
    }

    private void compose() {
        java.util.Arrays.fill(frame, 0);
        List<String> lines = lines();
        for (int line = 0; line < lines.size(); line++) {
            int slot = line - FIRST_SLOT_LINE;
            int color = slot >= 0 && slot < SaveGame.SLOTS ? (slots[slot] != null ? WHITE : GREY) : WHITE;
            drawText(lines.get(line), TOP + line * LINE, color);
        }
        pointer.draw(frame, WIDTH, HEIGHT, assets);
    }

    /** {@code L014792} : un octet de police par caractère et par ligne, à partir de l'octet 22. */
    private void drawText(String text, int top, int color) {
        int[][] font = assets.smallFont;
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i) - 0x20;
            if (c <= 0 || c * 8 >= font[0].length) {
                continue;
            }
            for (int row = 0; row < 7; row++) {
                for (int b = 0; b < 8; b++) {
                    if (font[row][c * 8 + b] != 0) {
                        int x = 22 * 8 + i * 8 + b;
                        if (x < WIDTH) {
                            frame[(top + row) * WIDTH + x] = color;
                        }
                    }
                }
            }
        }
    }
}
