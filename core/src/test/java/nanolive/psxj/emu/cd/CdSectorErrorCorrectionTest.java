package nanolive.psxj.emu.cd;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CdSectorErrorCorrectionTest {

    @Test
    void mode2Form1MatchesKnownEdcPAndQVector() {
        byte[] sector = rawSector(2, 1);
        byte[] subheader = {1, 2, 8, 0, 1, 2, 8, 0};
        System.arraycopy(subheader, 0, sector, 16, subheader.length);
        for (int i = 0; i < 2048; i++) {
            sector[24 + i] = (byte) (i * 29 + 7);
        }

        CdImage.generateEdcAndEcc(sector);

        assertEquals("EC72CF83", hex(sector, 0x818, 4));
        assertEquals("BD842A746CCCA72E3383D765D355C9B8AFB99A450F300D389F66EC12E12975C2",
            sha256(sector, 0x81C, 172));
        assertEquals("A2F31351BEB3300E79629A3F87E045DCF2F93DBB1FB0F1E81E72016914618B23",
            sha256(sector, 0x8C8, 104));
        assertEquals("09540AE344DB8AF4DB07104B22723A9C47DEF4C2D47CF05236053A72A356967C",
            sha256(sector, 0, sector.length));
    }

    @Test
    void mode2Form2MatchesKnownEdcVectorWithoutPOrQFields() {
        byte[] sector = rawSector(2, 2);
        byte[] subheader = {1, 2, 0x28, 0, 1, 2, 0x28, 0};
        System.arraycopy(subheader, 0, sector, 16, subheader.length);
        for (int i = 0; i < 2324; i++) {
            sector[24 + i] = (byte) (i * 17 + 3);
        }

        CdImage.generateEdcAndEcc(sector);

        assertEquals("6239F9A0", hex(sector, 0x92C, 4));
        assertEquals("B667B8C6EC6076496A2A04BC7321AC2584294050DB72DA1A6EC8BBB231D9D75B",
            sha256(sector, 0, sector.length));
    }

    private static byte[] rawSector(int mode, int frame) {
        byte[] sector = new byte[CdImage.SECTOR_SIZE];
        Arrays.fill(sector, 1, 11, (byte) 0xFF);
        sector[13] = 2;
        sector[14] = (byte) frame;
        sector[15] = (byte) mode;
        return sector;
    }

    private static String hex(byte[] bytes, int offset, int length) {
        return HexFormat.of().withUpperCase().formatHex(bytes, offset, offset + length);
    }

    private static String sha256(byte[] bytes, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, offset, length);
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }
}
