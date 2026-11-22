package fears.game;

import com.jme3.audio.AudioBuffer;
import com.jme3.audio.AudioKey;
import com.jme3.audio.AudioNode;
import com.jme3.util.BufferUtils;
import fears.engine.Doors;
import fears.engine.EngineData;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Les bruitages du moteur, lus dans sa table ({@code L01f8a0}) : échantillon 8 bits signé,
 * joué à la fréquence que donne sa période Paula, au volume indiqué (0 à 64).
 *
 * <p>Les portes (MOTEUR.md « monde » §4.4) : entrée {@code $54} pour un n° pair ou la sortie,
 * {@code $60} pour un n° impair, coupée quand la porte s'arrête.
 */
final class EngineSounds implements Doors.Listener, fears.engine.Aliens.Listener {

    private static final double PAULA_CLOCK = 3_546_895.0;
    static final int DOOR_EVEN = 0x54;
    static final int DOOR_ODD = 0x60;

    private final EngineData data;
    private final Map<Integer, AudioNode> nodes = new HashMap<>();
    private AudioNode door;
    /** Voie 1 (aliens) : un seul son à la fois, coupé au bout de sa durée. */
    private final Map<String, AudioNode> alienNodes = new HashMap<>();
    private AudioNode alienVoice;
    private int alienRemaining;

    EngineSounds(EngineData data) {
        this.data = data;
    }

    /** Joue le son désigné par son décalage dans la table ; renvoie son nœud, ou null. */
    AudioNode play(int offset) {
        AudioNode node = nodes.computeIfAbsent(offset, this::create);
        if (node != null) {
            node.stop();
            node.play();
        }
        return node;
    }

    private AudioNode create(int offset) {
        EngineData.Sound sound = data.sound(offset);
        if (sound == null || sound.pcm().length == 0 || sound.period() == 0) {
            return null;
        }
        // OpenAL attend du 8 bits non signé.
        ByteBuffer pcm = BufferUtils.createByteBuffer(sound.pcm().length);
        for (byte b : sound.pcm()) {
            pcm.put((byte) (b + 128));
        }
        pcm.flip();
        AudioBuffer buffer = new AudioBuffer();
        buffer.setupFormat(1, 8, (int) Math.round(PAULA_CLOCK / sound.period()));
        buffer.updateData(pcm);
        AudioNode node = new AudioNode(buffer, new AudioKey("son-" + Integer.toHexString(offset)));
        node.setPositional(false);
        node.setLooping(false);
        node.setVolume(Math.min(64, sound.volume()) / 64f);
        return node;
    }

    @Override
    public void doorStarted(int number) {
        doorStopped();
        door = play(number % 2 == 0 ? DOOR_EVEN : DOOR_ODD);
    }

    @Override
    public void doorStopped() {
        if (door != null) {
            door.stop();
            door = null;
        }
    }

    @Override
    public void alienSound(int offset, int period, int duration, int volume) {
        if (period <= 0) {
            return;
        }
        String key = offset + "/" + period;
        AudioNode node = alienNodes.computeIfAbsent(key, k -> {
            byte[] sample = data.alienSample(offset);
            return sample.length == 0 ? null : node(sample, period, "alien-" + k);
        });
        if (alienVoice != null) {
            alienVoice.stop();
        }
        alienVoice = node;
        if (node != null) {
            // Un son plus court que sa durée boucle : Paula relit l'échantillon.
            double seconds = node.getAudioData().getDuration();
            node.setLooping(seconds * 50 < duration);
            node.setVolume(Math.min(64, volume) / 64f);
            node.play();
            alienRemaining = duration;
        }
    }

    @Override
    public void sound(int offset) {
        play(offset);
    }

    /** Une VBL : la voie 1 se tait au bout de la durée demandée. */
    void tick() {
        if (alienVoice != null && --alienRemaining <= 0) {
            alienVoice.stop();
            alienVoice = null;
        }
    }

    private static AudioNode node(byte[] signed, int period, String name) {
        ByteBuffer pcm = BufferUtils.createByteBuffer(signed.length);
        for (byte b : signed) {
            pcm.put((byte) (b + 128));
        }
        pcm.flip();
        AudioBuffer buffer = new AudioBuffer();
        buffer.setupFormat(1, 8, (int) Math.round(PAULA_CLOCK / period));
        buffer.updateData(pcm);
        AudioNode node = new AudioNode(buffer, new AudioKey(name));
        node.setPositional(false);
        return node;
    }

    void stopAll() {
        if (alienVoice != null) {
            alienVoice.stop();
            alienVoice = null;
        }
        nodes.values().forEach(n -> {
            if (n != null) {
                n.stop();
            }
        });
        door = null;
    }
}
