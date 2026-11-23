package fears.game;

import com.jme3.audio.AudioBuffer;
import com.jme3.audio.AudioKey;
import com.jme3.audio.AudioNode;
import com.jme3.util.BufferUtils;
import fears.engine.Ambience;

import java.nio.ByteBuffer;

/**
 * Les ambiances des murs sur les voies 2 et 3 : chaque échantillon tourne en boucle, préparé à
 * la période de sa première entrée ; à chaque VBL, la hauteur suit le rapport des périodes et
 * le volume suit l'enveloppe.
 */
final class AmbienceSound {

    private static final double PAULA_CLOCK = 3_546_895.0;

    private final Ambience ambience;
    private final AudioNode[] nodes = new AudioNode[2];

    AmbienceSound(Ambience ambience) {
        this.ambience = ambience;
        for (int i = 0; i < 2; i++) {
            Ambience.Channel channel = ambience.channel(i);
            if (channel.pcm().length == 0) {
                continue;
            }
            ByteBuffer pcm = BufferUtils.createByteBuffer(channel.pcm().length);
            for (byte b : channel.pcm()) {
                pcm.put((byte) (b + 128));
            }
            pcm.flip();
            AudioBuffer buffer = new AudioBuffer();
            buffer.setupFormat(1, 8, (int) Math.round(PAULA_CLOCK / channel.basePeriod()));
            buffer.updateData(pcm);
            AudioNode node = new AudioNode(buffer, new AudioKey("ambiance-" + (i + 2)));
            node.setPositional(false);
            node.setLooping(true);
            nodes[i] = node;
        }
    }

    void start() {
        update();
        for (AudioNode node : nodes) {
            if (node != null) {
                node.play();
            }
        }
    }

    void stop() {
        for (AudioNode node : nodes) {
            if (node != null) {
                node.stop();
            }
        }
    }

    /** Une VBL : enveloppes, puis volume et hauteur des deux voies. */
    void tick() {
        ambience.tick();
        update();
    }

    private void update() {
        for (int i = 0; i < 2; i++) {
            AudioNode node = nodes[i];
            if (node == null) {
                continue;
            }
            Ambience.Channel channel = ambience.channel(i);
            double period = Math.max(1, channel.period());
            node.setPitch((float) Math.max(0.5, Math.min(2.0, channel.basePeriod() / period)));
            node.setVolume((float) (Math.max(0, Math.min(64, channel.volume())) / 64.0));
        }
    }
}
