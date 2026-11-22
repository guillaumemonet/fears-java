package fears.disasm;

import fears.extract.HunkFile;
import java.nio.file.*;

/** Écrit « adresse taille texte » pour chaque instruction atteinte par le suivi du flot. */
public final class FlowDump {
    public static void main(String[] a) throws Exception {
        DisasmListing l = new DisasmListing(HunkFile.parse(Files.readAllBytes(Path.of(a[0]))), 0x10000);
        l.analyze();
        StringBuilder sb = new StringBuilder();
        l.codeMap().forEach((k, i) -> sb.append(String.format("%06x %d %s%n", k, i.size, i)));
        Files.writeString(Path.of(a[1]), sb);
    }
}
