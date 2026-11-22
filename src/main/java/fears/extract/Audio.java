package fears.extract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Sons : IFF 8SVX intégrés dans les exécutables → WAV. */
final class Audio {
    private Audio() {
    }

    record Sample(int offset, String name, int rate, byte[] pcmSigned, byte[] iff) {
    }

    /** Cherche tous les {@code FORM....8SVX} et en extrait le corps (BODY) et la fréquence (VHDR). */
    static List<Sample> find8svx(byte[] d) {
        List<Sample> out = new ArrayList<>();
        for (int o = 0; o + 12 <= d.length; o += 2) {
            if (d[o] != 'F' || d[o + 1] != 'O' || d[o + 2] != 'R' || d[o + 3] != 'M') {
                continue;
            }
            if (!"8SVX".equals(new String(d, o + 8, 4, StandardCharsets.ISO_8859_1))) {
                continue;
            }
            int formLen = Be.l(d, o + 4);
            int end = Math.min(d.length, o + 8 + formLen);
            int p = o + 12, rate = 8363;
            String name = null;
            byte[] body = null;
            while (p + 8 <= end) {
                String id = new String(d, p, 4, StandardCharsets.ISO_8859_1);
                int len = Be.l(d, p + 4);
                int data = p + 8;
                int avail = Math.max(0, Math.min(len, d.length - data));
                switch (id) {
                    case "VHDR" -> rate = Be.w(d, data + 12);
                    case "NAME" -> name = new String(d, data, avail, StandardCharsets.ISO_8859_1).replace("\0", "").trim();
                    case "BODY" -> body = java.util.Arrays.copyOfRange(d, data, data + avail);
                    default -> {
                    }
                }
                p = data + len + (len & 1);
            }
            if (body != null) {
                out.add(new Sample(o, name, rate, body, java.util.Arrays.copyOfRange(d, o, end)));
            }
        }
        return out;
    }

    /** WAV mono 8 bits (non signé, comme l'exige le format WAV). */
    static void writeWav(Path p, byte[] signed, int rate) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int n = signed.length;
        b.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        le32(b, 36 + n);
        b.writeBytes("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        le32(b, 16);
        le16(b, 1);
        le16(b, 1);
        le32(b, rate);
        le32(b, rate);
        le16(b, 1);
        le16(b, 8);
        b.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        le32(b, n);
        for (byte s : signed) {
            b.write((s + 128) & 0xff);
        }
        if ((n & 1) != 0) {
            b.write(0);
        }
        Files.write(p, b.toByteArray());
    }

    private static void le32(ByteArrayOutputStream b, int v) {
        b.write(v);
        b.write(v >> 8);
        b.write(v >> 16);
        b.write(v >> 24);
    }

    private static void le16(ByteArrayOutputStream b, int v) {
        b.write(v);
        b.write(v >> 8);
    }
}
