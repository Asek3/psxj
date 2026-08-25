package nanolive.psxj.emu;

import nanolive.psxj.emu.cd.CdImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CdImageTest {

    @Test
    void cueLayoutTracksPregapAndTrackStarts(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("track01.bin"), new byte[10 * 2352]);
        Files.write(tempDir.resolve("track02.bin"), new byte[10 * 2352]);
        Files.writeString(tempDir.resolve("disc.cue"), """
            FILE "track01.bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 01 00:00:00
            FILE "track02.bin" BINARY
              TRACK 02 AUDIO
                PREGAP 00:02:00
                INDEX 01 00:00:00
            """);

        CdImage image = CdImage.open(tempDir.resolve("disc.cue"));
        CdImage.TrackPosition pregap = image.locateLba(20);
        CdImage.TrackPosition track2 = image.locateLba(160);

        assertEquals(170, image.sectorCount());
        assertEquals(0, image.trackStartLba(1));
        assertEquals(160, image.trackStartLba(2));
        assertNotNull(pregap);
        assertEquals(2, pregap.trackNumber());
        assertEquals(0, pregap.indexNumber());
        assertNotNull(track2);
        assertEquals(2, track2.trackNumber());
        assertEquals(1, track2.indexNumber());
    }

    @Test
    void cueTrackOneFilePregapStillMapsIndexOneToLogicalLbaZero(@TempDir Path tempDir) throws IOException {
        byte[] sectors = new byte[151 * 2352];
        sectors[(150 * 2352) + 15] = 0x02;
        sectors[(150 * 2352) + 24] = 0x55;
        Files.write(tempDir.resolve("disc.bin"), sectors);
        Files.writeString(tempDir.resolve("disc.cue"), """
            FILE "disc.bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 00 00:00:00
                INDEX 01 00:02:00
            """);

        CdImage image = CdImage.open(tempDir.resolve("disc.cue"));

        assertEquals(1, image.sectorCount());
        assertEquals(0, image.trackStartLba(1));
        assertEquals(0x55, image.readSector(0).raw2352()[24] & 0xFF);
    }

    @Test
    void bareBinStartsAtLogicalLbaZero(@TempDir Path tempDir) throws IOException {
        byte[] sector = new byte[2352];
        sector[15] = 0x02;
        sector[24] = 0x33;
        Files.write(tempDir.resolve("disc.bin"), sector);

        CdImage image = CdImage.open(tempDir.resolve("disc.bin"));

        assertEquals(1, image.sectorCount());
        assertEquals(0, image.trackStartLba(1));
        assertEquals(0x33, image.readSector(0).raw2352()[24] & 0xFF);
    }

    @Test
    void synthesizesValidPositionSubchannelQ(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("disc.bin"), new byte[2352]);

        CdImage.SubchannelQ q = CdImage.open(tempDir.resolve("disc.bin")).subchannelQ(0);

        assertNotNull(q);
        assertTrue(q.checksumValid());
        assertArrayEquals(new byte[]{
            0x41, 0x01, 0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x02, 0x00, 0x28, 0x32
        }, q.data());
    }

    @Test
    void synthesizesFrameExactLeadInTocEntriesForGetQ(@TempDir Path tempDir)
        throws IOException {
        byte[] dataTrack = new byte[10 * 2352];
        dataTrack[15] = 0x02;
        Files.write(tempDir.resolve("track01.bin"), dataTrack);
        Files.write(tempDir.resolve("track02.bin"), new byte[20 * 2352]);
        Files.writeString(tempDir.resolve("disc.cue"), """
            FILE "track01.bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 01 00:00:00
            FILE "track02.bin" BINARY
              TRACK 02 AUDIO
                PREGAP 00:00:03
                INDEX 01 00:00:00
            """);

        CdImage image = CdImage.open(tempDir.resolve("disc.cue"));

        assertArrayEquals(new byte[]{
            0x41, 0x00, 0x01, 0, 0, 0, 0, 0x00, 0x02, 0x00
        }, image.tocSubchannelQ(1, 0x01));
        assertArrayEquals(new byte[]{
            0x01, 0x00, 0x02, 0, 0, 0, 0, 0x00, 0x02, 0x13
        }, image.tocSubchannelQ(1, 0x02));
        assertArrayEquals(new byte[]{
            0x41, 0x00, (byte) 0xA0, 0, 0, 0, 0, 0x01, 0x20, 0x00
        }, image.tocSubchannelQ(1, 0xA0));
        assertArrayEquals(new byte[]{
            0x01, 0x00, (byte) 0xA1, 0, 0, 0, 0, 0x02, 0x00, 0x00
        }, image.tocSubchannelQ(1, 0xA1));
        assertArrayEquals(new byte[]{
            0x01, 0x00, (byte) 0xA2, 0, 0, 0, 0, 0x00, 0x02, 0x33
        }, image.tocSubchannelQ(1, 0xA2));
    }

    @Test
    void siblingSbiMarksLibCryptSectorAsBadSubchannelQ(@TempDir Path tempDir) throws IOException {
        Files.write(tempDir.resolve("disc.bin"), new byte[2 * 2352]);
        Files.write(tempDir.resolve("disc.sbi"), new byte[]{
            'S', 'B', 'I', 0,
            0x00, 0x02, 0x00, 0x03,
            0x00, 0x02, 0x01
        });

        CdImage image = CdImage.open(tempDir.resolve("disc.bin"));
        CdImage.SubchannelQ protectedQ = image.subchannelQ(0);
        CdImage.SubchannelQ ordinaryQ = image.subchannelQ(1);

        assertFalse(protectedQ.checksumValid());
        assertArrayEquals(new byte[]{0x00, 0x02, 0x01},
            Arrays.copyOfRange(protectedQ.data(), 7, 10));
        assertTrue(ordinaryQ.checksumValid());
    }

    @Test
    void bareIso2048SynthesizesMode1RawSectors(@TempDir Path tempDir) throws IOException {
        byte[] imageBytes = new byte[2 * 2048];
        imageBytes[0] = 0x12;
        imageBytes[2048] = 0x34;
        Files.write(tempDir.resolve("disc.iso"), imageBytes);

        CdImage image = CdImage.open(tempDir.resolve("disc.iso"));

        assertEquals(2, image.sectorCount());
        assertEquals(0, image.trackStartLba(1));
        assertEquals(2352, image.readSector(0).raw2352().length);
        assertEquals(0xFF, image.readSector(0).raw2352()[1] & 0xFF);
        assertEquals(0x00, image.readSector(0).raw2352()[12] & 0xFF);
        assertEquals(0x02, image.readSector(0).raw2352()[13] & 0xFF);
        assertEquals(0x00, image.readSector(0).raw2352()[14] & 0xFF);
        assertEquals(0x01, image.readSector(0).raw2352()[15] & 0xFF);
        assertEquals(0x12, image.readSector(0).raw2352()[16] & 0xFF);
        assertEquals(0x34, image.readSector(1).raw2352()[16] & 0xFF);
        assertEquals(0x01, image.readSector(1).raw2352()[14] & 0xFF);
        assertEquals(2048, image.readSector(0).userData().length);
    }

    @Test
    void synthesizedMode1WholeSectorMatchesKnownEdcAndParityVector(@TempDir Path tempDir)
        throws IOException {
        byte[] userData = new byte[2048];
        for (int i = 0; i < userData.length; i++) {
            userData[i] = (byte) (i * 73 + 41);
        }
        Path iso = tempDir.resolve("known-vector.iso");
        Files.write(iso, userData);

        byte[] raw = CdImage.open(iso).readSector(0).raw2352();

        assertArrayEquals(userData, Arrays.copyOfRange(raw, 16, 16 + 2048));
        assertEquals("FF302E8E", hex(raw, 0x810, 4));
        assertEquals("478C14C18017850BFA0FB5B45948ABA3A71F83BE7C68FE112E2B50E7EA6573B3",
            sha256(raw, 0x81C, 172));
        assertEquals("9EB6C91822AE275D0BBA27FC172EBE0ED07B08CB67EBF07014A964685589FEF3",
            sha256(raw, 0x8C8, 104));
        assertEquals("872A132FA0A5D18AF7A80F31F69194D0F7B3CEC065B9268C8AE977610B92539B",
            sha256(raw, 0, raw.length));
    }

    @Test
    void cueMode22048SynthesizesXaForm1Sector(@TempDir Path tempDir) throws IOException {
        byte[] userData = new byte[2048];
        userData[123] = 0x56;
        Files.write(tempDir.resolve("track01.bin"), userData);
        Files.writeString(tempDir.resolve("disc.cue"), """
            FILE "track01.bin" BINARY
              TRACK 01 MODE2/2048
                INDEX 01 00:00:00
            """);

        byte[] raw = CdImage.open(tempDir.resolve("disc.cue")).readSector(0).raw2352();

        assertEquals(2, raw[15] & 0xFF);
        assertArrayEquals(
            Arrays.copyOfRange(raw, 16, 20),
            Arrays.copyOfRange(raw, 20, 24)
        );
        assertEquals(0x08, raw[18] & 0xFF);
        assertEquals(0x56, raw[24 + 123] & 0xFF);
        assertNotEquals("00000000", hex(raw, 0x818, 4));
        assertTrue(containsNonZero(raw, 0x81C, raw.length));
    }

    @Test
    void cueMode22324SynthesizesXaForm2Sector(@TempDir Path tempDir) throws IOException {
        byte[] userData = new byte[2324];
        userData[321] = 0x45;
        Files.write(tempDir.resolve("track01.bin"), userData);
        Files.writeString(tempDir.resolve("disc.cue"), """
            FILE "track01.bin" BINARY
              TRACK 01 MODE2/2324
                INDEX 01 00:00:00
            """);

        byte[] raw = CdImage.open(tempDir.resolve("disc.cue")).readSector(0).raw2352();

        assertEquals(2, raw[15] & 0xFF);
        assertEquals(0x28, raw[18] & 0xFF);
        assertEquals(0x45, raw[24 + 321] & 0xFF);
        assertNotEquals("00000000", hex(raw, 0x92C, 4));
    }

    @Test
    void existingRawSectorIsReturnedWithoutNormalizingItsEdcOrEcc(@TempDir Path tempDir)
        throws IOException {
        byte[] raw = new byte[2352];
        raw[0] = 0;
        Arrays.fill(raw, 1, 11, (byte) 0xFF);
        raw[11] = 0;
        raw[12] = 0;
        raw[13] = 2;
        raw[14] = 0;
        raw[15] = 2;
        for (int i = 16; i < raw.length; i++) {
            raw[i] = (byte) (i * 31 + 9);
        }
        Path bin = tempDir.resolve("raw.bin");
        Files.write(bin, raw);

        assertArrayEquals(raw, CdImage.open(bin).readSector(0).raw2352());
    }

    @Test
    void cueMode12048Uses2048ByteFileSectors(@TempDir Path tempDir) throws IOException {
        byte[] imageBytes = new byte[3 * 2048];
        imageBytes[2048 + 123] = 0x56;
        Files.write(tempDir.resolve("track01.iso"), imageBytes);
        Files.writeString(tempDir.resolve("disc.cue"), """
            FILE "track01.iso" BINARY
              TRACK 01 MODE1/2048
                INDEX 01 00:00:00
            """);

        CdImage image = CdImage.open(tempDir.resolve("disc.cue"));

        assertEquals(3, image.sectorCount());
        assertEquals(0x56, image.readSector(1).userData()[123] & 0xFF);
    }

    @Test
    void licenseIsReadFromSystemAreaSectorFourIncludingUsSpacing(@TempDir Path tempDir)
        throws IOException {
        byte[] imageBytes = mode2Image(20);
        byte[] license = (
            "          Licensed  by          "
                + "Sony Computer Entertainment Amer  ica ")
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(license, 0, imageBytes, 4 * 2352 + 24, license.length);
        Path bin = tempDir.resolve("licensed.bin");
        Files.write(bin, imageBytes);

        CdImage image = CdImage.open(bin);

        assertTrue(image.isLicensedPlayStationDisc());
        assertEquals("SCEA", image.regionCode());
        assertEquals(0x20, image.discTypeCode());
    }

    @Test
    void volumeDescriptorTextCannotFakePlayStationLicense(@TempDir Path tempDir)
        throws IOException {
        byte[] imageBytes = mode2Image(20);
        byte[] fake = (
            "          Licensed  by          "
                + "Sony Computer Entertainment Europe")
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(fake, 0, imageBytes, 16 * 2352 + 24, fake.length);
        Path bin = tempDir.resolve("unlicensed.bin");
        Files.write(bin, imageBytes);

        CdImage image = CdImage.open(bin);

        assertFalse(image.isLicensedPlayStationDisc());
    }

    private static byte[] mode2Image(int sectors) {
        byte[] image = new byte[sectors * 2352];
        for (int sector = 0; sector < sectors; sector++) {
            int base = sector * 2352;
            image[base + 15] = 0x02;
        }
        return image;
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

    private static boolean containsNonZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) return true;
        }
        return false;
    }
}
