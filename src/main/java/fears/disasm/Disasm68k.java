package fears.disasm;

import java.util.ArrayList;
import java.util.List;

/**
 * Désassembleur 68000/68020 (jeu entier, sans FPU/MMU), syntaxe Motorola.
 *
 * Usage : {@code new Disasm68k(mem, base).decode(addr)} renvoie une {@link Insn}. Les opcodes inconnus
 * ou invalides donnent {@code dc.w $xxxx} sur 2 octets. Les adresses absolues ou relatives au PC
 * rencontrées sont collectées dans {@link Insn#refs} pour poser des étiquettes.
 */
public final class Disasm68k {

    public static final class Insn {
        public final int addr;
        public int size;
        public String mnemonic;
        public String operands = "";
        /** Adresses référencées (cibles de branchement, opérandes absolues/PC-relatives). */
        public final List<Integer> refs = new ArrayList<>();
        /** Cible de branchement/saut direct, ou −1. */
        public int branchTarget = -1;
        /** Fin de flot : rts, rte, rtr, bra, jmp. */
        public boolean endsFlow;
        public boolean valid = true;

        Insn(int addr) {
            this.addr = addr;
        }

        @Override
        public String toString() {
            return operands.isEmpty() ? mnemonic : mnemonic + " " + operands;
        }
    }

    /** Pour substituer des étiquettes aux adresses (renvoie null pour garder l'hexadécimal). */
    public interface Labeler {
        String label(int addr);
    }

    private final byte[] mem;
    private final int base;
    private Labeler labeler = a -> null;

    // état de l'instruction en cours
    private int pc;
    private Insn cur;

    public Disasm68k(byte[] mem, int base) {
        this.mem = mem;
        this.base = base;
    }

    public void setLabeler(Labeler l) {
        this.labeler = l;
    }

    private static final class Bad extends RuntimeException {
        Bad() {
            super(null, null, false, false);
        }
    }

    private int w() {
        int o = pc - base;
        if (o < 0 || o + 2 > mem.length) {
            throw new Bad();
        }
        pc += 2;
        return ((mem[o] & 0xff) << 8) | (mem[o + 1] & 0xff);
    }

    private int l() {
        return (w() << 16) | w();
    }

    public Insn decode(int addr) {
        cur = new Insn(addr);
        pc = addr;
        try {
            decodeOne(w());
        } catch (Bad e) {
            cur.valid = false;
        }
        if (!cur.valid) {
            pc = addr;
            int op;
            try {
                op = w();
            } catch (Bad e) {
                op = 0;
            }
            cur = new Insn(addr);
            cur.mnemonic = "dc.w";
            cur.operands = hex16(op);
            cur.valid = false;
            pc = addr + 2;
        }
        cur.size = pc - addr;
        return cur;
    }

    // ------------------------------------------------------------------ formatage

    private static String hex(int v) {
        return "$" + Integer.toHexString(v);
    }

    private static String hex16(int v) {
        return "$" + Integer.toHexString(v & 0xffff);
    }

    private static String sdisp(int v) {
        return v < 0 ? "-$" + Integer.toHexString(-v) : "$" + Integer.toHexString(v);
    }

    private String addrRef(int a) {
        cur.refs.add(a);
        String lb = labeler.label(a);
        return lb != null ? lb : hex(a);
    }

    private static final String[] SZ = {".b", ".w", ".l"};
    private static final String[] CC = {"t", "f", "hi", "ls", "cc", "cs", "ne", "eq", "vc", "vs", "pl", "mi", "ge", "lt", "gt", "le"};

    private void set(String mn, String ops) {
        cur.mnemonic = mn;
        cur.operands = ops;
    }

    private static Bad bad() {
        return new Bad();
    }

    // ------------------------------------------------------------------ modes d'adressage

    private static final int SZ_B = 0, SZ_W = 1, SZ_L = 2;

    /** Masques de modes autorisés. */
    private static final int M_DN = 1, M_AN = 2, M_IND = 4, M_POST = 8, M_PRE = 16, M_D16 = 32, M_IDX = 64,
            M_ABSW = 128, M_ABSL = 256, M_PCD = 512, M_PCI = 1024, M_IMM = 2048;
    private static final int ALL = 4095, DATA = ALL & ~M_AN, MEM = ALL & ~(M_DN | M_AN),
            ALTER = M_DN | M_AN | M_IND | M_POST | M_PRE | M_D16 | M_IDX | M_ABSW | M_ABSL,
            DATA_ALT = ALTER & ~M_AN, MEM_ALT = ALTER & ~(M_DN | M_AN),
            CTRL = M_IND | M_D16 | M_IDX | M_ABSW | M_ABSL | M_PCD | M_PCI, CTRL_ALT = CTRL & ~(M_PCD | M_PCI);

