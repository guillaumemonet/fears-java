package fears.game;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.control.BillboardControl;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture2D;
import fears.engine.Aliens;
import fears.engine.LevelMesh;
import fears.engine.World;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les aliens et les projectiles, en panneaux tournés vers le joueur (une seule vue par alien
 * dans l'original). Mis à jour à chaque image d'après {@link Aliens}.
 *
 * <p>Taille d'après le type (largeur W, hauteur Hs, décalage vertical selon l'état vivant,
 * mourant ou cadavre) ; centre à {@code sol − 2,7465 + décalage} unités. Ce décalage est en
 * unités de hauteur : c'est ce qui pose les pieds des aliens sur le sol pour tous les types.
 */
final class AlienView {

    private final Node node = new Node("aliens");
    private final AssetManager assets;
    private final World world;
    private final Aliens aliens;
    /** [jeu][image] : matériaux des images d'aliens. */
    private final Material[][] alienMaterials = new Material[3][];
    private final Map<Integer, Material> objectMaterials = new HashMap<>();
    private final Path objectImages;
    private final List<Node> alienNodes = new ArrayList<>();
    private final List<Node> shotNodes = new ArrayList<>();

    AlienView(AssetManager assets, World world, Aliens aliens, int[] sets, Path sprites, Path objectImages)
            throws IOException {
        this.assets = assets;
        this.world = world;
        this.aliens = aliens;
        this.objectImages = objectImages;
        for (int set = 0; set < 3; set++) {
            Path dir = spriteDirectory(sprites, sets[set]);
            List<Material> frames = new ArrayList<>();
            for (int i = 0; dir != null && Files.isRegularFile(dir.resolve(String.format("%02d.png", i))); i++) {
                frames.add(material(Textures.load(dir.resolve(String.format("%02d.png", i)))));
            }
            alienMaterials[set] = frames.toArray(Material[]::new);
        }
        for (Aliens.Alien alien : aliens.aliens()) {
            Node holder = billboard("alien " + alien.number);
            node.attachChild(holder);
            alienNodes.add(holder);
        }
    }

    /** Le dossier d'images du jeu d'aliens n° id ({@code sprites/<fichier>_setNN/}). */
    private static Path spriteDirectory(Path sprites, int id) throws IOException {
        String suffix = String.format("_set%02d", id);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(sprites)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry) && entry.getFileName().toString().endsWith(suffix)) {
                    return entry;
                }
            }
        }
        System.err.println("AVERTISSEMENT : images du jeu d'aliens " + id + " introuvables");
        return null;
    }

    private Material material(Texture2D texture) {
        Material material = new Material(assets, "Fears/Shaders/Sprite.j3md");
        material.setTexture("ColorMap", texture);
        return material;
    }

    private Material objectMaterial(int image) {
        return objectMaterials.computeIfAbsent(image, i -> {
            try {
                return material(Textures.load(objectImages.resolve(String.format("%02d.png", i))));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static Node billboard(String name) {
        Node holder = new Node(name);
        Geometry quad = new Geometry(name, new Quad(1, 1));
        quad.setLocalTranslation(-0.5f, -0.5f, 0);
        holder.attachChild(quad);
        BillboardControl control = new BillboardControl();
        control.setAlignment(BillboardControl.Alignment.AxialY);
        holder.addControl(control);
        return holder;
    }

    Node node() {
        return node;
    }

    /** Met les panneaux à jour : position, taille, image. */
    void update() {
        List<Aliens.Alien> list = aliens.aliens();
        for (int i = 0; i < list.size(); i++) {
            Aliens.Alien alien = list.get(i);
            Node holder = alienNodes.get(i);
            Material[] frames = alienMaterials[alien.set];
            int image = alien.image();
            if (frames.length == 0) {
                holder.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
                continue;
            }
            Geometry quad = (Geometry) holder.getChild(0);
            quad.setMaterial(frames[Math.min(image, frames.length - 1)]);
            double[] display = alien.display();
            float w = (float) (display[0] * Aliens.UNITS_PER_W / 64.0);
            float h = (float) (display[1] * LevelMesh.HEIGHT_UNIT);
            double center = world.floor(alien.cell()) - ObjectView.CENTER_OFFSET + display[2];
            place(holder, quad, alien.x(), alien.y(), center, w, h);
        }
        for (Node shot : shotNodes) {
            shot.removeFromParent();
        }
        List<Aliens.Shot> shots = aliens.shots();
        while (shotNodes.size() < shots.size()) {
            shotNodes.add(billboard("projectile"));
        }
        for (int i = 0; i < shots.size(); i++) {
            Aliens.Shot shot = shots.get(i);
            Node holder = shotNodes.get(i);
            Geometry quad = (Geometry) holder.getChild(0);
            quad.setMaterial(objectMaterial(shot.image));
            float w = (float) (shot.width * Aliens.UNITS_PER_W / 64.0);
            float h = (float) (shot.height * LevelMesh.HEIGHT_UNIT);
            place(holder, quad, shot.x(), shot.y(), shot.h(), w, h);
            node.attachChild(holder);
        }
    }

    private static void place(Node holder, Geometry quad, double x, double y, double centerHeight,
                              float w, float h) {
        quad.setLocalScale(w, h, 1);
        quad.setLocalTranslation(-w / 2, -h / 2, 0);
        holder.setLocalTranslation((float) (x / 64.0), (float) (-centerHeight * LevelMesh.HEIGHT_UNIT),
                (float) (y / 64.0));
    }
}
