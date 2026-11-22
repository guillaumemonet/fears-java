package fears.extract;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * Niveau de Fears (.lvl, 106 536 octets). Format établi depuis l'éditeur (d1_0bb6b2),
 * le moteur (d1_0282da) et le chargeur (d1_000400).
 *
 * <pre>
 * 0x00000  en-tête 0x28 : long départ P1 (offset (y*64+x)*8), long angle P1 (tour = 0x10E0),
 *          titre 22 car., 3 octets « ALIENS LIST » (éditeur), octet page de textures (éditeur),
 *          long départ P2, mot angle P2 (éditeur ; le jeu en câble utilise une table fixe)
 * 0x00028  grille A 64×64×8 : mot sol, mot plafond (0..63, plus grand = plus bas ; plein si égaux),
 *          octets texture plafond, sol, mur bas, mur haut
 * 0x08028  grille B : hauteurs de l'autre état des cases mobiles (portes, ascenseurs)
 * 0x10028  grille C : octet 4 = objet (voir {@link #OBJECT_NAMES}), mot 6 = déclencheur
 * 0x18028  64 portes × 0x80 : mot compte, puis jusqu'à 63 index de cases mobiles
 * </pre>
 */
public final class Level {

    public static final int SIZE = 0x1A028, W = 64, H = 64;
    public static final int GRID_A = 0x28, GRID_B = 0x8028, GRID_C = 0x10028, DOORS = 0x18028;
    /** Angle : un tour complet. 0 = vers +y (bas de la carte), 0x438 = −x, 0x870 = −y, 0xCA8 = +x. */
    public static final int FULL_TURN = 0x10E0;

    public static final String[] OBJECT_NAMES = {"", "ROLLING", "ROCKET", "PLASMA", "C. SAW", "AMMO 1", "AMMO 2",
        "AMMO 3", "LIFE", "HEALTH", "SHIELD", "GOLD", "STALAGT.", "BARREL", "COLUMN", "ALIEN 1", "ALIEN 2", "ALIEN 3"};

    public record Cell(int floor, int ceiling, int texCeiling, int texFloor, int texWallDown, int texWallUp) {
        public boolean solid() {
            return floor <= ceiling;
        }
    }

    /** Déclencheur : porte 1..63 ou 64 = fin du niveau ; sens = octet 6 &amp; 0x78. */
    public record Trigger(int x, int y, int door, int direction) {
        public boolean exit() {
            return door == 64;
        }

        public String directionName() {
            return switch (direction) {
                case 0x00 -> "tous côtés";
                case 0x08 -> "+x";
                case 0x10 -> "+y";
                case 0x20 -> "-x";
                case 0x40 -> "-y";
                case 0x28 -> "axe x";
                case 0x50 -> "axe y";
                case 0x78 -> "sur la case";
                default -> String.format("0x%02x", direction);
            };
        }
    }

    public record Item(int x, int y, int type) {
        public String name() {
            return type < OBJECT_NAMES.length ? OBJECT_NAMES[type] : "?" + type;
        }
    }

    public final String title;
    public final int p1x, p1y, p1angle, p2x, p2y, p2angle;
    public final int[] editorAliens = new int[3];
    public final int editorTexturePage;
    public final Cell[] a = new Cell[W * H], b = new Cell[W * H];
    public final List<Item> items = new ArrayList<>();
    public final List<Trigger> triggers = new ArrayList<>();
    /** porte n (1..63) → index des cases mobiles. */
    public final Map<Integer, List<Integer>> doors = new TreeMap<>();

    public Level(byte[] d) {
        if (d.length != SIZE) {
            throw new IllegalArgumentException("taille de niveau inattendue : " + d.length);
        }
        int p1 = Be.l(d, 0) / 8, p2 = Be.l(d, 0x22) / 8;
        p1x = p1 % W;
        p1y = p1 / W;
        p1angle = Be.l(d, 4);
        title = new String(d, 8, 22, StandardCharsets.ISO_8859_1).trim();
        for (int i = 0; i < 3; i++) {
            editorAliens[i] = d[0x1e + i] & 0xff;
        }
        editorTexturePage = d[0x21] & 0xff;
        p2x = p2 % W;
        p2y = p2 / W;
        p2angle = Be.w(d, 0x26);
        for (int i = 0; i < W * H; i++) {
            a[i] = cell(d, GRID_A + i * 8);
            b[i] = cell(d, GRID_B + i * 8);
            if (i == 0) {
                continue; // le moteur efface la case (0,0) de la grille C
            }
            int o = GRID_C + i * 8;
            int type = d[o + 4] & 0xff;
            if (type != 0) {
                items.add(new Item(i % W, i / W, type));
            }
            int trig = Be.w(d, o + 6);
            if ((trig & 0x7f) != 0) {
                triggers.add(new Trigger(i % W, i / W, trig & 0x7f, (trig >> 8) & 0x78));
            }
        }
        // 64 = fin du niveau : son enregistrement n'est pas lu par le moteur (parfois rempli de déchets)
        for (int n = 1; n <= 63; n++) {
            int o = DOORS + (n - 1) * 0x80;
            int count = Math.abs((short) Be.w(d, o));
            if (count == 0 || count > 63) {
                continue;
            }
            List<Integer> cells = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                int idx = Be.w(d, o + 2 + 2 * k);
                if (idx < W * H) {
                    cells.add(idx);
                }
            }
            doors.put(n, cells);
        }
    }

    private static Cell cell(byte[] d, int o) {
        return new Cell(Be.w(d, o), Be.w(d, o + 2), d[o + 4] & 0xff, d[o + 5] & 0xff, d[o + 6] & 0xff, d[o + 7] & 0xff);
    }

    public static double angleDegrees(int angle) {
        return angle * 360.0 / FULL_TURN;
    }

    // ------------------------------------------------------------------ exports

    /** Export JSON autonome (pas de dépendance) : tout ce qu'il faut pour reconstruire le niveau. */
    public String toJson(String file, LevelCatalog.Entry cat) {
        StringBuilder s = new StringBuilder("{\n");
        s.append("  \"file\": \"").append(file).append("\",\n");
        s.append("  \"title\": \"").append(title.replace("\"", "\\\"")).append("\",\n");
        if (cat != null) {
            s.append(String.format("  \"level\": %d,%n  \"walls\": \"%s\",%n  \"wallPalette\": \"%s\",%n  \"alienSets\": [%d, %d, %d],%n",
                    cat.number(), cat.walls(), cat.paletteB() ? "B" : "A", cat.aliens()[0], cat.aliens()[1], cat.aliens()[2]));
        }
        s.append(String.format(java.util.Locale.ROOT, "  \"player1\": {\"x\": %d, \"y\": %d, \"angle\": %d, \"degrees\": %.1f},%n", p1x, p1y, p1angle, angleDegrees(p1angle)));
        s.append(String.format("  \"player2\": {\"x\": %d, \"y\": %d, \"angle\": %d},%n", p2x, p2y, p2angle));
        s.append(String.format("  \"editor\": {\"aliens\": [%d, %d, %d], \"texturePage\": %d},%n",
                editorAliens[0], editorAliens[1], editorAliens[2], editorTexturePage));
        s.append("  \"angleConvention\": \"tour = 4320 ; 0 = +y, 1080 = -x, 2160 = -y, 3240 = +x\",\n");
        s.append("  \"cellFields\": [\"floor\", \"ceiling\", \"texCeiling\", \"texFloor\", \"texWallDown\", \"texWallUp\"],\n");
        grid(s, "gridA", a);
        s.append(",\n");
        // grille B : seules les cases mobiles comptent
        s.append("  \"movingCells\": [");
        StringJoiner mj = new StringJoiner(", ");
        Set<Integer> moving = new TreeSet<>();
        doors.values().forEach(moving::addAll);
        for (int i : moving) {
            Cell c = b[i];
            mj.add(String.format("{\"x\": %d, \"y\": %d, \"floor\": %d, \"ceiling\": %d}", i % W, i / W, c.floor(), c.ceiling()));
        }
        s.append(mj).append("],\n");
        s.append("  \"doors\": {");
        StringJoiner dj = new StringJoiner(", ");
        doors.forEach((n, cells) -> dj.add("\"" + n + "\": " + cells.stream().map(i -> "[" + (i % W) + ", " + (i / W) + "]").toList()));
        s.append(dj).append("},\n");
        s.append("  \"triggers\": [");
        StringJoiner tj = new StringJoiner(", ");
        for (Trigger t : triggers) {
            tj.add(String.format("{\"x\": %d, \"y\": %d, \"door\": %d, \"exit\": %b, \"direction\": %d}", t.x(), t.y(), t.door(), t.exit(), t.direction()));
        }
        s.append(tj).append("],\n");
        s.append("  \"items\": [");
        StringJoiner ij = new StringJoiner(", ");
        for (Item it : items) {
            ij.add(String.format("{\"x\": %d, \"y\": %d, \"type\": %d, \"name\": \"%s\"}", it.x(), it.y(), it.type(), it.name()));
        }
        s.append(ij).append("]\n}\n");
        return s.toString();
    }

    private static void grid(StringBuilder s, String name, Cell[] g) {
        s.append("  \"").append(name).append("\": [\n");
        for (int y = 0; y < H; y++) {
            s.append("    [");
            for (int x = 0; x < W; x++) {
                Cell c = g[y * W + x];
                s.append(String.format("[%d,%d,%d,%d,%d,%d]", c.floor(), c.ceiling(), c.texCeiling(), c.texFloor(),
                        c.texWallDown(), c.texWallUp()));
                if (x < W - 1) {
                    s.append(',');
                }
            }
            s.append(y < H - 1 ? "],\n" : "]\n");
        }
        s.append("  ]");
    }

    /** Carte vue de dessus : murs bleu foncé, sol en gris (plus clair = plus bas), portes, objets, départs. */
    public BufferedImage renderMap(int scale) {
        int legend = 170;
        BufferedImage img = new BufferedImage(W * scale + legend, H * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int i = 0; i < W * H; i++) {
            Cell c = a[i];
            int x = (i % W) * scale, y = (i / W) * scale;
            if (c.solid()) {
                g.setColor(new Color(0x2a2a5a));
            } else {
                int v = 70 + c.floor() * 3;
                g.setColor(new Color(Math.min(v, 230), Math.min(v, 230), Math.min(v, 230)));
            }
            g.fillRect(x, y, scale, scale);
            if (c.texWallDown() >= 14 && c.solid()) {
                g.setColor(new Color(0xffaa00)); // interrupteur (textures 14/15)
                g.drawRect(x + 1, y + 1, scale - 3, scale - 3);
            }
        }
        g.setStroke(new BasicStroke(1f));
        Map<Integer, Trigger> byDoor = new HashMap<>();
        for (Trigger t : triggers) {
            byDoor.putIfAbsent(t.door(), t);
        }
        for (var e : doors.entrySet()) {
            Trigger t = byDoor.get(e.getKey());
            for (int i : e.getValue()) {
                int x = (i % W) * scale, y = (i / W) * scale;
                g.setColor(new Color(0x2f8fff));
                g.drawRect(x, y, scale - 1, scale - 1);
                if (t != null && (Math.abs(t.x() - i % W) + Math.abs(t.y() - i / W)) > 1) {
                    g.setColor(new Color(0x602f8fff, true));
                    g.drawLine(t.x() * scale + scale / 2, t.y() * scale + scale / 2, x + scale / 2, y + scale / 2);
                }
            }
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, scale - 2)));
        for (Trigger t : triggers) {
            g.setColor(t.exit() ? Color.RED : Color.WHITE);
            g.drawString(t.exit() ? "X" : String.valueOf(t.door()), t.x() * scale + 1, t.y() * scale + scale - 2);
        }
        for (Item it : items) {
            g.setColor(itemColor(it.type()));
            int r = it.type() >= 15 ? scale - 2 : scale / 2;
            g.fillOval(it.x() * scale + (scale - r) / 2, it.y() * scale + (scale - r) / 2, r, r);
        }
        drawStart(g, p1x, p1y, p1angle, scale, Color.RED);
        // légende
        int lx = W * scale + 8, ly = 16;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.setColor(Color.WHITE);
        g.drawString(title, lx, ly);
        ly += 16;
        g.drawString(String.format("départ (%d,%d) %.0f°", p1x, p1y, angleDegrees(p1angle)), lx, ly);
        ly += 20;
        for (int t = 1; t < OBJECT_NAMES.length; t++) {
            g.setColor(itemColor(t));
            g.fillOval(lx, ly - 8, 8, 8);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(t + " " + OBJECT_NAMES[t], lx + 12, ly);
            ly += 13;
        }
        ly += 6;
        g.setColor(new Color(0x2f8fff));
        g.drawString("cadre bleu : case mobile", lx, ly);
        ly += 13;
        g.setColor(Color.WHITE);
        g.drawString("n : déclencheur de porte", lx, ly);
        ly += 13;
        g.setColor(Color.RED);
        g.drawString("X : sortie", lx, ly);
        ly += 13;
        g.setColor(new Color(0xffaa00));
        g.drawString("cadre orange : interrupteur", lx, ly);
        g.dispose();
        return img;
    }

    private static void drawStart(Graphics2D g, int x, int y, int angle, int scale, Color c) {
        double rad = Math.toRadians(angleDegrees(angle));
        // 0 = +y ; 90° = −x : vecteur (−sin, cos)
        double dx = -Math.sin(rad), dy = Math.cos(rad);
        int cx = x * scale + scale / 2, cy = y * scale + scale / 2;
        g.setColor(c);
        g.setStroke(new BasicStroke(2f));
        g.drawOval(cx - scale / 2, cy - scale / 2, scale, scale);
        g.drawLine(cx, cy, (int) (cx + dx * scale * 1.5), (int) (cy + dy * scale * 1.5));
    }

    private static Color itemColor(int t) {
        if (t <= 4) {
            return new Color(0xff8c1a);   // armes
        } else if (t <= 7) {
            return new Color(0xffd21a);   // munitions
        } else if (t <= 10) {
            return new Color(0x3cdc3c);   // vie, soins, bouclier
        } else if (t == 11) {
            return new Color(0xfff07a);   // or
        } else if (t <= 14) {
            return new Color(0x9a6a3a);   // décors
        }
        return new Color[]{new Color(0xff2a2a), new Color(0xff2aff), new Color(0xc070ff)}[t - 15];
    }
}