    private static int modeBit(int mode, int reg) {
        if (mode < 7) {
            return 1 << mode;
        }
        return switch (reg) {
            case 0 -> M_ABSW;
            case 1 -> M_ABSL;
            case 2 -> M_PCD;
            case 3 -> M_PCI;
            case 4 -> M_IMM;
            default -> 0;
        };
    }

    private void check(int mode, int reg, int allowed) {
        if ((modeBit(mode, reg) & allowed) == 0) {
            throw bad();
        }
    }

    private String ea(int mode, int reg, int size, int allowed) {
        check(mode, reg, allowed);
        return ea(mode, reg, size);
    }

    private String ea(int mode, int reg, int size) {
        switch (mode) {
            case 0:
                return "d" + reg;
            case 1:
                return "a" + reg;
            case 2:
                return "(a" + reg + ")";
            case 3:
                return "(a" + reg + ")+";
            case 4:
                return "-(a" + reg + ")";
            case 5:
                return sdisp((short) w()) + "(a" + reg + ")";
            case 6:
                return indexed("a" + reg, -1);
            default:
                break;
        }
        switch (reg) {
            case 0: {
                int a = (short) w();
                return addrRef(a) + ".w";
            }
            case 1:
                return addrRef(l()) + ".l";
            case 2: {
                int p = pc;
                int t = p + (short) w();
                return addrRef(t) + "(pc)";
            }
            case 3:
                return indexed("pc", pc);
            case 4:
                return switch (size) {
                    case SZ_B -> "#" + hex(immByte());
                    case SZ_W -> "#" + hex(w());
                    default -> "#" + hex(l());
                };
            default:
                throw bad();
        }
    }

    /** Index court (68000) ou complet (68020). {@code pcBase} ≥ 0 pour PC-relatif. */
    /** Immédiat octet : le mot d'extension doit avoir son octet fort nul (ou $FF pour une valeur signée). */
    private int immByte() {
        int v = w();
        if ((v & 0xff00) != 0 && (v & 0xff00) != 0xff00) {
            throw bad();
        }
        return v & 0xff;
    }

