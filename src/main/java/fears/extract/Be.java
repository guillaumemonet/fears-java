package fears.extract;

/** Lecture big-endian (68000). */
final class Be {
    private Be() {
    }

    static int b(byte[] d, int o) {
        return d[o] & 0xff;
    }

    static int w(byte[] d, int o) {
        return ((d[o] & 0xff) << 8) | (d[o + 1] & 0xff);
    }

    static int l(byte[] d, int o) {
        return (w(d, o) << 16) | w(d, o + 2);
    }
}
