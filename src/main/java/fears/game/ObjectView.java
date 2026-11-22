package fears.game;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.control.BillboardControl;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture2D;
import fears.engine.EngineData;
import fears.engine.LevelMesh;
import fears.engine.World;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Les objets posés sur les cases (grille C, octet 4), en panneaux toujours tournés vers le
 * joueur — le moteur d'origine n'a qu'une vue par objet.
 *
 * <p>Taille et hauteur d'après la table du moteur (MOTEUR.md, rendu §6.2–6.3) :
 * <ul>
 *   <li>hauteur = Hs unités de hauteur (la projection des sprites et celle des murs partagent
 *       la même échelle verticale) ;</li>
 *   <li>largeur = W × 11,9 sous-unités, soit W × 0,186 case — la focale des murs (172
 *       colonnes) appliquée à {@code w = W / (32·Z)} ;</li>
 *   <li>centre à {@code référence − 2,7465 + dy/16} unités (échelle des sols), la référence
 *       étant le sol — ou le plafond pour la stalactite (objet 12). Une colonne (dy = 0) touche
 *       ainsi exactement le sol.</li>
 * </ul>
 */
final class ObjectView {

    /** Largeur d'une unité W, en cases. */
    static final float WIDTH_PER_W = 2048f / 172f / 64f;
    /** Œil des murs (3,662) moins œil des sprites (0,9155) : 2,7465 unités. */
    static final float CENTER_OFFSET = (0x3A980 - 0xEA60) / 65536f;
    static final int STALACTITE = 12;

    private final Node node = new Node("objets");
    private final Map<Integer, Node> byCell = new HashMap<>();

    ObjectView(AssetManager assets, World world, EngineData data, Path images) throws IOException {
        Map<Integer, Material> materials = new HashMap<>();
        for (int cell = 0; cell < World.SIZE * World.SIZE; cell++) {
            int type = world.item(cell);
            EngineData.ObjectSprite sprite = data.object(type);
            if (sprite == null) {
                continue;                       // vide, ou apparition d'alien (15 à 17)
            }
            Material material = materials.get(sprite.image());
            if (material == null) {
                Texture2D texture = Textures.load(images.resolve(String.format("%02d.png", sprite.image())));
                material = new Material(assets, "Fears/Shaders/Sprite.j3md");
                material.setTexture("ColorMap", texture);
                materials.put(sprite.image(), material);
            }
            float w = (float) sprite.width() * WIDTH_PER_W;
            float h = (float) (sprite.height() * LevelMesh.HEIGHT_UNIT);
            int reference = type == STALACTITE ? world.ceiling(cell) : world.floor(cell);
            float center = (float) ((reference - CENTER_OFFSET + sprite.dy() / 16.0) * -LevelMesh.HEIGHT_UNIT);

            Geometry quad = new Geometry("objet " + type, new Quad(w, h));
            quad.setMaterial(material);
            quad.setLocalTranslation(-w / 2, -h / 2, 0);
            Node holder = new Node("case " + cell);
            holder.attachChild(quad);
            holder.setLocalTranslation(cell % World.SIZE + 0.5f, center, cell / World.SIZE + 0.5f);
            BillboardControl billboard = new BillboardControl();
            billboard.setAlignment(BillboardControl.Alignment.AxialY);
            holder.addControl(billboard);
            node.attachChild(holder);
            byCell.put(cell, holder);
        }
    }

    Node node() {
        return node;
    }

    /** Retire l'objet ramassé. */
    void remove(int cell) {
        Node holder = byCell.remove(cell);
        if (holder != null) {
            holder.removeFromParent();
        }
    }
}