    private String indexed(String baseReg, int pcBase) {
        int ext = w();
        String xn = ((ext & 0x8000) != 0 ? "a" : "d") + ((ext >> 12) & 7) + ((ext & 0x800) != 0 ? ".l" : ".w");
        int scale = (ext >> 9) & 3;
        if (scale != 0) {
            xn += "*" + (1 << scale);
        }
        if ((ext & 0x100) == 0) {
            int d8 = (byte) ext;
            if (pcBase >= 0) {
                return addrRef(pcBase + d8) + "(pc," + xn + ")";
            }
            return sdisp(d8) + "(" + baseReg + "," + xn + ")";
        }
        // format complet
        boolean bs = (ext & 0x80) != 0, is = (ext & 0x40) != 0;
        int bdSize = (ext >> 4) & 3, iis = ext & 7;
        if (bdSize == 0 || (ext & 8) != 0) {
            throw bad();
        }
        int bd = switch (bdSize) {
            case 2 -> (short) w();
            case 3 -> l();
            default -> 0;
        };
        String bdS;
        if (pcBase >= 0 && !bs) {
            bdS = addrRef(pcBase + bd);
        } else {
            bdS = bdSize == 1 ? "" : sdisp(bd);
        }
        String b = bs ? (pcBase >= 0 ? "zpc" : "") : (pcBase >= 0 ? "pc" : baseReg);
        String x = is ? "" : xn;
        if (is && iis > 3) {
            throw bad();
        }
        if (iis == 0) {
            return "(" + join(bdS, b, x) + ")";
        }
        int od = switch (iis & 3) {
            case 2 -> (short) w();
            case 3 -> l();
            default -> 0;
        };
        String odS = (iis & 3) <= 1 ? "" : sdisp(od);
        if (iis == 4) {
            throw bad();
        }
        if (iis < 4) {
            return "([" + join(bdS, b, x) + "]" + (odS.isEmpty() ? "" : "," + odS) + ")";
        }
        return "([" + join(bdS, b) + "]," + join(x, odS) + ")";
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private static int mode(int op) {
        return (op >> 3) & 7;
    }

    private static int reg(int op) {
        return op & 7;
    }

    private static int size2(int op) {
        int s = (op >> 6) & 3;
        if (s == 3) {
            throw bad();
        }
        return s;
    }

    private String eaOp(int op, int size, int allowed) {
        return ea(mode(op), reg(op), size, allowed);
    }

    // ------------------------------------------------------------------ décodage

    private void decodeOne(int op) {
        switch (op >>> 12) {
            case 0x0 -> group0(op);
            case 0x1 -> move(op, SZ_B);
            case 0x2 -> move(op, SZ_L);
            case 0x3 -> move(op, SZ_W);
            case 0x4 -> group4(op);
            case 0x5 -> group5(op);
            case 0x6 -> branch(op);
            case 0x7 -> {
                if ((op & 0x100) != 0) {
                    throw bad();
                }
                set("moveq", "#" + sdisp((byte) op) + ",d" + ((op >> 9) & 7));
            }
            case 0x8 -> group8(op);
            case 0x9 -> addSub(op, "sub");
            case 0xb -> groupB(op);
            case 0xc -> groupC(op);
            case 0xd -> addSub(op, "add");
            case 0xe -> groupE(op);
            default -> throw bad(); // line A / line F (FPU non géré)
        }
    }

    private void move(int op, int size) {
        int dmode = (op >> 6) & 7, dreg = (op >> 9) & 7;
        String src = ea(mode(op), reg(op), size, size == SZ_B ? ALL & ~M_AN : ALL);
        if (dmode == 1) {
            if (size == SZ_B) {
                throw bad();
            }
            set("movea" + SZ[size], src + ",a" + dreg);
            return;
        }
        String dst = ea(dmode, dreg, size, DATA_ALT);
        set("move" + SZ[size], src + "," + dst);
    }

    private void group0(int op) {
        if ((op & 0x100) != 0) {
            if (mode(op) == 1) { // movep
                int dreg = (op >> 9) & 7, ar = reg(op);
                String sz = (op & 0x40) != 0 ? ".l" : ".w";
                String m = sdisp((short) w()) + "(a" + ar + ")";
                if ((op & 0x80) != 0) {
                    set("movep" + sz, "d" + dreg + "," + m);
                } else {
                    set("movep" + sz, m + ",d" + dreg);
                }
                return;
            }
            String[] bn = {"btst", "bchg", "bclr", "bset"};
            int t = (op >> 6) & 3;
            String dst = eaOp(op, SZ_B, t == 0 ? DATA : DATA_ALT);
            set(bn[t], "d" + ((op >> 9) & 7) + "," + dst);
            return;
        }
        int hi = (op >> 9) & 7;
        if (hi == 4) { // bit statique
            String[] bn = {"btst", "bchg", "bclr", "bset"};
            int t = (op >> 6) & 3;
            int ext = w();
            if ((ext & 0xff00) != 0) {
                throw bad();
            }
            int bit = ext & 0xff;
            String dst = eaOp(op, SZ_B, t == 0 ? DATA & ~M_IMM : DATA_ALT);
            set(bn[t], "#" + bit + "," + dst);
            return;
        }
        if ((op & 0xf9c0) == 0x00c0 && hi <= 2) { // cmp2/chk2
            int sz = (op >> 9) & 3;
            int ext = w();
            if ((ext & 0x07ff) != 0) {
                throw bad();
            }
            String r = ((ext & 0x8000) != 0 ? "a" : "d") + ((ext >> 12) & 7);
            String e = eaOp(op, sz, CTRL);
            set(((ext & 0x800) != 0 ? "chk2" : "cmp2") + SZ[sz], e + "," + r);
            return;
        }
        if ((op & 0xf9c0) == 0x08c0 && hi >= 5) { // cas
            int sz = ((op >> 9) & 3) - 1;
            int ext = w();
            if ((ext & 0xfe38) != 0) {
                throw bad();
            }
            String e = eaOp(op, sz, MEM_ALT);
            set("cas" + SZ[sz], "d" + (ext & 7) + ",d" + ((ext >> 6) & 7) + "," + e);
            return;
        }
        if (hi == 7) { // moves
            int sz = size2(op);
            int ext = w();
            if ((ext & 0x07ff) != 0) {
                throw bad();
            }
            String r = ((ext & 0x8000) != 0 ? "a" : "d") + ((ext >> 12) & 7);
            String e = eaOp(op, sz, MEM_ALT);
            set("moves" + SZ[sz], (ext & 0x800) != 0 ? r + "," + e : e + "," + r);
            return;
        }
        String[] names = {"ori", "andi", "subi", "addi", null, "eori", "cmpi", null};
        String n = names[hi];
        if (n == null) {
            throw bad();
        }
        int sz = size2(op);
        if (mode(op) == 7 && reg(op) == 4 && (hi == 0 || hi == 1 || hi == 5)) { // vers CCR / SR
            if (sz == SZ_B) {
                int imm = w();
                if ((imm & 0xff00) != 0) {
                    throw bad();
                }
                set(n, "#" + hex(imm) + ",ccr");
                return;
            }
            if (sz == SZ_W) {
                set(n, "#" + hex(w()) + ",sr");
                return;
            }
            throw bad();
        }
        String imm = sz == SZ_B ? "#" + hex(immByte()) : sz == SZ_W ? "#" + hex(w()) : "#" + hex(l());
        String dst = eaOp(op, sz, hi == 6 ? DATA & ~M_IMM : DATA_ALT);
        set(n + SZ[sz], imm + "," + dst);
    }

    private static String regList(int mask, boolean reversed) {
        if (reversed) {
            mask = Integer.reverse(mask) >>> 16;
        }
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < 2; g++) {
            char c = g == 0 ? 'd' : 'a';
            int i = 0;
            while (i < 8) {
                if ((mask & (1 << (g * 8 + i))) != 0) {
                    int j = i;
                    while (j + 1 < 8 && (mask & (1 << (g * 8 + j + 1))) != 0) {
                        j++;
                    }
                    if (sb.length() > 0) {
                        sb.append('/');
                    }
                    sb.append(c).append(i);
                    if (j > i) {
                        sb.append('-').append(c).append(j);
                    }
                    i = j + 1;
                } else {
                    i++;
                }
            }
        }
        return sb.length() == 0 ? "#0" : sb.toString();
    }

