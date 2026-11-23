package fears.game;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.Vector2f;
import com.jme3.post.Filter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;

/**
 * Le rendu « chunky » de l'Amiga en post-traitement : la vue 3D ramenée à des pixels de
 * 3 pixels basse résolution sur 2 lignes, et, avec le tramage (F1, option DITHERING), la
 * seconde ligne de chaque rangée décalée d'un pixel — ce que le moteur obtient en passant les
 * modulos des plans de −40 à $F0 ({@code L014c6c}, {@code L013798}).
 */
final class ChunkyFilter extends Filter {

    private Material material;
    private final Vector2f viewSize = new Vector2f(320, 256);
    private float lores = 1;
    private float line = 1;
    private boolean dither;

    ChunkyFilter() {
        super("chunky");
    }

    @Override
    protected void initFilter(AssetManager manager, RenderManager renderManager, ViewPort vp, int w, int h) {
        material = new Material(manager, "Fears/Shaders/Chunky.j3md");
        apply();
    }

    @Override
    protected Material getMaterial() {
        return material;
    }

    /**
     * @param width  largeur de la vue, en pixels de fenêtre
     * @param height hauteur de la vue
     * @param loresPixel largeur d'un pixel basse résolution de l'écran d'origine, en pixels de fenêtre
     * @param rasterLine hauteur d'une ligne de l'écran d'origine
     */
    void configure(float width, float height, float loresPixel, float rasterLine, boolean dithering) {
        viewSize.set(width, height);
        lores = loresPixel;
        line = rasterLine;
        dither = dithering;
        apply();
    }

    private void apply() {
        if (material == null) {
            return;
        }
        material.setVector2("ViewSize", viewSize);
        material.setFloat("Lores", lores);
        material.setFloat("Line", line);
        material.setBoolean("Dither", dither);
    }
}
