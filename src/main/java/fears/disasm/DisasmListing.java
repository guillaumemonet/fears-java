package fears.disasm;

import fears.extract.HunkFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Listing désassemblé d'un exécutable à hunks : séparation code/données par suivi du flot.
 *
 * Graines : le début du premier hunk, puis les cibles de branchement/jsr, puis les adresses chargées
 * par lea/pea/move.l #imm (gestionnaires d'interruption, pointeurs de fonctions) si elles se décodent
 * proprement jusqu'à une fin de flot. Ce qui n'est jamais atteint sort en données (dc.b + ASCII).
 */
public final class DisasmListing {

    private final HunkFile hf;
    private final byte[] img;
    private final int base;
    private final Set<Integer> relocs = new HashSet<>();
    private final TreeMap<Integer, Disasm68k.Insn> code = new TreeMap<>();
    private final Set<Integer> labels = new TreeSet<>();
    private final Disasm68k dis;

    public DisasmListing(HunkFile hf, int base) {
        this.hf = hf;
        hf.layout(base);
        this.img = hf.linkedImage();
        this.base = base;
        this.dis = new Disasm68k(img, base);
        for (HunkFile.Hunk h : hf.hunks) {
            for (var e : h.relocs.entrySet()) {
                for (int off : e.getValue()) {
                    relocs.add(h.base + off);
                }
            }
        }
    }

    private HunkFile.Hunk hunkAt(int a) {
        for (HunkFile.Hunk h : hf.hunks) {
            if (a >= h.base && a < h.base + Math.max(h.data.length, 0)) {
                return h;
            }
        }
        return null;
    }

    private boolean inCodeHunk(int a) {
        HunkFile.Hunk h = hf.hunks.get(0);
        return a >= h.base && a < h.base + h.data.length;
    }

    /** Décode un chemin depuis {@code start} ; renvoie false si une instruction invalide est rencontrée. */
    private boolean trace(int start, Deque<Integer> work, boolean tentative) {
        List<Disasm68k.Insn> path = new ArrayList<>();
        int pc = start;
        while (inCodeHunk(pc) && !code.containsKey(pc)) {
            Disasm68k.Insn i = dis.decode(pc);
            if (!i.valid || pc + i.size > hf.hunks.get(0).base + hf.hunks.get(0).data.length
                    || (tentative && (!plausible(i) || relocInside(pc, i.size)))) {
                if (tentative) {
                    return false;
                }
                break;
            }
            path.add(i);
            if (tentative && path.size() > 4000) {
                return false;
            }
            if (i.endsFlow) {
                break;
            }
            pc += i.size;
        }
        for (Disasm68k.Insn i : path) {
            code.put(i.addr, i);
            if (i.branchTarget >= 0 && inCodeHunk(i.branchTarget)) {
                work.add(i.branchTarget);
            }
        }
        return true;
    }

    public void analyze() {
        Deque<Integer> work = new ArrayDeque<>();
        work.add(hf.hunks.get(0).base);
        boolean changed = true;
        Set<Integer> tried = new HashSet<>();
        while (changed) {
            while (!work.isEmpty()) {
                int a = work.poll();
                if (!code.containsKey(a)) {
                    trace(a, work, false);
                }
            }
            changed = false;
            // graines secondaires : adresses de code chargées comme données par le code atteint
            List<Integer> seeds = new ArrayList<>();
            for (Disasm68k.Insn i : code.values()) {
                String m = i.mnemonic;
                if (m.equals("lea") || m.equals("pea") || m.startsWith("move.l") || m.startsWith("movea.l")) {
                    for (int r : i.refs) {
                        if (inCodeHunk(r) && !code.containsKey(r) && tried.add(r)) {
                            seeds.add(r);
                        }
                    }
                }
            }
            // pointeurs relogés (tables de sauts, vecteurs) visant le hunk de code
            for (int r : relocs) {
                int v = Be32(r);
                if (inCodeHunk(v) && !code.containsKey(v) && !code.containsKey(r) && tried.add(v)) {
                    seeds.add(v);
                }
            }
            for (int s : seeds) {
                if (trace(s, work, true)) {
                    changed = true;
                }
            }
            if (!changed) {
                changed = fillGaps(work);
            }
        }
        for (Disasm68k.Insn i : code.values()) {
            labels.addAll(i.refs);
        }
        // pointeurs relogés dans les données
        for (int r : relocs) {
            if (!code.containsKey(r)) {
                labels.add(Be32(r));
            }
        }
    }