    private void group4(int op) {
        switch (op) {
            case 0x4afc -> {
                set("illegal", "");
                return;
            }
            case 0x4e70 -> {
                set("reset", "");
                return;
            }
            case 0x4e71 -> {
                set("nop", "");
                return;
            }
            case 0x4e72 -> {
                set("stop", "#" + hex(w()));
                return;
            }
            case 0x4e73 -> {
                set("rte", "");
                cur.endsFlow = true;
                return;
            }
            case 0x4e74 -> {
                set("rtd", "#" + sdisp((short) w()));
                cur.endsFlow = true;
                return;
            }
            case 0x4e75 -> {
                set("rts", "");
                cur.endsFlow = true;
                return;
            }
            case 0x4e76 -> {
                set("trapv", "");
                return;
            }
            case 0x4e77 -> {
                set("rtr", "");
                cur.endsFlow = true;
                return;
            }
            default -> {
            }
        }
        if ((op & 0xfff0) == 0x4e40) {
            set("trap", "#" + (op & 15));
            return;
        }
        if ((op & 0xfff8) == 0x4e50) {
            set("link.w", "a" + reg(op) + ",#" + sdisp((short) w()));
            return;
        }
        if ((op & 0xfff8) == 0x4808) {
            set("link.l", "a" + reg(op) + ",#" + sdisp(l()));
            return;
        }
        if ((op & 0xfff8) == 0x4e58) {
            set("unlk", "a" + reg(op));
            return;
        }
        if ((op & 0xfff0) == 0x4e60) {
            set("move.l", (op & 8) == 0 ? "a" + reg(op) + ",usp" : "usp,a" + reg(op));
            return;
        }
        if ((op & 0xfffe) == 0x4e7a) { // movec
            int ext = w();
            String r = ((ext & 0x8000) != 0 ? "a" : "d") + ((ext >> 12) & 7);
            String cr = switch (ext & 0xfff) {
                case 0x000 -> "sfc";
                case 0x001 -> "dfc";
                case 0x002 -> "cacr";
                case 0x800 -> "usp";
                case 0x801 -> "vbr";
                case 0x802 -> "caar";
                case 0x803 -> "msp";
                case 0x804 -> "isp";
                default -> hex(ext & 0xfff);
            };
            set("movec", (op & 1) == 0 ? cr + "," + r : r + "," + cr);
            return;
        }
        if ((op & 0xfff8) == 0x4840) {
            set("swap", "d" + reg(op));
            return;
        }
        if ((op & 0xfff8) == 0x4848) {
            set("bkpt", "#" + reg(op));
            return;
        }
        if ((op & 0xfff8) == 0x4880) {
            set("ext.w", "d" + reg(op));
            return;
        }
        if ((op & 0xfff8) == 0x48c0) {
            set("ext.l", "d" + reg(op));
            return;
        }
        if ((op & 0xfff8) == 0x49c0) {
            set("extb.l", "d" + reg(op));
            return;
        }
        if ((op & 0xffc0) == 0x4ec0) {
            String e = eaOp(op, SZ_L, CTRL);
            set("jmp", e);
            cur.endsFlow = true;
            if (mode(op) == 7 && reg(op) <= 2 && !cur.refs.isEmpty()) {
                cur.branchTarget = cur.refs.get(cur.refs.size() - 1);
            }
            return;
        }
        if ((op & 0xffc0) == 0x4e80) {
            String e = eaOp(op, SZ_L, CTRL);
            set("jsr", e);
            if (mode(op) == 7 && reg(op) <= 2 && !cur.refs.isEmpty()) {
                cur.branchTarget = cur.refs.get(cur.refs.size() - 1);
            }
            return;
        }
        if ((op & 0xffc0) == 0x4840) {
            set("pea", eaOp(op, SZ_L, CTRL));
            return;
        }
        if ((op & 0xffc0) == 0x4800) {
            set("nbcd", eaOp(op, SZ_B, DATA_ALT));
            return;
        }
        if ((op & 0xffc0) == 0x4ac0) {
            set("tas", eaOp(op, SZ_B, DATA_ALT));
            return;
        }
        if ((op & 0xffc0) == 0x40c0) {
            set("move.w", "sr," + eaOp(op, SZ_W, DATA_ALT));
            return;
        }
        if ((op & 0xffc0) == 0x42c0) {
            set("move.w", "ccr," + eaOp(op, SZ_W, DATA_ALT));
            return;
        }
        if ((op & 0xffc0) == 0x44c0) {
            set("move.w", eaOp(op, SZ_W, DATA) + ",ccr");
            return;
        }
        if ((op & 0xffc0) == 0x46c0) {
            set("move.w", eaOp(op, SZ_W, DATA) + ",sr");
            return;
        }
        if ((op & 0xfb80) == 0x4880) { // movem
            boolean toReg = (op & 0x400) != 0;
            int sz = (op & 0x40) != 0 ? SZ_L : SZ_W;
            int mask = w();
            if (toReg) {
                String e = eaOp(op, sz, CTRL | M_POST);
                set("movem" + SZ[sz], e + "," + regList(mask, false));
            } else {
                String e = eaOp(op, sz, CTRL_ALT | M_PRE);
                set("movem" + SZ[sz], regList(mask, mode(op) == 4) + "," + e);
            }
            return;
        }
        if ((op & 0xff80) == 0x4c00) { // mul.l / div.l
            int ext = w();
            if ((ext & 0x83f8) != 0) {
                throw bad();
            }
            boolean div = (op & 0x40) != 0;
            boolean signed = (ext & 0x800) != 0;
            boolean quad = (ext & 0x400) != 0;
            int dl = (ext >> 12) & 7, dh = ext & 7;
            String e = eaOp(op, SZ_L, DATA);
            String mn = (div ? "div" : "mul") + (signed ? "s" : "u");
            if (div && !quad && dh != dl) {
                set(mn + "l.l", e + ",d" + dh + ":d" + dl);
            } else if (quad) {
                set(mn + ".l", e + ",d" + dh + ":d" + dl);
            } else {
                set(mn + ".l", e + ",d" + dl);
            }
            return;
        }
        if ((op & 0xf1c0) == 0x41c0) {
            set("lea", eaOp(op, SZ_L, CTRL) + ",a" + ((op >> 9) & 7));
            return;
        }
        if ((op & 0xf140) == 0x4100) { // chk
            int sz = (op & 0x80) != 0 ? SZ_W : SZ_L;
            set("chk" + SZ[sz], eaOp(op, sz, DATA) + ",d" + ((op >> 9) & 7));
            return;
        }
        int sub = (op >> 8) & 0xf;
        String n = switch (sub) {
            case 0x0 -> "negx";
            case 0x2 -> "clr";
            case 0x4 -> "neg";
            case 0x6 -> "not";
            case 0xa -> "tst";
            default -> null;
        };
        if (n != null) {
            int sz = size2(op);
            set(n + SZ[sz], eaOp(op, sz, sub == 0xa ? ALL : DATA_ALT));
            return;
        }
        throw bad();
    }

