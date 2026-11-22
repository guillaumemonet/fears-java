package fears.audio;

/**
 * Un lecteur ProTracker hors ligne : il rend un morceau entier en seize bits stéréo, que la
 * coquille rejoue ensuite en boucle.
 *
 * <p>Repris du lecteur de Breathless ({@code fov.breathless.gld.sound.ProTrackerPlayer}),
 * adapté au format M.K. standard : boucles d'instrument bornées par leur longueur, et
 * répartition Amiga des voies (0 et 3 à gauche, 1 et 2 à droite), adoucie par un peu de
 * mélange pour l'écoute au casque.
 *
 * <p>Une ligne dure {@code speed} ticks, et un tick {@code 2,5 / bpm} seconde — 1/50 s au
 * tempo par défaut, ce qui est la cadence du lecteur d'origine, appelé dans la VBL.
 */
public final class ModPlayer {

    private static final double PAL_CLOCK = 3_546_895.0;
    private static final int DEFAULT_TEMPO = 125;
    private static final int DEFAULT_SPEED = 6;
    private static final int MAX_VOLUME = 64;
    private static final int MIN_PERIOD = 113;
    private static final int MAX_PERIOD = 856;
    /** Part de chaque voie envoyée du côté opposé. */
    private static final double CROSSFEED = 0.25;
    /** Côté de chaque voie : vrai = gauche. */
    private static final boolean[] LEFT = {true, false, false, true};

    private static final int[] SINE = {
        0, 24, 49, 74, 97, 120, 141, 161, 180, 197, 212, 224, 235, 244, 250, 253,
        255, 253, 250, 244, 235, 224, 212, 197, 180, 161, 141, 120, 97, 74, 49, 24,
    };

    private static final class Channel {
        int sample;
        int period;
        int volume;
        double position;
        double step;
        boolean playing;

        int portamentoTarget;
        int portamentoSpeed;
        int vibratoSpeed;
        int vibratoDepth;
        int vibratoPosition;
        int lastOffset;
        int noteCutTick;
    }

    private final ModModule module;
    private final int sampleRate;

    private ModPlayer(ModModule module, int sampleRate) {
        this.module = module;
        this.sampleRate = sampleRate;
    }

    /**
     * Rend le morceau jusqu'à ce qu'il revienne sur une position déjà jouée.
     *
     * @return échantillons stéréo entrelacés, gauche puis droite
     */
    public static short[] render(ModModule module, int sampleRate, int maxSeconds) {
        return new ModPlayer(module, sampleRate).run(maxSeconds);
    }

    private short[] run(int maxSeconds) {
        Channel[] channels = new Channel[ModModule.CHANNELS];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new Channel();
        }
        int[] sequence = module.sequence();
        int tempo = DEFAULT_TEMPO;
        int speed = DEFAULT_SPEED;

        int limit = sampleRate * maxSeconds;
        short[] out = new short[limit * 2];
        int written = 0;

        int position = 0;
        int row = 0;
        int patternDelay = 0;
        boolean[] visited = new boolean[sequence.length];