    /**
     * Trous entre deux zones de code : on tente un décodage linéaire depuis le début du trou (et à
     * chaque mot pair suivant une fin de flot). Accepté seulement si au moins 3 instructions valides
     * mènent à un rts/rte/bra/jmp sans toucher un pointeur relogé en plein milieu.
     */
    private boolean fillGaps(Deque<Integer> work) {
        HunkFile.Hunk h = hf.hunks.get(0);
        int end = h.base + h.data.length;
        boolean any = false;
        int pc = h.base;
        while (pc < end) {
            Disasm68k.Insn c = code.get(pc);
            if (c != null) {
                pc += c.size;
                continue;
            }
            // pc est dans un trou : essai
            // seulement au début d'un trou (après une fin de flot), éventuellement après un remplissage nul
            Disasm68k.Insn prev = code.floorEntry(pc - 1) == null ? null : code.floorEntry(pc - 1).getValue();
            boolean gapStart = prev != null && prev.addr + prev.size == pc && prev.endsFlow;
            int p = pc;
            if (gapStart) {
                while (p + 2 <= end && img[p - base] == 0 && img[p - base + 1] == 0 && !code.containsKey(p)) {
                    p += 2; // remplissage d'alignement
                }
            }
            List<Disasm68k.Insn> path = new ArrayList<>();
            boolean ok = false;
            while (gapStart && p < end && !code.containsKey(p)) {
                Disasm68k.Insn i = dis.decode(p);
                if (!i.valid || p + i.size > end || relocInside(p, i.size) || !plausible(i)) {
                    break;
                }
                path.add(i);
                p += i.size;
                if (i.endsFlow) {
                    ok = path.size() >= 4 && !i.mnemonic.startsWith("bra.b");
                    break;
                }
            }
            if (ok) {
                for (Disasm68k.Insn i : path) {
                    code.put(i.addr, i);
                    if (i.branchTarget >= 0 && inCodeHunk(i.branchTarget)) {
                        work.add(i.branchTarget);
                    }
                }
                any = true;
                pc = p;
            } else {
                pc += 2;
            }
        }
        return any;
    }

    private static final java.util.regex.Pattern RARE = java.util.regex.Pattern.compile(
            "^(bf\\w+|cas|cas2|cmp2|chk2|chk|moves|movep|pack|unpk|abcd|sbcd|nbcd|trap\\w\\w.*|bkpt|rtd|rtm|stop|reset|illegal|"
            + "link\\.l|movec|tas|negx|addx|subx|roxl|roxr|divs\\.l|divu\\.l|divsl\\.l|divul\\.l|dc\\.w)(\\..*)?$");

    /** Filtre pour le code deviné : rejette les instructions rares et les adresses absolues improbables. */
    private boolean plausible(Disasm68k.Insn i) {
        if (RARE.matcher(i.mnemonic).matches() || i.operands.contains("usp") || i.operands.contains(",sr")) {
            return false;
        }
        if (i.branchTarget >= 0 && (!inCodeHunk(i.branchTarget) || (i.branchTarget & 1) != 0)) {
            return false;
        }
        for (int r : i.refs) {
            if (r == i.branchTarget) {
                continue;
            }
            boolean inImage = r >= base && r < hf.imageEnd();
            boolean hw = (r >= 0xdff000 && r < 0xdff200) || (r >= 0xbfd000 && r < 0xbff000);
            boolean low = r >= 0 && r < 0x400;
            if (!inImage && !hw && !low) {
                return false;
            }
        }
        return true;
    }

    /** Un long relogé commence-t-il au milieu de l'instruction (hors de ses opérandes 32 bits) ? */
    private boolean relocInside(int at, int size) {
        for (int k = at; k < at + size; k++) {
            if (relocs.contains(k) && (k == at || k + 4 > at + size)) {
                return true;
            }
        }
        return false;
    }

    private int Be32(int a) {
        int o = a - base;
        return ((img[o] & 0xff) << 24) | ((img[o + 1] & 0xff) << 16) | ((img[o + 2] & 0xff) << 8) | (img[o + 3] & 0xff);
    }

    public Map<Integer, Disasm68k.Insn> codeMap() {
        return code;
    }

