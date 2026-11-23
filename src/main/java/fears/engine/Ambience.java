package fears.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Les deux ambiances sonores d'un jeu de murs (MOTEUR.md « acteurs » §6.4), jouées en boucle
 * sur les voies 2 et 3.
 *
 * <pre>
 * +0x10000  mots len1, len2, loop1, loop2
 *           len1 + len2 octets codés en delta (une seule somme continue)
 *           enveloppe voie 2 : triplets (trame, volume, période) … mot $FFFF
 *           enveloppe voie 3 : idem
 * </pre>
 *
 * <p>Seule la partie qui suit {@code loop + 200} octets est jouée, en boucle continue : voie 2
 * de {@code loop1 + 200} à {@code len1}, voie 3 de {@code len1 + loop2 + 200} à
 * {@code len1 + len2}. À chaque VBL, volume et période suivent leur enveloppe par interpolation
 * linéaire ; à la dernière entrée, l'enveloppe repart de la première.
 */
public final class Ambience {

    static final int OFFSET = 0x10000;
    static final int SKIP = 200;

    /** Une voie : l'échantillon joué en boucle et son enveloppe. */
    public static final class Channel {
        private final byte[] pcm;
        private final int[][] envelope;
        private int entry;
        private int clock;

        Channel(byte[] pcm, int[][] envelope) {
            this.pcm = pcm;
            this.envelope = envelope;
        }

        /** Échantillon 8 bits signé, joué en boucle. */
        public byte[] pcm() {
            return pcm;
        }

        /** Période de la première entrée : celle à laquelle l'échantillon est préparé. */
        public int basePeriod() {
            return envelope.length > 0 && envelope[0][2] > 0 ? envelope[0][2] : 400;
        }

        /** Une VBL d'enveloppe. */
        void tick() {
            if (envelope.length == 0) {
                return;
            }
            clock++;
            if (entry + 1 < envelope.length && clock >= envelope[entry + 1][0]) {
                entry++;
            }
            if (entry == envelope.length - 1) {
                entry = 0;
                clock = 0;
            }
        }

        private double interpolate(int field) {
            if (envelope.length == 0) {
                return 0;
            }
            int[] a = envelope[entry];
            if (entry + 1 >= envelope.length) {
                return a[field];
            }
            int[] b = envelope[entry + 1];
            double t = (clock - a[0]) / (double) Math.max(1, b[0] - a[0]);
            return a[field] + (b[field] - a[field]) * t;
        }

        /** Volume courant, 0 à 64. */
        public double volume() {
            return interpolate(1);
        }

        /** Période Paula courante. */
        public double period() {
            return interpolate(2);
        }
    }

    private final Channel[] channels = new Channel[2];

    /** @param walls le fichier de murs brut ({@code raw/<jeu>.bin}) */
    public Ambience(byte[] walls) {
        int len1 = word(walls, OFFSET);
        int len2 = word(walls, OFFSET + 2);
        int loop1 = word(walls, OFFSET + 4);
        int loop2 = word(walls, OFFSET + 6);
        int start = OFFSET + 8;
        byte[] pcm = new byte[len1 + len2];
        int sum = 0;
        for (int i = 0; i < pcm.length && start + i < walls.length; i++) {
            sum += walls[start + i];
            pcm[i] = (byte) sum;
        }
        int at = start + len1 + len2;
        List<int[]> first = new ArrayList<>();
        at = envelope(walls, at, first);
        List<int[]> second = new ArrayList<>();
        envelope(walls, at, second);
        channels[0] = new Channel(Arrays.copyOfRange(pcm, Math.min(len1, loop1 + SKIP), len1),
                first.toArray(int[][]::new));
        channels[1] = new Channel(Arrays.copyOfRange(pcm, Math.min(len1 + len2, len1 + loop2 + SKIP), len1 + len2),
                second.toArray(int[][]::new));
    }

    private static int envelope(byte[] d, int at, List<int[]> out) {
        while (at + 2 <= d.length && word(d, at) != 0xFFFF) {
            out.add(new int[]{word(d, at), word(d, at + 2), word(d, at + 4)});
            at += 6;
        }
        return at + 2;
    }

    private static int word(byte[] d, int at) {
        return at + 1 < d.length ? ((d[at] & 0xFF) << 8) | (d[at + 1] & 0xFF) : 0;
    }

    public Channel channel(int index) {
        return channels[index];
    }

    /** Une VBL : les deux enveloppes avancent (y compris pendant la carte). */
    public void tick() {
        channels[0].tick();
        channels[1].tick();
    }
}
