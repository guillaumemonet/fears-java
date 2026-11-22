package fears.game;

import com.jme3.asset.AssetManager;
import com.jme3.scene.Node;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;

/**
 * Un écran Amiga affiché tel quel : une texture plein cadre, aux pixels nets, dont on garde
 * les proportions d'origine. Un pixel hires PAL non entrelacé est deux fois plus haut que
 * large : une image de 640×256 s'affiche donc en 640×512, comme une image entrelacée de
 * 640×512 ; l'une et l'autre agrandies au mieux et centrées.
 */
final class ScreenView {

    private final int width;
    private final int height;
    private final float pixelAspect;
    private final boolean transparent;
    private final ByteBuffer pixels;
    private final Image image;
    private final Texture2D texture;
    private Picture picture;
    private float scaleX = 1;
    private float scaleY = 1;
    private int laidOutFor = -1;

    ScreenView(int width, int height, float pixelAspect) {
        this(width, height, pixelAspect, false);
    }

    /** @param transparent l'alpha des pixels est respecté (HUD posé sur la vue 3D) */
    ScreenView(int width, int height, float pixelAspect, boolean transparent) {
        this.transparent = transparent;
        this.width = width;
        this.height = height;
        this.pixelAspect = pixelAspect;
        this.pixels = BufferUtils.createByteBuffer(width * height * 4);
        this.image = new Image(Image.Format.RGBA8, width, height, pixels, ColorSpace.sRGB);
        this.texture = new Texture2D(image);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
    }

    /** Cette vue convient-elle à une image de cette taille et de ces proportions ? */
    boolean fits(int width, int height, float pixelAspect) {
        return this.width == width && this.height == height && this.pixelAspect == pixelAspect;
    }

    void attach(Node gui, AssetManager assets) {
        if (picture == null) {
            picture = new Picture("ecran");
            picture.setTexture(assets, texture, transparent);
        }
        gui.attachChild(picture);
        laidOutFor = -1;
    }

    void detach() {
        if (picture != null) {
            picture.removeFromParent();
        }
    }

    /** Agrandit l'image au mieux dans la fenêtre, en gardant ses proportions. */
    void layout(int windowWidth, int windowHeight) {
        int key = windowWidth * 65_536 + windowHeight;
        if (picture == null || key == laidOutFor) {
            return;
        }
        laidOutFor = key;
        float displayHeight = height * pixelAspect;
        float scale = Math.min(windowWidth / (float) width, windowHeight / displayHeight);
        float w = width * scale;
        float h = displayHeight * scale;
        picture.setWidth(w);
        picture.setHeight(h);
        picture.setPosition((windowWidth - w) / 2f, (windowHeight - h) / 2f);
        scaleX = scale;
        scaleY = scale * pixelAspect;
    }

    /** Le rectangle occupé dans la fenêtre : x, y (depuis le bas), largeur, hauteur. */
    float[] rectangle(int windowWidth, int windowHeight) {
        float displayHeight = height * pixelAspect;
        float scale = Math.min(windowWidth / (float) width, windowHeight / displayHeight);
        float w = width * scale;
        float h = displayHeight * scale;
        return new float[]{(windowWidth - w) / 2f, (windowHeight - h) / 2f, w, h};
    }

    /** Pixels de la fenêtre par pixel de l'image, horizontalement. */
    float scaleX() {
        return scaleX;
    }

    /** Pixels de la fenêtre par ligne de l'image. */
    float scaleY() {
        return scaleY;
    }

    /** Envoie une image 0xRRGGBB ; OpenGL compte les lignes de bas en haut. */
    void upload(int[] rgb) {
        pixels.clear();
        for (int y = height - 1; y >= 0; y--) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int c = rgb[row + x];
                pixels.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c)
                        .put(transparent ? (byte) (c >>> 24) : (byte) 0xFF);
            }
        }
        pixels.flip();
        image.setData(pixels);
        image.setUpdateNeeded();
    }
}