    private void group5(int op) {
        if ((op & 0xc0) == 0xc0) {
            int cc = (op >> 8) & 15;
            if (mode(op) == 1) {
                int p = pc;
                int t = p + (short) w();
                set("db" + (cc == 1 ? "ra" : CC[cc]), "d" + reg(op) + "," + addrRef(t));
                cur.branchTarget = t;
                return;
            }
            if (mode(op) == 7 && reg(op) >= 2 && reg(op) <= 4) {
                switch (reg(op)) {
                    case 2 -> set("trap" + CC[cc] + ".w", "#" + hex(w()));
                    case 3 -> set("trap" + CC[cc] + ".l", "#" + hex(l()));
                    default -> set("trap" + CC[cc], "");
                }
                return;
            }
            set("s" + CC[cc] + ".b", eaOp(op, SZ_B, DATA_ALT));
            return;
        }
        int sz = size2(op);
        int q = (op >> 9) & 7;
        if (q == 0) {
            q = 8;
        }
        if (mode(op) == 1 && sz == SZ_B) {
            throw bad();
        }
        set(((op & 0x100) != 0 ? "subq" : "addq") + SZ[sz], "#" + q + "," + eaOp(op, sz, ALTER));
    }

    private void branch(int op) {
        int cc = (op >> 8) & 15;
        int p = pc;
        int d = (byte) op;
        String suffix = ".b";
        if (d == 0) {
            d = (short) w();
            suffix = ".w";
        } else if (d == -1) {
            d = l();
            suffix = ".l";
        }
        int t = p + d;
        String mn = cc == 0 ? "bra" : cc == 1 ? "bsr" : "b" + CC[cc];
        set(mn + suffix, addrRef(t));
        cur.branchTarget = t;
        if (cc == 0) {
            cur.endsFlow = true;
        }
    }

