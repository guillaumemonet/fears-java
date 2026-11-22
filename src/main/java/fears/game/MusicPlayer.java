package fears.game;

import com.jme3.app.Application;
import com.jme3.audio.AudioKey;
import com.jme3.audio.AudioSource;
import com.jme3.audio.AudioBuffer;
import com.jme3.audio.AudioNode;
import com.jme3.util.BufferUtils;
import fears.audio.ModModule;
import fears.audio.ModPlayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * La musique : un module rendu une fois en entier, hors du fil de rendu, puis rejoué en
 * boucle par jMonkeyEngine.
 */
final class MusicPlayer {

    private static final int RATE = 44_100;
    private static final int MAX_SECONDS = 600;

    private final Application app;
    private volatile short[] rendered;
    private AudioNode node;
    private boolean wanted;
    private float volume = 1f;

    MusicPlayer(Application app) {
        this.app = app;
    }

    /** Lance le rendu du module en arrière-plan ; il jouera dès qu'il sera prêt. */
    void load(Path module) {
        Thread worker = new Thread(() -> {
            try {
                long started = System.nanoTime();
                ModModule mod = ModModule.read(Files.readAllBytes(module));
                short[] pcm = ModPlayer.render(mod, RATE, MAX_SECONDS);
                System.out.printf("Musique \"%s\" : %.0f s, rendue en %.1f s%n", mod.title(),
                        pcm.length / 2.0 / RATE, (System.nanoTime() - started) / 1e9);
                rendered = pcm;
                app.enqueue(this::refresh);
            } catch (Exception e) {
                System.err.println("AVERTISSEMENT : musique illisible (" + module + ") : " + e.getMessage());
            }
        }, "rendu-musique");
        worker.setDaemon(true);
        worker.start();
    }

    void play() {
        wanted = true;
        refresh();
    }

    void stop() {
        wanted = false;
        if (node != null) {
            node.stop();
        }
    }

    void setVolume(float value) {
        volume = value;
        if (node != null) {
            node.setVolume(volume);
        }
    }

    private void refresh() {
        if (!wanted) {
            return;
        }
        if (node == null && rendered != null) {
            node = createNode(rendered);
        }
        if (node != null && node.getStatus() != AudioSource.Status.Playing) {
            node.setVolume(volume);
            node.play();
        }
    }

    private static AudioNode createNode(short[] pcm) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asShortBuffer().put(pcm);
        AudioBuffer buffer = new AudioBuffer();
        buffer.setupFormat(2, 16, RATE);
        buffer.updateData(bytes);
        AudioNode node = new AudioNode(buffer, new AudioKey("musique-menu"));
        node.setPositional(false);
        node.setLooping(true);
        return node;
    }
}
