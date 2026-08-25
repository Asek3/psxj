package nanolive.psxj.emu;

import nanolive.psxj.emu.devices.CdRomController;
import nanolive.psxj.emu.devices.InterruptController;
import nanolive.psxj.emu.core.Bus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdRomControllerTest {

    @Test
    void cpuWideStatusReadsRepeatHstsWithoutAdvancingToAdjacentPorts() {
        CdRomController cd = new CdRomController(new InterruptController());
        Bus bus = new Bus();
        bus.setCdRomController(cd);

        assertEquals(0x1818, bus.read16(0x1F80_1800));
        assertEquals(0x1818_1818, bus.read32(0x1F80_1800));
    }

    private static final int COMMAND_CYCLES = 0x0004_0000;
    private static final int RESET_CYCLES = 0x0040_0000;
    private static final int SECTOR_CYCLES = 451_584;
    private static final int SEEK_CYCLES = 0x0700_0000;

    @Test
    void shouldRespondToGetStatusWithoutDisc() {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.write8(0x1F80_1801, 0x01);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x10, cd.read8(0x1F80_1801));
    }

    @Test
    void openingTheShellRaisesUnsolicitedInt5AndNopClearsTheClosedShellLatch(
        @TempDir Path tempDir
    ) throws IOException {
        InterruptController interrupts = new InterruptController();
        CdRomController cd = new CdRomController(interrupts);
        Path disc = writeSingleTrackDataDisc(tempDir);
        cd.mount(disc);

        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1802, 0x1F);
        cd.eject();

        assertEquals(1 << 2, interrupts.status() & (1 << 2));
        assertEquals(5, cd.read8(0x1F80_1803) & 0x1F);
        assertEquals(0x11, cd.read8(0x1F80_1801));
        assertEquals(0x08, cd.read8(0x1F80_1801));

        interrupts.writeStatus(~(1 << 2));
        ack(cd, 5);
        cd.mount(disc);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x01);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x10, cd.read8(0x1F80_1801));
        ack(cd, 3);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x01);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x00, cd.read8(0x1F80_1801));
    }

    @Test
    void driveSwitchAndRegionTestsDescribeTheScph5501Hardware(@TempDir Path tempDir)
        throws IOException {
        CdRomController cd = new CdRomController(new InterruptController());

        cd.write8(0x1F80_1802, 0x21);
        cd.write8(0x1F80_1801, 0x19);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x03, cd.read8(0x1F80_1801), "POS0 and DOOR are both active");
        ack(cd, 3);

        cd.mount(writeSingleTrackDataDisc(tempDir));
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1802, 0x21);
        cd.write8(0x1F80_1801, 0x19);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x01, cd.read8(0x1F80_1801), "closing the lid clears DOOR");
        ack(cd, 3);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1802, 0x22);
        cd.write8(0x1F80_1801, 0x19);
        cd.tick(COMMAND_CYCLES);
        for (int expected : "for U/C".chars().toArray()) {
            assertEquals(expected, cd.read8(0x1F80_1801));
        }
    }

    @Test
    void getIdAcceptsSceaButRejectsAnImportedLicensedDisc(@TempDir Path tempDir)
        throws IOException {
        CdRomController domestic = new CdRomController(new InterruptController());
        domestic.mount(writeLicensedDataDisc(tempDir.resolve("domestic"), "America"));
        int[] domesticId = getIdSecondResponse(domestic);
        assertEquals(0x02, domesticId[0], Arrays.toString(domesticId));
        assertEquals(0x00, domesticId[1]);
        assertEquals('A', domesticId[7]);

        CdRomController imported = new CdRomController(new InterruptController());
        imported.mount(writeLicensedDataDisc(tempDir.resolve("imported"), "Europe"));
        int[] importedId = getIdSecondResponse(imported);
        assertEquals(0x0A, importedId[0]);
        assertEquals(0x80, importedId[1]);
        assertEquals(0x20, importedId[2]);
        assertEquals(0x00, importedId[7]);
    }

    @Test
    void c2SecretUnlockControlsReadProtectionButNotGetId(@TempDir Path tempDir)
        throws IOException {
        CdRomController locked = new CdRomController(new InterruptController());
        locked.mount(writeSingleTrackDataDisc(tempDir.resolve("locked")));

        issueReadAtCurrentLocation(locked);
        locked.tick(COMMAND_CYCLES);
        locked.write8(0x1F80_1800, 0x01);
        assertEquals(5, locked.read8(0x1F80_1803) & 0x07);
        assertEquals(0x03, locked.read8(0x1F80_1801));
        assertEquals(0x40, locked.read8(0x1F80_1801));
        ack(locked, 5);

        unlockDrive(locked);
        CdRomController restored = new CdRomController(new InterruptController());
        restored.loadState(locked.copyState());
        issueReadAtCurrentLocation(restored);
        restored.tick(COMMAND_CYCLES);
        restored.write8(0x1F80_1800, 0x01);
        assertEquals(3, restored.read8(0x1F80_1803) & 0x07);
        assertEquals(0x02, restored.read8(0x1F80_1801));

        CdRomController identity = new CdRomController(new InterruptController());
        identity.mount(writeSingleTrackDataDisc(tempDir.resolve("identity")));
        unlockDrive(identity);
        int[] id = getIdSecondResponse(identity);
        assertEquals(0x0A, id[0]);
        assertEquals(0x80, id[1]);
    }

    @Test
    void scph5501ReadProtectionAcceptsSceaAndCddaModeBypassesImportLock(
        @TempDir Path tempDir
    ) throws IOException {
        CdRomController domestic = new CdRomController(new InterruptController());
        domestic.mount(writeLicensedDataDisc(tempDir.resolve("domestic-read"), "America"));
        issueReadAtCurrentLocation(domestic);
        domestic.tick(COMMAND_CYCLES);
        domestic.write8(0x1F80_1800, 0x01);
        assertEquals(3, domestic.read8(0x1F80_1803) & 0x07);

        CdRomController imported = new CdRomController(new InterruptController());
        imported.mount(writeLicensedDataDisc(tempDir.resolve("import-read"), "Europe"));
        issueReadAtCurrentLocation(imported);
        imported.tick(COMMAND_CYCLES);
        imported.write8(0x1F80_1800, 0x01);
        assertEquals(5, imported.read8(0x1F80_1803) & 0x07);
        assertEquals(0x03, imported.read8(0x1F80_1801));
        assertEquals(0x40, imported.read8(0x1F80_1801));
        ack(imported, 5);

        imported.write8(0x1F80_1800, 0x00);
        imported.write8(0x1F80_1802, 0x01);
        imported.write8(0x1F80_1801, 0x0E);
        imported.tick(COMMAND_CYCLES);
        ack(imported, 3);
        issueReadAtCurrentLocation(imported);
        imported.tick(COMMAND_CYCLES);
        imported.write8(0x1F80_1800, 0x01);
        assertEquals(3, imported.read8(0x1F80_1803) & 0x07);
    }

    @Test
    void c2UnlockSurvivesLidCycleAndResetOrBadSequenceRelocksIt(@TempDir Path tempDir)
        throws IOException {
        Path disc = writeSingleTrackDataDisc(tempDir);
        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(disc);
        unlockDrive(cd);

        cd.eject();
        ack(cd, 5);
        cd.mount(disc);
        issueReadAtCurrentLocation(cd);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        assertEquals(3, cd.read8(0x1F80_1803) & 0x07,
            "opening and closing the lid must preserve the unlock flag");
        cd.read8(0x1F80_1801);
        ack(cd, 3);

        cd.eject();
        ack(cd, 5);
        cd.mount(disc);
        issueSecretPart(cd, 0x50, "");
        issueSecretPart(cd, 0x52, "Sony");
        issueReadAtCurrentLocation(cd);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        assertEquals(5, cd.read8(0x1F80_1803) & 0x07,
            "a command sent out of sequence must relock the drive");
        ack(cd, 5);

        unlockDrive(cd);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1802, 0xA5);
        cd.write8(0x1F80_1801, 0x1C);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        assertEquals(3, cd.read8(0x1F80_1803) & 0x07,
            "C2 Reset accepts parameters");
        ack(cd, 3);
        cd.tick(RESET_CYCLES);
        issueReadAtCurrentLocation(cd);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        assertEquals(5, cd.read8(0x1F80_1803) & 0x07,
            "Reset must clear the unlock flag");
        ack(cd, 5);

        unlockDrive(cd);
        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, 0x80);
        issueReadAtCurrentLocation(cd);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        assertEquals(5, cd.read8(0x1F80_1803) & 0x07,
            "CHPRST must clear the unlock flag");
    }

    @Test
    void failedMountPreservesTheCurrentlyMountedDisc(@TempDir Path tempDir) throws IOException {
        CdRomController cd = new CdRomController(new InterruptController());
        Path disc = writeSingleTrackDataDisc(tempDir);
        cd.mount(disc);

        cd.mount(tempDir.resolve("missing.cue"));

        assertEquals(disc, cd.mountedImage());
    }

    @Test
    void shouldNotClearResultFifoWhenAcknowledgingInterrupt() {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.write8(0x1F80_1801, 0x01);
        cd.tick(COMMAND_CYCLES);

        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, 0x03);

        assertEquals(0x10, cd.read8(0x1F80_1801));
    }

    @Test
    void acknowledgingFirstResponseRelatchesAlreadyDueSecondResponseIrq(@TempDir Path tempDir)
        throws IOException {
        InterruptController interrupts = new InterruptController();
        CdRomController cd = new CdRomController(interrupts);
        cd.mount(writeSingleTrackDataDisc(tempDir));

        cd.write8(0x1F80_1801, 0x07);
        cd.tick(SEEK_CYCLES);
        assertEquals(1 << 2, interrupts.status() & (1 << 2));

        interrupts.writeStatus(~(1 << 2));
        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, 0x03);

        assertEquals(1 << 2, interrupts.status() & (1 << 2));
        assertEquals(0x02, cd.read8(0x1F80_1803) & 0x1F);
    }

    @Test
    void getIdWithTheShellOpenReturnsImmediateDoorErrorOnly() {
        CdRomController cd = new CdRomController(new InterruptController());

        cd.write8(0x1F80_1801, 0x1A);
        cd.tick(COMMAND_CYCLES);

        cd.write8(0x1F80_1800, 0x01);
        assertEquals(5, cd.read8(0x1F80_1803) & 0x1F);
        assertEquals(0x11, cd.read8(0x1F80_1801));
        assertEquals(0x80, cd.read8(0x1F80_1801));
        ack(cd, 5);
        cd.tick(SEEK_CYCLES);
        assertEquals(0, cd.read8(0x1F80_1803) & 0x1F);
    }

    @Test
    void latestCommandOverwritesBlockedCommandMailbox() {
        CdRomController cd = new CdRomController(new InterruptController());

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x01);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x01);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x19);

        cd.tick(COMMAND_CYCLES);
        assertEquals(0x10, cd.read8(0x1F80_1801));
        ack(cd, 3);

        cd.tick(COMMAND_CYCLES);
        assertEquals(0x11, cd.read8(0x1F80_1801));
        assertEquals(0x20, cd.read8(0x1F80_1801));
    }

    @Test
    void shouldRequireBufferReadRequestBeforeExposingSectorData(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);
        unlockDrive(cd);

        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1802, 0x02);
        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1801, 0x02);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, 0x03);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x06);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, 0x03);
        cd.tick(SECTOR_CYCLES);

        cd.write8(0x1F80_1800, 0x00);
        assertEquals(0x00, cd.read8(0x1F80_1800) & 0x40);
        assertEquals(0x00, cd.read8(0x1F80_1802));

        cd.write8(0x1F80_1803, 0x80);
        assertEquals(0x40, cd.read8(0x1F80_1800) & 0x40);
        assertEquals(0x01, cd.read8(0x1F80_1802));
    }

    @Test
    void halfwordReadConsumesTwoBytesFromCdDataPort(@TempDir Path tempDir) throws IOException {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(writeSingleTrackDataDisc(tempDir));
        startReadAtLba0(cd);
        cd.tick(SECTOR_CYCLES);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1803, 0x80);

        Bus bus = new Bus();
        bus.setCdRomController(cd);

        assertEquals(0x0201, bus.read16(0x1F80_1802));
    }

    @Test
    void snapshotRestoresMountedImageAndPendingSectorData(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);
        unlockDrive(cd);

        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1802, 0x02);
        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1801, 0x02);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, 0x03);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x06);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, 0x03);
        cd.tick(SECTOR_CYCLES);

        CdRomController.State state = cd.copyState();
        CdRomController restored = new CdRomController(new InterruptController());
        restored.loadState(state);

        restored.write8(0x1F80_1800, 0x00);
        assertEquals(0x00, restored.read8(0x1F80_1800) & 0x40);
        assertEquals(0x00, restored.read8(0x1F80_1802));

        restored.write8(0x1F80_1803, 0x80);

        assertEquals(0x40, restored.read8(0x1F80_1800) & 0x40);
        assertEquals(0x01, restored.read8(0x1F80_1802));
    }

    @Test
    void unacceptedSectorIsReplacedByTheNextPendingInterrupt(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir, 4);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);
        unlockDrive(cd);

        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1802, 0x02);
        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1801, 0x02);
        cd.tick(COMMAND_CYCLES);
        ack(cd, 3);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x06);
        cd.tick(COMMAND_CYCLES);
        ack(cd, 3);

        cd.tick(SECTOR_CYCLES);
        ack(cd, 1);
        cd.tick(SECTOR_CYCLES);
        ack(cd, 1);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1803, 0x80);

        assertEquals(0x04, cd.read8(0x1F80_1802));
    }

    @Test
    void nextRawSectorReplacesUnreadTailWhenBfrdIsRearmed(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir, 4);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);
        startReadAtLba0(cd);

        cd.tick(SECTOR_CYCLES);
        cd.read8(0x1F80_1801);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1803, 0x00);
        cd.write8(0x1F80_1803, 0x80);
        ack(cd, 1);

        for (int i = 0; i < 0x800; i++) {
            cd.read8(0x1F80_1802);
        }

        cd.tick(SECTOR_CYCLES);
        cd.read8(0x1F80_1801);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1803, 0x00);
        cd.write8(0x1F80_1803, 0x80);

        assertEquals(0x04, cd.read8(0x1F80_1802));
        assertEquals(0x05, cd.read8(0x1F80_1802));
        assertEquals(0x06, cd.read8(0x1F80_1802));
        assertEquals(0x02, cd.read8(0x1F80_1802));
    }

    @Test
    void readInterruptUsesPsxSectorClock(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);
        startReadAtLba0(cd);

        cd.tick(SECTOR_CYCLES - COMMAND_CYCLES - 1);
        assertEquals(0x00, cd.read8(0x1F80_1800) & 0x20);

        cd.tick(1);
        assertEquals(0x20, cd.read8(0x1F80_1800) & 0x20);
        assertEquals(0x22, cd.read8(0x1F80_1801));
    }

    @Test
    void resultPortPadsToSixteenBytesThenRepeatsWindow() {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.write8(0x1F80_1801, 0x01);
        cd.tick(COMMAND_CYCLES);

        assertEquals(0x10, cd.read8(0x1F80_1801));
        for (int i = 0; i < 15; i++) {
            assertEquals(0x00, cd.read8(0x1F80_1801));
        }
        assertEquals(0x10, cd.read8(0x1F80_1801));
    }

    @Test
    void dataPortReturnsZeroAfterBlockEnd(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);
        startReadAtLba0(cd);
        cd.tick(SECTOR_CYCLES);
        ack(cd, 1);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1803, 0x80);

        assertEquals(0x01, cd.read8(0x1F80_1802));
        for (int i = 1; i < 0x924; i++) {
            cd.read8(0x1F80_1802);
        }
        assertEquals(0x00, cd.read8(0x1F80_1802));
    }

    @Test
    void motorOnWithoutDiscFailsWithCannotRespond() {
        CdRomController cd = new CdRomController(new InterruptController());

        cd.write8(0x1F80_1801, 0x07);
        cd.tick(COMMAND_CYCLES);

        assertEquals(0x11, cd.read8(0x1F80_1801));
        assertEquals(0x80, cd.read8(0x1F80_1801));
    }

    @Test
    void getQRequiresSpinningDiscAndReturnsRawLeadInEntry(@TempDir Path tempDir)
        throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir, 10);
        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);

        cd.write8(0x1F80_1802, 0x01);
        cd.write8(0x1F80_1802, 0x01);
        cd.write8(0x1F80_1801, 0x1D);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x01, cd.read8(0x1F80_1801));
        assertEquals(0x80, cd.read8(0x1F80_1801));
        ack(cd, 5);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x07);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);
        cd.tick(SEEK_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 2);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1802, 0x01);
        cd.write8(0x1F80_1802, 0x01);
        cd.write8(0x1F80_1801, 0x1D);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x02, cd.read8(0x1F80_1801));
        ack(cd, 3);

        cd.tick(SEEK_CYCLES);
        assertEquals(0x02, cd.read8(0x1F80_1803) & 0x1F);
        int[] expected = {0x41, 0x00, 0x01, 0, 0, 0, 0, 0x00, 0x02, 0x00, 0};
        for (int value : expected) {
            assertEquals(value, cd.read8(0x1F80_1801));
        }
    }

    @Test
    void cddaPlayQueuesStereoPcm(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackAudioDisc(tempDir);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);

        cd.write8(0x1F80_1801, 0x03);
        cd.tick(COMMAND_CYCLES);
        ack(cd, 3);
        cd.tick(SECTOR_CYCLES);

        short[] pcm = cd.drainXaPcm();
        assertEquals(588 * 2, pcm.length);
        assertEquals((short) 0x1234, pcm[0]);
        assertEquals((short) 0xEDCC, pcm[1]);
    }

    @Test
    void shouldExposeTrackNumbersAndCueTrackOffsets(@TempDir Path tempDir) throws IOException {
        Path track1 = tempDir.resolve("track01.bin");
        Path track2 = tempDir.resolve("track02.bin");
        Files.write(track1, new byte[10 * 2352]);
        Files.write(track2, new byte[10 * 2352]);
        Files.writeString(tempDir.resolve("disc.cue"), """
            FILE "track01.bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 01 00:00:00
            FILE "track02.bin" BINARY
              TRACK 02 AUDIO
                PREGAP 00:02:00
                INDEX 01 00:00:00
            """);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(tempDir.resolve("disc.cue"));

        cd.write8(0x1F80_1801, 0x13);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x00, cd.read8(0x1F80_1801));
        assertEquals(0x01, cd.read8(0x1F80_1801));
        assertEquals(0x02, cd.read8(0x1F80_1801));

        cd = new CdRomController(new InterruptController());
        cd.mount(tempDir.resolve("disc.cue"));
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1802, 0x02);
        cd.write8(0x1F80_1801, 0x14);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x00, cd.read8(0x1F80_1801));
        assertEquals(0x00, cd.read8(0x1F80_1801));
        assertEquals(0x04, cd.read8(0x1F80_1801));
    }

    @Test
    void setlocRejectsInvalidBcdMsf(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);

        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1802, 0x60);
        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1801, 0x02);
        cd.tick(COMMAND_CYCLES);

        assertEquals(0x01, cd.read8(0x1F80_1801));
        assertEquals(0x10, cd.read8(0x1F80_1801));
    }

    @Test
    void gettdRejectsNonBcdTrackParameter(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);

        cd.write8(0x1F80_1802, 0x1A);
        cd.write8(0x1F80_1801, 0x14);
        cd.tick(COMMAND_CYCLES);

        assertEquals(0x01, cd.read8(0x1F80_1801));
        assertEquals(0x10, cd.read8(0x1F80_1801));
    }

    @Test
    void cddaPlayAdvancesGetlocPPosition(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir, 120);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);

        cd.write8(0x1F80_1801, 0x03);
        cd.tick(COMMAND_CYCLES);
        ack(cd, 3);
        cd.tick(SECTOR_CYCLES);
        cd.tick(SECTOR_CYCLES);

        int[] loc = getlocP(cd);

        assertEquals(0x01, loc[0]);
        assertEquals(0x01, loc[1]);
        assertEquals(0x00, loc[2]);
        assertEquals(0x00, loc[3]);
        assertEquals(0x02, loc[4]);
    }

    @Test
    void forwardRequiresActivePlayAndAdvancesByScanStep(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir, 120);

        CdRomController idle = new CdRomController(new InterruptController());
        idle.mount(cue);
        idle.write8(0x1F80_1801, 0x04);
        idle.tick(COMMAND_CYCLES);
        assertEquals(0x01, idle.read8(0x1F80_1801));
        assertEquals(0x80, idle.read8(0x1F80_1801));

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);
        cd.write8(0x1F80_1801, 0x03);
        cd.tick(COMMAND_CYCLES);
        ack(cd, 3);
        cd.tick(SECTOR_CYCLES);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x04);
        cd.tick(COMMAND_CYCLES);
        ack(cd, 3);
        cd.tick(SECTOR_CYCLES);

        int[] loc = getlocP(cd);

        assertTrue(fromBcd(loc[4]) >= 13);
    }

    @Test
    void setSessionRejectsMissingSessionOnSingleSessionDisc(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir, 120);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);

        cd.write8(0x1F80_1802, 0x02);
        cd.write8(0x1F80_1801, 0x12);
        cd.tick(COMMAND_CYCLES);

        assertEquals(0x42, cd.read8(0x1F80_1801));
        ack(cd, 3);
        cd.tick(SEEK_CYCLES);

        assertEquals(0x06, cd.read8(0x1F80_1801));
        assertEquals(0x40, cd.read8(0x1F80_1801));
        ack(cd, 5);

        assertEquals(0x06, cd.read8(0x1F80_1801));
        assertEquals(0x40, cd.read8(0x1F80_1801));
    }

    @Test
    void wrongParameterCountReturnsReasonTwentyAndClearsFifo() {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1802, 0x02);
        cd.write8(0x1F80_1801, 0x02);
        cd.tick(COMMAND_CYCLES);

        assertEquals(0x11, cd.read8(0x1F80_1801));
        assertEquals(0x20, cd.read8(0x1F80_1801));
        ack(cd, 5);

        cd.write8(0x1F80_1800, 0x00);
        assertEquals(0x08, cd.read8(0x1F80_1800) & 0x08);
    }

    @Test
    void hclrClrprmClearsParameterFifoWithoutIssuingACommand() {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.write8(0x1F80_1802, 0x5A);
        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, 0x40);

        cd.write8(0x1F80_1800, 0x00);
        assertEquals(0x08, cd.read8(0x1F80_1800) & 0x08);
        cd.write8(0x1F80_1801, 0x01);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        assertEquals(3, cd.read8(0x1F80_1803) & 0x07);
    }

    @Test
    void dataOnlyModeAlwaysExposesExactlyEightHundredBytesForFormTwo(
        @TempDir Path tempDir) throws IOException {
        byte[] image = new byte[2 * 2352];
        image[15] = 0x02;
        image[18] = 0x20; // Mode2/Form2
        Arrays.fill(image, 24, 24 + 2324, (byte) 0x6B);
        image[2352 + 15] = 0x02;
        Path bin = tempDir.resolve("form2.bin");
        Files.write(bin, image);

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(bin);
        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1801, 0x0E);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);
        startReadAtLba0(cd);
        cd.tick(SECTOR_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 1);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1803, 0x80);

        for (int i = 0; i < 0x7FF; i++) {
            assertEquals(0x6B, cd.read8(0x1F80_1802));
        }
        assertEquals(0x40, cd.read8(0x1F80_1800) & 0x40);
        assertEquals(0x6B, cd.read8(0x1F80_1802));
        assertEquals(0x00, cd.read8(0x1F80_1800) & 0x40);
    }

    @Test
    void successfulSeekLatchesHeaderForGetlocL(@TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir, 120);
        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);

        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1802, 0x02);
        cd.write8(0x1F80_1802, 0x01);
        cd.write8(0x1F80_1801, 0x02);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);
        cd.write8(0x1F80_1800, 0);
        cd.write8(0x1F80_1801, 0x15);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);
        cd.tick(SEEK_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 2);

        cd.write8(0x1F80_1800, 0);
        cd.write8(0x1F80_1801, 0x10);
        cd.tick(COMMAND_CYCLES);

        assertEquals(0x04, cd.read8(0x1F80_1801));
        assertEquals(0x05, cd.read8(0x1F80_1801));
        assertEquals(0x06, cd.read8(0x1F80_1801));
        assertEquals(0x02, cd.read8(0x1F80_1801));
    }

    @Test
    void failedSeekMakesBothGetlocCommandsFailUntilReinitialized(
        @TempDir Path tempDir) throws IOException {
        Path cue = writeSingleTrackDataDisc(tempDir, 2);
        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(cue);

        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1802, 0x10);
        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1801, 0x02);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);
        cd.write8(0x1F80_1800, 0);
        cd.write8(0x1F80_1801, 0x15);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);
        cd.tick(SEEK_CYCLES);
        assertEquals(0x04, cd.read8(0x1F80_1801));
        assertEquals(0x04, cd.read8(0x1F80_1801));
        ack(cd, 5);

        cd.write8(0x1F80_1800, 0);
        cd.write8(0x1F80_1801, 0x10);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x04, cd.read8(0x1F80_1801));
        ack(cd, 5);

        cd.write8(0x1F80_1800, 0);
        cd.write8(0x1F80_1801, 0x11);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x04, cd.read8(0x1F80_1801));
    }

    @Test
    void delayedSectorAckExposesOldestThenNewestAndGetlocLTracksNewest(
        @TempDir Path tempDir) throws IOException {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(writeTaggedDataDisc(tempDir, 8));
        startReadAtLba0(cd);

        for (int i = 0; i < 6; i++) {
            cd.tick(SECTOR_CYCLES);
        }

        selectNextDataBlock(cd);
        assertEquals(0x00, cd.read8(0x1F80_1802));
        assertEquals(0x02, cd.read8(0x1F80_1802));
        assertEquals(0x00, cd.read8(0x1F80_1802));
        assertEquals(0x02, cd.read8(0x1F80_1802));

        ack(cd, 1);
        selectNextDataBlock(cd);
        assertEquals(0x00, cd.read8(0x1F80_1802));
        assertEquals(0x02, cd.read8(0x1F80_1802));
        assertEquals(0x01, cd.read8(0x1F80_1802));
        assertEquals(0x02, cd.read8(0x1F80_1802));

        ack(cd, 1);
        selectNextDataBlock(cd);
        assertEquals(0x00, cd.read8(0x1F80_1802));
        assertEquals(0x02, cd.read8(0x1F80_1802));
        assertEquals(0x05, cd.read8(0x1F80_1802));
        assertEquals(0x02, cd.read8(0x1F80_1802));

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x10);
        ack(cd, 1);
        cd.tick(COMMAND_CYCLES);
        assertEquals(0x00, cd.read8(0x1F80_1801));
        assertEquals(0x02, cd.read8(0x1F80_1801));
        assertEquals(0x05, cd.read8(0x1F80_1801));
        assertEquals(0x02, cd.read8(0x1F80_1801));
    }

    @Test
    void xaDecoderRunsAtSectorArrivalWhileOlderDataInterruptIsUnacknowledged(
        @TempDir Path tempDir) throws IOException {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(writeDataThenXaDisc(tempDir));
        startReadAtLba0(cd);
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1802, 0x40);
        cd.write8(0x1F80_1801, 0x0E);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);

        cd.tick(SECTOR_CYCLES); // Data sector raises INT1.
        cd.tick(SECTOR_CYCLES); // XA sector arrives while INT1 stays set.

        assertTrue(cd.drainXaPcm().length > 0);
    }

    @Test
    void saveStatePreservesCoalescedSectorBacklog(@TempDir Path tempDir) throws IOException {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(writeTaggedDataDisc(tempDir, 8));
        startReadAtLba0(cd);
        for (int i = 0; i < 6; i++) {
            cd.tick(SECTOR_CYCLES);
        }

        CdRomController restored = new CdRomController(new InterruptController());
        restored.loadState(cd.copyState());
        selectNextDataBlock(restored);
        assertEquals(0x00, restored.read8(0x1F80_1802));
        assertEquals(0x02, restored.read8(0x1F80_1802));
        assertEquals(0x00, restored.read8(0x1F80_1802));
        assertEquals(0x02, restored.read8(0x1F80_1802));
        ack(restored, 1);
        selectNextDataBlock(restored);

        assertEquals(0x00, restored.read8(0x1F80_1802));
        assertEquals(0x02, restored.read8(0x1F80_1802));
        assertEquals(0x01, restored.read8(0x1F80_1802));
        assertEquals(0x02, restored.read8(0x1F80_1802));
        ack(restored, 1);
        selectNextDataBlock(restored);

        assertEquals(0x00, restored.read8(0x1F80_1802));
        assertEquals(0x02, restored.read8(0x1F80_1802));
        assertEquals(0x05, restored.read8(0x1F80_1802));
        assertEquals(0x02, restored.read8(0x1F80_1802));
    }

    @Test
    void firmwarePhaseProducesDeterministicBoundedFirstResponseJitter() {
        CdRomController heavyPhase = new CdRomController(new InterruptController());
        int heavyLatency = issueNopAndMeasureResponse(heavyPhase);

        CdRomController lightPhase = new CdRomController(new InterruptController());
        lightPhase.tick(16 * 384 * 16);
        CdRomController restored = new CdRomController(new InterruptController());
        restored.loadState(lightPhase.copyState());

        int lightLatency = issueNopAndMeasureResponse(lightPhase);
        int restoredLatency = issueNopAndMeasureResponse(restored);

        assertTrue(heavyLatency >= 0x0004_83B && heavyLatency <= 0x0009_3F2 + 63);
        assertTrue(lightLatency >= 0x0004_83B && lightLatency <= 0x0009_3F2 + 63);
        assertTrue(lightLatency < heavyLatency);
        assertEquals(lightLatency, restoredLatency);
    }

    @Test
    void invalidLibCryptSubchannelRetainsPreviousValidGetlocP(
        @TempDir Path tempDir) throws IOException {
        Path disc = writeTaggedDataDisc(tempDir, 3);
        Files.write(tempDir.resolve("tagged.sbi"), new byte[]{
            'S', 'B', 'I', 0,
            0x00, 0x02, 0x01, 0x03,
            0x00, 0x02, 0x09
        });

        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(disc);
        startReadAtLba0(cd);

        cd.tick(SECTOR_CYCLES - COMMAND_CYCLES);
        ack(cd, 1);
        int[] validLocation = getlocP(cd);
        assertEquals(0x00, validLocation[4]);
        assertEquals(0x00, validLocation[7]);

        cd.tick(SECTOR_CYCLES - COMMAND_CYCLES);
        ack(cd, 1);
        int[] afterBadChecksum = getlocP(cd);

        assertEquals(0x00, afterBadChecksum[4]);
        assertEquals(0x00, afterBadChecksum[7]);
        assertTrue(Arrays.equals(validLocation, afterBadChecksum));
    }

    @Test
    void seekDelayDependsOnDistanceAndSpindleState(@TempDir Path tempDir) throws IOException {
        Path disc = writeTaggedDataDisc(tempDir, 2_000);

        CdRomController coldNear = new CdRomController(new InterruptController());
        coldNear.mount(disc);
        int coldNearLatency = issueSeekAndMeasureSecondResponse(coldNear, 1);

        CdRomController coldFar = new CdRomController(new InterruptController());
        coldFar.mount(disc);
        int coldFarLatency = issueSeekAndMeasureSecondResponse(coldFar, 1_800);

        CdRomController spinningNear = new CdRomController(new InterruptController());
        spinningNear.mount(disc);
        spinningNear.write8(0x1F80_1801, 0x07);
        spinningNear.tick(COMMAND_CYCLES);
        spinningNear.read8(0x1F80_1801);
        ack(spinningNear, 3);
        spinningNear.tick(0x0240_0000);
        spinningNear.read8(0x1F80_1801);
        ack(spinningNear, 2);
        int spinningNearLatency = issueSeekAndMeasureSecondResponse(spinningNear, 1);

        assertTrue(coldFarLatency > coldNearLatency);
        assertTrue(coldNearLatency > spinningNearLatency);
    }

    @Test
    void zeroDistanceSeekStillWaitsForACompleteSectorValidation(@TempDir Path tempDir)
        throws IOException {
        CdRomController cd = new CdRomController(new InterruptController());
        cd.mount(writeTaggedDataDisc(tempDir, 16));

        issueSeekAndMeasureSecondResponse(cd, 1);
        cd.read8(0x1F80_1801);
        ack(cd, 2);

        int latency = issueSeekAndMeasureSecondResponse(cd, 1);

        assertTrue(latency >= SECTOR_CYCLES - COMMAND_CYCLES);
    }

    private static Path writeSingleTrackDataDisc(Path tempDir) throws IOException {
        return writeSingleTrackDataDisc(tempDir, 2);
    }

    private static void startReadAtLba0(CdRomController cd) {
        unlockDrive(cd);
        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1802, 0x02);
        cd.write8(0x1F80_1802, 0x00);
        cd.write8(0x1F80_1801, 0x02);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x06);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);
    }

    private static void unlockDrive(CdRomController cd) {
        issueSecretPart(cd, 0x50, "");
        issueSecretPart(cd, 0x51, "Licensed by");
        issueSecretPart(cd, 0x52, "Sony");
        issueSecretPart(cd, 0x53, "Computer");
        issueSecretPart(cd, 0x54, "Entertainment");
        issueSecretPart(cd, 0x55, "of America");
        issueSecretPart(cd, 0x56, "");
    }

    private static void issueSecretPart(CdRomController cd, int command, String parameter) {
        cd.write8(0x1F80_1800, 0x00);
        parameter.chars().forEach(value -> cd.write8(0x1F80_1802, value));
        cd.write8(0x1F80_1801, command);
        cd.tick(COMMAND_CYCLES);
        cd.write8(0x1F80_1800, 0x01);
        assertEquals(5, cd.read8(0x1F80_1803) & 0x07);
        assertEquals(0x11, cd.read8(0x1F80_1801));
        assertEquals(0x40, cd.read8(0x1F80_1801));
        ack(cd, 5);
    }

    private static void issueReadAtCurrentLocation(CdRomController cd) {
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x06);
    }

    private static Path writeSingleTrackDataDisc(Path tempDir, int sectors) throws IOException {
        Files.createDirectories(tempDir);
        byte[] sector0 = new byte[2352];
        byte[] sector1 = new byte[2352];
        sector0[12] = 0x01;
        sector0[13] = 0x02;
        sector0[14] = 0x03;
        sector0[15] = 0x02;
        sector0[16] = 0x05;
        sector0[17] = 0x06;
        sector0[18] = 0x00;
        sector0[2348] = 0x5A;
        Arrays.fill(sector0, 24, 24 + 2048, (byte) 0x11);
        sector1[12] = 0x04;
        sector1[13] = 0x05;
        sector1[14] = 0x06;
        sector1[15] = 0x02;
        Arrays.fill(sector1, 24, 24 + 2048, (byte) 0x22);

        byte[] image = new byte[Math.max(2, sectors) * 2352];
        System.arraycopy(sector0, 0, image, 0, sector0.length);
        System.arraycopy(sector1, 0, image, sector0.length, sector1.length);

        Path bin = tempDir.resolve("track01.bin");
        Files.write(bin, image);
        Path cue = tempDir.resolve("disc.cue");
        Files.writeString(cue, """
            FILE "track01.bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 01 00:00:00
            """);
        return cue;
    }

    private static Path writeLicensedDataDisc(Path directory, String region) throws IOException {
        Files.createDirectories(directory);
        byte[] image = new byte[6 * 2352];
        for (int sector = 0; sector < 6; sector++) {
            image[sector * 2352 + 15] = 0x02;
        }
        byte[] license = ("          Licensed  by          "
            + "Sony Computer Entertainment " + region)
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(license, 0, image, 4 * 2352 + 24, license.length);
        Path bin = directory.resolve("licensed.bin");
        Files.write(bin, image);
        return bin;
    }

    private static Path writeTaggedDataDisc(Path tempDir, int sectors) throws IOException {
        byte[] image = new byte[sectors * 2352];
        for (int sector = 0; sector < sectors; sector++) {
            int offset = sector * 2352;
            image[offset + 12] = 0x00;
            image[offset + 13] = 0x02;
            image[offset + 14] = (byte) sector;
            image[offset + 15] = 0x02;
            Arrays.fill(image, offset + 24, offset + 24 + 2048, (byte) (0x40 + sector));
        }
        Path bin = tempDir.resolve("tagged.bin");
        Files.write(bin, image);
        return bin;
    }

    private static Path writeDataThenXaDisc(Path tempDir) throws IOException {
        byte[] image = new byte[2 * 2352];
        image[15] = 0x02;
        image[2352 + 15] = 0x02;
        image[2352 + 16] = 0x01;
        image[2352 + 17] = 0x02;
        image[2352 + 18] = 0x44; // Form-2 realtime audio.
        image[2352 + 19] = 0x01; // 37.8 kHz, 4-bit stereo.
        Path bin = tempDir.resolve("data-xa.bin");
        Files.write(bin, image);
        return bin;
    }

    private static Path writeSingleTrackAudioDisc(Path tempDir) throws IOException {
        byte[] sector = new byte[2352];
        for (int i = 0; i + 3 < sector.length; i += 4) {
            sector[i] = 0x34;
            sector[i + 1] = 0x12;
            sector[i + 2] = (byte) 0xCC;
            sector[i + 3] = (byte) 0xED;
        }

        Path bin = tempDir.resolve("track01.bin");
        Files.write(bin, sector);
        Path cue = tempDir.resolve("disc.cue");
        Files.writeString(cue, """
            FILE "track01.bin" BINARY
              TRACK 01 AUDIO
                INDEX 01 00:00:00
            """);
        return cue;
    }

    private static int[] getlocP(CdRomController cd) {
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x11);
        cd.tick(COMMAND_CYCLES);
        int[] result = new int[8];
        for (int i = 0; i < result.length; i++) {
            result[i] = cd.read8(0x1F80_1801);
        }
        ack(cd, 3);
        return result;
    }

    private static void selectNextDataBlock(CdRomController cd) {
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1803, 0x00);
        cd.write8(0x1F80_1803, 0x80);
    }

    private static int issueNopAndMeasureResponse(CdRomController cd) {
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x01);
        int elapsed = 0;
        while ((cd.read8(0x1F80_1800) & 0x20) == 0 && elapsed <= 0x000A_0000) {
            cd.tick(64);
            elapsed += 64;
        }
        return elapsed;
    }

    private static int[] getIdSecondResponse(CdRomController cd) {
        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x1A);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);
        cd.tick(SEEK_CYCLES);
        int[] response = new int[8];
        for (int i = 0; i < response.length; i++) {
            response[i] = cd.read8(0x1F80_1801);
        }
        return response;
    }

    private static int issueSeekAndMeasureSecondResponse(CdRomController cd, int lba) {
        int absolute = lba + 150;
        cd.write8(0x1F80_1802, toBcd(absolute / (75 * 60)));
        cd.write8(0x1F80_1802, toBcd((absolute / 75) % 60));
        cd.write8(0x1F80_1802, toBcd(absolute % 75));
        cd.write8(0x1F80_1801, 0x02);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);

        cd.write8(0x1F80_1800, 0x00);
        cd.write8(0x1F80_1801, 0x15);
        cd.tick(COMMAND_CYCLES);
        cd.read8(0x1F80_1801);
        ack(cd, 3);

        int elapsed = 0;
        while ((cd.read8(0x1F80_1800) & 0x20) == 0 && elapsed < SEEK_CYCLES) {
            cd.tick(65_536);
            elapsed += 65_536;
        }
        return elapsed;
    }

    private static void ack(CdRomController cd, int irq) {
        cd.write8(0x1F80_1800, 0x01);
        cd.write8(0x1F80_1803, irq);
    }

    private static int fromBcd(int value) {
        return ((value >>> 4) * 10) + (value & 0x0F);
    }

    private static int toBcd(int value) {
        return ((value / 10) << 4) | (value % 10);
    }
}