    public String render(String title) {
        int lo = hf.hunks.get(0).base, hi = hf.imageEnd();
        dis.setLabeler(a -> labels.contains(a) && a >= lo && a < hi ? String.format("L%06x", a) : null);
        StringBuilder sb = new StringBuilder();
        sb.append("; ").append(title).append('\n');
        sb.append("; désassemblé par fears.disasm (68000/68020), image liée à 0x").append(Integer.toHexString(base)).append('\n');
        int codeBytes = code.values().stream().mapToInt(i -> i.size).sum();
        sb.append(String.format("; %d instructions atteintes (%d octets de code)%n", code.size(), codeBytes));
        for (HunkFile.Hunk h : hf.hunks) {
            sb.append(String.format("; hunk %d %s %s : 0x%06x, %d octets (%d initialisés)%n", h.index, h.typeName(), h.memName(),
                    h.base, h.allocSize, h.data.length));
        }
        for (HunkFile.Hunk h : hf.hunks) {
            sb.append(String.format("%n\tsection hunk%d,%s%n", h.index, h.data.length == 0 ? "bss" : h.index == 0 ? "code" : "data"));
            if (h.data.length == 0) {
                for (int a : labels) {
                    if (a >= h.base && a < h.base + h.allocSize) {
                        sb.append(String.format("L%06x\tequ\t$%06x\t; +%d dans le BSS%n", a, a, a - h.base));
                    }
                }
                sb.append(String.format("\tds.b\t%d%n", h.allocSize));
                continue;
            }
            int end = h.base + h.data.length;
            int pc = h.base;
            while (pc < end) {
                Disasm68k.Insn i = code.get(pc);
                if (i != null) {
                    if (labels.contains(pc)) {
                        sb.append(String.format("L%06x:%n", pc));
                    }
                    Disasm68k.Insn r = dis.decode(pc); // re-décodage avec étiquettes
                    StringBuilder hex = new StringBuilder();
                    for (int k = 0; k < Math.min(r.size, 10); k++) {
                        hex.append(String.format("%02x", img[pc - base + k] & 0xff));
                    }
                    String rel = "";
                    for (int k = pc; k < pc + r.size; k++) {
                        if (relocs.contains(k)) {
                            rel = " ; reloc";
                            break;
                        }
                    }
                    sb.append(String.format("\t%-10s %-24s ; %06x %s%s%n", r.mnemonic, r.operands, pc, hex, rel));
                    pc += r.size;
                    continue;
                }
                // données jusqu'au prochain code ou étiquette
                int stop = pc + 1;
                while (stop < end && !code.containsKey(stop) && !labels.contains(stop) && stop - pc < 16) {
                    stop++;
                }
                if (labels.contains(pc)) {
                    sb.append(String.format("L%06x:%n", pc));
                }
                if (relocs.contains(pc) && pc + 4 <= end) {
                    int v = Be32(pc);
                    sb.append(String.format("\tdc.l\t%s\t\t; %06x pointeur%n",
                            labels.contains(v) ? String.format("L%06x", v) : String.format("$%x", v), pc));
                    pc += 4;
                    continue;
                }
                // ne pas couper un pointeur relogé
                for (int k = pc + 1; k < stop; k++) {
                    if (relocs.contains(k)) {
                        stop = k;
                        break;
                    }
                }
                StringBuilder bytes = new StringBuilder(), ascii = new StringBuilder();
                for (int k = pc; k < stop; k++) {
                    int b = img[k - base] & 0xff;
                    if (bytes.length() > 0) {
                        bytes.append(',');
                    }
                    bytes.append(String.format("$%02x", b));
                    ascii.append(b >= 32 && b < 127 ? (char) b : '.');
                }
                sb.append(String.format("\tdc.b\t%-64s ; %06x %s%n", bytes, pc, ascii));
                pc = stop;
            }
        }
        return sb.toString();
    }

    /** Désassemble un exécutable à hunks vers un fichier .s. */
    public static int write(byte[] exe, String title, Path out) throws IOException {
        HunkFile hf = HunkFile.parse(exe);
        DisasmListing l = new DisasmListing(hf, 0x10000);
        l.analyze();
        Files.writeString(out, l.render(title));
        return l.code.size();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage : DisasmListing <exécutable hunk> <sortie.s>");
            System.exit(1);
        }
        byte[] d = Files.readAllBytes(Path.of(args[0]));
        int n = write(d, Path.of(args[0]).getFileName().toString(), Path.of(args[1]));
        System.out.println(n + " instructions");
    }
}
