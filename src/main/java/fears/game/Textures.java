package fears.game;

import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Textures lues dans les PNG extraits, aux texels nets comme sur Amiga. */
final class Textures {

    private Textures() {
    }

    /** Les pixels d'un PNG en 0xAARRGGBB, ligne par ligne depuis le haut. */
    static int[] argb(Path file) throws IOException {
        BufferedImage source = ImageIO.read(file.toFile());
        if (source == null) {
            throw new IOException("image illisible : " + file);
        }
        return source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth());
    }

    /** Les index de couleur d'un PNG indexé, ligne par ligne depuis le haut. */
    static int[] indices(Path file) throws IOException {
        BufferedImage source = ImageIO.read(file.toFile());
        if (source == null || !(source.getColorModel() instanceof java.awt.image.IndexColorModel)) {
            throw new IOException("image indexée attendue : " + file);
        }
        return source.getRaster().getPixels(0, 0, source.getWidth(), source.getHeight(), (int[]) null);
    }

    /**
     * Charge un PNG. OpenGL compte les lignes de bas en haut : la ligne 0 de l'image se
     * retrouve en v = 1.
     */
    static Texture2D load(Path file) throws IOException {
        BufferedImage source = ImageIO.read(file.toFile());
        if (source == null) {
            throw new IOException("image illisible : " + file);
        }
        int w = source.getWidth();
        int h = source.getHeight();
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = source.getRGB(x, y);
                pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb)
                        .put((byte) (argb >>> 24));
            }
        }
        pixels.flip();
        Texture2D texture = new Texture2D(new Image(Image.Format.RGBA8, w, h, pixels, ColorSpace.sRGB));
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setWrap(Texture.WrapMode.Repeat);
        texture.setName(file.getFileName().toString());
        return texture;
    }
}
