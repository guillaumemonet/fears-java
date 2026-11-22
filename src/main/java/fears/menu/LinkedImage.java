package fears.menu;

import fears.extract.HunkFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Un exécutable Amiga chargé tel que l'Amiga le voyait : hunks posés à partir de 0x10000
 * et relogés. Sert au menu ({@code d1_000400}) et à l'intro de démarrage ({@code d1_0d140c}).
 *
 * <p>Le menu d'origine range ses pages de texte, ses tables (largeurs de la police, sinus de
 * l'ondulation) et même l'état de ses options <em>dans ses propres données</em>, qu'il
 * modifie en place : choisir « WALLS » réécrit les quatre octets «   ON » de la chaîne. Le
 * portage garde ce fonctionnement : il travaille sur une copie modifiable de l'image et la
 * relit aux mêmes adresses que le code 68k. Les adresses citées sont celles du listing
 * {@code extract/out/exe/d1_000400.s}.
 */
public final class LinkedImage {

    /** Adresse de chargement de l'image liée. */
    public static final int BASE = 0x10000;

    /** Fin de chaque page de texte. */
    static final int END_OF_PAGE = 0xFF;

    private final byte[] image;

    LinkedImage(byte[] image) {
        this.image = image;
    }

    /** Lit l'exécutable brut tel que l'extracteur l'a sorti du disque ({@code raw/d1_000400.bin}). */
    public static LinkedImage load(Path raw) throws IOException {
        HunkFile hunks = HunkFile.parse(Files.readAllBytes(raw));
        hunks.layout(BASE);
        return new LinkedImage(hunks.linkedImage());
    }

    public int u8(int address) {
        return image[address - BASE] & 0xFF;
    }

    public int s16(int address) {
        return (short) ((u8(address) << 8) | u8(address + 1));
    }

    public int u16(int address) {
        return s16(address) & 0xFFFF;
    }

    public int u32(int address) {
        return (u16(address) << 16) | u16(address + 2);
    }

    void put8(int address, int value) {
        image[address - BASE] = (byte) value;
    }

    void put16(int address, int value) {
        put8(address, value >> 8);
        put8(address + 1, value);
    }

    void put32(int address, int value) {
        put16(address, value >>> 16);
        put16(address + 2, value);
    }

    /** Recopie {@code length} octets d'une adresse à une autre, comme un {@code move.l}. */
    void copy(int from, int to, int length) {
        System.arraycopy(image, from - BASE, image, to - BASE, length);
    }

    /** Chaîne terminée par un zéro. */
    public String string(int address) {
        int end = address;
        while (u8(end) != 0) {
            end++;
        }
        return new String(image, address - BASE, end - address, StandardCharsets.ISO_8859_1);
    }
}