        while (position < sequence.length && written < limit) {
            if (row == 0) {
                if (visited[position]) {
                    break;
                }
                visited[position] = true;
            }
            int pattern = sequence[position];
            int nextPosition = position;
            int nextRow = row + 1;
            boolean jumped = false;

            for (int repeat = 0; repeat <= patternDelay && written < limit; repeat++) {
                for (int tick = 0; tick < speed && written < limit; tick++) {
                    if (tick == 0 && repeat == 0) {
                        int[] control = startRow(channels, pattern, row);
                        if (control[0] >= 0) {
                            speed = control[0];
                        }
                        if (control[1] >= 0) {
                            tempo = control[1];
                        }
                        if (control[2] >= 0) {
                            nextPosition = control[2];
                            nextRow = 0;
                            jumped = true;
                        }
                        if (control[3] >= 0) {
                            nextPosition = (control[2] >= 0 ? control[2] : position + 1);
                            nextRow = control[3];
                            jumped = true;
                        }
                        patternDelay = Math.max(patternDelay, control[4]);
                    } else {
                        applyTickEffects(channels, pattern, row, tick);
                    }
                    written += mix(out, written, limit, channels,
                            (int) Math.round(sampleRate * 2.5 / tempo));
                }
            }
            patternDelay = 0;
            if (!jumped && nextRow >= ModModule.ROWS) {
                nextRow = 0;
                nextPosition = position + 1;
            }
            if (nextRow >= ModModule.ROWS) {
                nextRow = 0;
            }
            position = nextPosition;
            row = nextRow;
        }
        short[] trimmed = new short[written * 2];
        System.arraycopy(out, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    private int[] startRow(Channel[] channels, int pattern, int row) {
        int speed = -1;
        int tempo = -1;
        int positionJump = -1;
        int patternBreak = -1;
        int patternDelay = 0;

        for (int c = 0; c < channels.length; c++) {
            Channel channel = channels[c];
            int[] cell = module.cell(pattern, row, c);
            int period = cell[0];
            int sample = cell[1];
            int command = (cell[2] >> 8) & 0xF;
            int data = cell[2] & 0xFF;

            if (sample > 0 && sample <= ModModule.SAMPLES) {
                channel.sample = sample;
                channel.volume = module.sample(sample).volume();
            }
            boolean tonePortamento = command == 0x3 || command == 0x5;
            if (period > 0) {
                if (tonePortamento) {
                    channel.portamentoTarget = period;
                } else {
                    channel.period = period;
                    channel.position = 0;
                    if (command == 0x9) {
                        if (data != 0) {
                            channel.lastOffset = data * 256;
                        }
                        channel.position = channel.lastOffset;
                    }
                    channel.playing = channel.sample > 0;
                    channel.vibratoPosition = 0;
                }
            }
            channel.noteCutTick = -1;

            switch (command) {
                case 0x3 -> {
                    if (data != 0) {
                        channel.portamentoSpeed = data;
                    }
                }
                case 0x4 -> {
                    if ((data >> 4) != 0) {
                        channel.vibratoSpeed = data >> 4;
                    }
                    if ((data & 0xF) != 0) {
                        channel.vibratoDepth = data & 0xF;
                    }
                }
                case 0xB -> positionJump = data;
                case 0xC -> channel.volume = Math.min(MAX_VOLUME, data);
                case 0xD -> patternBreak = (data >> 4) * 10 + (data & 0xF);
                case 0xE -> {
                    int sub = data >> 4;
                    int value = data & 0xF;
                    switch (sub) {
                        case 0x1 -> channel.period = clampPeriod(channel.period - value);
                        case 0x2 -> channel.period = clampPeriod(channel.period + value);
                        case 0xA -> channel.volume = Math.min(MAX_VOLUME, channel.volume + value);
                        case 0xB -> channel.volume = Math.max(0, channel.volume - value);
                        case 0xC -> channel.noteCutTick = value;
                        case 0xE -> patternDelay = Math.max(patternDelay, value);
                        default -> {
                            // Commandes étendues sans effet audible ici.
                        }
                    }
                }
                case 0xF -> {
                    if (data == 0) {
                        speed = 1;
                    } else if (data < 32) {
                        speed = data;
                    } else {
                        tempo = data;
                    }
                }
                default -> {
                    // Effets par tick : plus bas.
                }
            }
            refreshStep(channel, channel.period);
        }
        return new int[]{speed, tempo, positionJump, patternBreak, patternDelay};
    }

    private void applyTickEffects(Channel[] channels, int pattern, int row, int tick) {
        for (int c = 0; c < channels.length; c++) {
            Channel channel = channels[c];
            int effect = module.cell(pattern, row, c)[2];
            int command = (effect >> 8) & 0xF;
            int data = effect & 0xFF;
            int period = channel.period;

            switch (command) {
                case 0x0 -> {
                    if (data != 0) {
                        int step = switch (tick % 3) {
                            case 1 -> data >> 4;
                            case 2 -> data & 0xF;
                            default -> 0;
                        };
                        period = transpose(channel.period, step);
                    }
                }
                case 0x1 -> {
                    channel.period = clampPeriod(channel.period - data);
                    period = channel.period;
                }
                case 0x2 -> {
                    channel.period = clampPeriod(channel.period + data);
                    period = channel.period;
                }
                case 0x3 -> {
                    slideToNote(channel);
                    period = channel.period;
                }
                case 0x4 -> period = vibrato(channel);
                case 0x5 -> {
                    slideToNote(channel);
                    period = channel.period;
                    volumeSlide(channel, data);
                }
                case 0x6 -> {
                    period = vibrato(channel);
                    volumeSlide(channel, data);
                }
                case 0xA -> volumeSlide(channel, data);
                case 0xE -> {
                    int sub = data >> 4;
                    int value = data & 0xF;
                    if (sub == 0x9 && value > 0 && tick % value == 0) {
                        channel.position = 0;
                    } else if (sub == 0xC && tick == channel.noteCutTick) {
                        channel.volume = 0;
                    }
                }
                default -> {
                    // Rien à ce tick.
                }
            }
            refreshStep(channel, period);
        }
    }

    private void slideToNote(Channel channel) {
        if (channel.portamentoTarget == 0) {
            return;
        }
        if (channel.period < channel.portamentoTarget) {
            channel.period = Math.min(channel.portamentoTarget, channel.period + channel.portamentoSpeed);
        } else if (channel.period > channel.portamentoTarget) {
            channel.period = Math.max(channel.portamentoTarget, channel.period - channel.portamentoSpeed);
        }
    }

    private int vibrato(Channel channel) {
        int index = (channel.vibratoPosition >> 2) & 0x1F;
        int amplitude = SINE[index] * channel.vibratoDepth / 128;
        int period = (channel.vibratoPosition < 128)
                ? channel.period + amplitude : channel.period - amplitude;
        channel.vibratoPosition = (channel.vibratoPosition + channel.vibratoSpeed * 4) & 0xFF;
        return clampPeriod(period);
    }

    private static void volumeSlide(Channel channel, int data) {
        int up = data >> 4;
        int down = data & 0xF;
        if (up > 0) {
            channel.volume = Math.min(MAX_VOLUME, channel.volume + up);
        } else if (down > 0) {
            channel.volume = Math.max(0, channel.volume - down);
        }
    }

    private static int transpose(int period, int semitones) {
        if (semitones == 0 || period <= 0) {
            return period;
        }
        return clampPeriod((int) Math.round(period / Math.pow(2.0, semitones / 12.0)));
    }

    private static int clampPeriod(int period) {
        return Math.max(MIN_PERIOD, Math.min(MAX_PERIOD, period));
    }

    private void refreshStep(Channel channel, int period) {
        channel.step = (period <= 0) ? 0 : (PAL_CLOCK / period) / sampleRate;
    }

    /** Mélange un tick ; renvoie le nombre de trames stéréo écrites. */
    private int mix(short[] out, int at, int limit, Channel[] channels, int count) {
        int written = 0;
        for (int i = 0; i < count && at + written < limit; i++) {
            double left = 0;
            double right = 0;
            for (int c = 0; c < channels.length; c++) {
                Channel channel = channels[c];
                if (!channel.playing || channel.sample <= 0 || channel.step <= 0) {
                    continue;
                }
                ModModule.Sample sample = module.sample(channel.sample);
                byte[] pcm = sample.data();
                int end = sample.loops() ? sample.loopEnd() : pcm.length;
                if (end <= 0) {
                    continue;
                }
                if (channel.position >= end) {
                    if (sample.loops() && sample.loopEnd() > sample.loopStart()) {
                        int loopLength = sample.loopEnd() - sample.loopStart();
                        channel.position = sample.loopStart()
                                + (channel.position - sample.loopStart()) % loopLength;
                    } else {
                        channel.playing = false;
                        continue;
                    }
                }
                double value = pcm[(int) channel.position] * channel.volume;
                if (LEFT[c]) {
                    left += value;
                    right += value * CROSSFEED;
                } else {
                    right += value;
                    left += value * CROSSFEED;
                }
                channel.position += channel.step;
            }
            // 2 voies × 128 × 64 × 1,25 = 20 480 au pire par côté : ×1,5 reste dans seize bits.
            out[2 * (at + written)] = clip(left * 1.5);
            out[2 * (at + written) + 1] = clip(right * 1.5);
            written++;
        }
        return written;
    }

    private static short clip(double value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
    }
}
