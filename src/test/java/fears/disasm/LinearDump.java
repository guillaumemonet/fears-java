package fears.disasm;

import java.nio.file.*;

/** Balayage linéaire : « adresse taille texte » par ligne (pour comparaison avec un autre désassembleur). */
public final class LinearDump {
    public static void main(String[] a) throws Exception {
        byte[] img = Files.readAllBytes(Path.of(a[0]));
        int base = Integer.decode(a[1]), start = Integer.decode(a[2]), len = Integer.decode(a[3]);
        Disasm68k d = new Disasm68k(img, base);
        StringBuilder sb = new StringBuilder();
        for (int pc = start; pc < start + len; ) {
            Disasm68k.Insn i = d.decode(pc);
            sb.append(String.format("%06x %d %s%n", pc, i.size, i));
            pc += i.size;
        }
        Files.writeString(Path.of(a[4]), sb);
    }
}