    private void group8(int op) {
        int dreg = (op >> 9) & 7;
        if ((op & 0x1c0) == 0xc0) {
            set("divu.w", eaOp(op, SZ_W, DATA) + ",d" + dreg);
            return;
        }
        if ((op & 0x1c0) == 0x1c0) {
            set("divs.w", eaOp(op, SZ_W, DATA) + ",d" + dreg);
            return;
        }
        if ((op & 0x1f0) == 0x100) {
            set("sbcd", (op & 8) == 0 ? "d" + reg(op) + ",d" + dreg : "-(a" + reg(op) + "),-(a" + dreg + ")");
            return;
        }
        if ((op & 0x1f0) == 0x140 || (op & 0x1f0) == 0x180) {
            String mn = (op & 0x1f0) == 0x140 ? "pack" : "unpk";
            String ops = (op & 8) == 0 ? "d" + reg(op) + ",d" + dreg : "-(a" + reg(op) + "),-(a" + dreg + ")";
            set(mn, ops + ",#" + hex(w()));
            return;
        }
        logic(op, "or");
    }

    private void logic(int op, String n) {
        int dreg = (op >> 9) & 7;
        int sz = size2(op);
        if ((op & 0x100) == 0) {
            set(n + SZ[sz], eaOp(op, sz, DATA) + ",d" + dreg);
        } else {
            set(n + SZ[sz], "d" + dreg + "," + eaOp(op, sz, MEM_ALT));
        }
    }

