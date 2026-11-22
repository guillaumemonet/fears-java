package fears.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Un module ProTracker « M.K. » : 31 instruments, 4 voies, motifs de 64 lignes.
 *
 * <p>Les musiques de Fears sont rangées sur disque codées en delta ; l'extracteur les écrit
 * déjà décodées, au format standard.
 */
public final class ModModule {

    public static final int CHANNELS = 4;
    public static final int ROWS = 64;
    public static final int SAMPLES = 31;

    /** Un instrument. Longueurs et boucle en octets. */
    public record Sample(String name, int length, int fineTune, int volume, int loopStart,
                         int loopLength, byte[] data) {

        public boolean loops() {
            return loopLength > 2;
        }

        public int loopEnd() {
            return Math.min(data.length, loopStart + loopLength);
        }
    }

    private final String title;
    private final Sample[] samples;
    private final int[] sequence;
    /** [motif][ligne][voie] = {période, instrument, effet}. */
    private final int[][][][] patterns;

    private ModModule(String title, Sample[] samples, int[] sequence, int[][][][] patterns) {
        this.title = title;
        this.samples = samples;
        this.sequence = sequence;
        this.patterns = patterns;
    }

    public static ModModule read(byte[] data) throws IOException {
        if (data.length < 1084 || !"M.K.".equals(new String(data, 1080, 4, StandardCharsets.ISO_8859_1))) {
            throw new IOException("module ProTracker M.K. attendu");
        }
        String title = text(data, 0, 20);
        int songLength = data[950] & 0xFF;
        int[] sequence = new int[songLength];
        int patternCount = 0;
        for (int i = 0; i < 128; i++) {
            int pattern = data[952 + i] & 0x7F;
            patternCount = Math.max(patternCount, pattern + 1);
            if (i < songLength) {
                sequence[i] = pattern;
            }
        }
        int[][][][] patterns = new int[patternCount][ROWS][CHANNELS][];
        int at = 1084;
        for (int p = 0; p < patternCount; p++) {
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < CHANNELS; c++) {
                    int b0 = data[at] & 0xFF;
                    int b1 = data[at + 1] & 0xFF;
                    int b2 = data[at + 2] & 0xFF;
                    int b3 = data[at + 3] & 0xFF;
                    patterns[p][r][c] = new int[]{
                        ((b0 & 0x0F) << 8) | b1,
                        (b0 & 0xF0) | (b2 >> 4),
                        ((b2 & 0x0F) << 8) | b3,
                    };
                    at += 4;
                }
            }
        }
        Sample[] samples = new Sample[SAMPLES];
        for (int s = 0; s < SAMPLES; s++) {
            int header = 20 + 30 * s;
            int length = word(data, header + 22) * 2;
            int fineTune = data[header + 24] & 0x0F;
            int volume = Math.min(64, data[header + 25] & 0xFF);
            int loopStart = word(data, header + 26) * 2;
            int loopLength = word(data, header + 28) * 2;
            int available = Math.max(0, Math.min(length, data.length - at));
            byte[] pcm = new byte[available];
            System.arraycopy(data, at, pcm, 0, available);
            at += length;
            samples[s] = new Sample(text(data, header, 22), length, fineTune, volume, loopStart,
                    loopLength, pcm);
        }
        return new ModModule(title, samples, sequence, patterns);
    }

    private static int word(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    private static String text(byte[] data, int at, int length) {
        int end = at;
        while (end < at + length && data[end] != 0) {
            end++;
        }
        return new String(data, at, end - at, StandardCharsets.ISO_8859_1).trim();
    }

    public String title() {
        return title;
    }

    public int[] sequence() {
        return sequence.clone();
    }

    /** Instrument numéroté de 1 à 31. */
    public Sample sample(int number) {
        return samples[number - 1];
    }

    public int[] cell(int pattern, int row, int channel) {
        return patterns[pattern][row][channel];
    }
}