    private void addSub(int op, String n) {
        int r = (op >> 9) & 7;
        int s = (op >> 6) & 3;
        if (s == 3) { // adda/suba
            int sz = (op & 0x100) != 0 ? SZ_L : SZ_W;
            set(n + "a" + SZ[sz], eaOp(op, sz, ALL) + ",a" + r);
            return;
        }
        if ((op & 0x130) == 0x100) { // addx/subx
            set(n + "x" + SZ[s], (op & 8) == 0 ? "d" + reg(op) + ",d" + r : "-(a" + reg(op) + "),-(a" + r + ")");
            return;
        }
        if ((op & 0x100) == 0) {
            if (s == SZ_B && mode(op) == 1) {
                throw bad();
            }
            set(n + SZ[s], eaOp(op, s, ALL) + ",d" + r);
        } else {
            set(n + SZ[s], "d" + r + "," + eaOp(op, s, MEM_ALT));
        }
    }

    private void groupB(int op) {
        int r = (op >> 9) & 7;
        int s = (op >> 6) & 3;
        if (s == 3) {
            int sz = (op & 0x100) != 0 ? SZ_L : SZ_W;
            set("cmpa" + SZ[sz], eaOp(op, sz, ALL) + ",a" + r);
            return;
        }
        if ((op & 0x100) == 0) {
            if (s == SZ_B && mode(op) == 1) {
                throw bad();
            }
            set("cmp" + SZ[s], eaOp(op, s, ALL) + ",d" + r);
            return;
        }
        if (mode(op) == 1) {
            set("cmpm" + SZ[s], "(a" + reg(op) + ")+,(a" + r + ")+");
            return;
        }
        set("eor" + SZ[s], "d" + r + "," + eaOp(op, s, DATA_ALT));
    }

    private void groupC(int op) {
        int r = (op >> 9) & 7;
        if ((op & 0x1c0) == 0xc0) {
            set("mulu.w", eaOp(op, SZ_W, DATA) + ",d" + r);
            return;
        }
        if ((op & 0x1c0) == 0x1c0) {
            set("muls.w", eaOp(op, SZ_W, DATA) + ",d" + r);
            return;
        }
        if ((op & 0x1f0) == 0x100) {
            set("abcd", (op & 8) == 0 ? "d" + reg(op) + ",d" + r : "-(a" + reg(op) + "),-(a" + r + ")");
            return;
        }
        switch (op & 0x1f8) {
            case 0x140 -> {
                set("exg", "d" + r + ",d" + reg(op));
                return;
            }
            case 0x148 -> {
                set("exg", "a" + r + ",a" + reg(op));
                return;
            }
            case 0x188 -> {
                set("exg", "d" + r + ",a" + reg(op));
                return;
            }
            default -> {
            }
        }
        logic(op, "and");
    }

    private void groupE(int op) {
        String[] names = {"as", "ls", "rox", "ro"};
        if ((op & 0xc0) == 0xc0) {
            if ((op & 0x800) != 0) { // champs de bits 68020
                bitfield(op);
                return;
            }
            int t = (op >> 9) & 3;
            set(names[t] + ((op & 0x100) != 0 ? "l" : "r") + ".w", eaOp(op, SZ_W, MEM_ALT));
            return;
        }
        int sz = size2(op);
        int t = (op >> 3) & 3;
        int cnt = (op >> 9) & 7;
        String src = (op & 0x20) != 0 ? "d" + cnt : "#" + (cnt == 0 ? 8 : cnt);
        set(names[t] + ((op & 0x100) != 0 ? "l" : "r") + SZ[sz], src + ",d" + reg(op));
    }

    private void bitfield(int op) {
        String[] names = {"bftst", "bfextu", "bfchg", "bfexts", "bfclr", "bfffo", "bfset", "bfins"};
        int t = (op >> 8) & 7;
        int ext = w();
        String off = (ext & 0x800) != 0 ? "d" + ((ext >> 6) & 7) : String.valueOf((ext >> 6) & 31);
        String wid = (ext & 0x20) != 0 ? "d" + (ext & 7) : String.valueOf((ext & 31) == 0 ? 32 : ext & 31);
        boolean readOnly = t == 0 || t == 1 || t == 3 || t == 5;
        String e = eaOp(op, SZ_L, readOnly ? (CTRL | M_DN) : (CTRL_ALT | M_DN)) + "{" + off + ":" + wid + "}";
        String dn = "d" + ((ext >> 12) & 7);
        switch (t) {
            case 1, 3, 5 -> set(names[t], e + "," + dn);
            case 7 -> set(names[t], dn + "," + e);
            default -> set(names[t], e);
        }
    }
}
