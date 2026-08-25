package nanolive.psxj.emu.hardware;

/**
 * Identity and timing envelopes for one PlayStation CD controller/transport.
 *
 * <p>The command response envelopes below come from the PSX-SPX measurements
 * made on a PAL PSone, because equivalent PU-18 traces are not yet available.
 * They are therefore evidence-backed defaults for the SCPH-5501 target, not a
 * claim that a PU-18 produces the exact same extrema. Seek bounds are explicit
 * approximations: the real HC05 firmware, servo retries and disc mechanics are
 * not recoverable without revision-matched firmware and hardware traces.</p>
 */
public final class CdDriveProfile {

    public static final CdDriveProfile SCPH_5501_PU_18 = new CdDriveProfile(
        "KSM-440ADM",
        "CXD1815Q",
        "C2",
        0x97,
        0x01,
        0x10,
        "CXD2545Q",
        "CXD2545Q",
        "CXD1815Q",
        "for U/C",
        "SCEA",
        384,
        new TimingRange(0x0004_A73, 0x000C_4E1, 0x0031_15B),
        new TimingRange(0x0004_83B, 0x0005_CF4, 0x0009_3F2),
        new TimingRange(0x000F_820, 0x0013_CCE, 0x0040_000),
        new TimingRange(0x000F_820, 0x0013_CCE, 0x0040_000),
        new TimingRange(0x0004_922, 0x0004_A00, 0x0004_C2B),
        new TimingRange(0x020E_AEF, 0x0211_81C, 0x0216_E3C),
        new TimingRange(0x0104_77A, 0x010B_D93, 0x011B_302),
        new TimingRange(0x0001_D25, 0x0001_DF2, 0x0001_F22),
        new TimingRange(0x0C3B_C41, 0x0D38_ACA, 0x0DA5_54D),
        new TimingRange(0x1844_76B, 0x18A6_076, 0x192B_306),
        new TimingRange(0x0001_CE8, 0x0001_D7B, 0x0001_EEF),
        new TimingRange(0x0040_0000, 0x0100_0000, 0x0240_0000),
        new TimingRange(0x0002_0000, 0x0040_0000, 0x0100_0000),
        new TimingRange(30_000_000, 33_868_800, 38_000_000),
        0x0002_0000,
        0x0400_0000,
        0x0640_0000,
        "Response ranges: PSX-SPX PAL PSone measurements; "
            + "PU-18 seek/spin envelopes: explicit approximation pending hardware traces"
    );

    // Known retail CD-controller revisions.
    public static final CdDriveProfile PU_7_C0_1994_09_19 = identityFrom(
        SCPH_5501_PU_18, "KSM-440AAM", "CXD1199BQ", "C0",
        0x94, 0x09, 0x19, "CXA1782BR", "CXD2516Q", "CXD1199BQ");
    public static final CdDriveProfile PU_7_C0_1994_11_18 = identityFrom(
        SCPH_5501_PU_18, "KSM-440AAM", "CXD1199BQ", "C0",
        0x94, 0x11, 0x18, "CXA1782BR", "CXD2516Q", "CXD1199BQ");
    public static final CdDriveProfile LATE_PU_8_C1_1995_05_16 = identityFrom(
        SCPH_5501_PU_18, "KSM-440ACM", "CXD1815Q", "C1",
        0x95, 0x05, 0x16, "CXA1782BR", "CXD2510Q", "CXD1815Q");
    public static final CdDriveProfile LATE_PU_8_C1_1995_07_24 = identityFrom(
        SCPH_5501_PU_18, "KSM-440ACM", "CXD1815Q", "C1",
        0x95, 0x07, 0x24, "CXA1782BR", "CXD2510Q", "CXD1815Q");
    public static final CdDriveProfile PU_16_VCD_C2 = identityFrom(
        SCPH_5501_PU_18, "KSM-440ACM", "CXD1815Q", "C2",
        0x96, 0x08, 0x15, "CXA1782BR", "CXD2510Q", "CXD1815Q");
    public static final CdDriveProfile PU_18_JAPAN_C2 = identityFrom(
        SCPH_5501_PU_18, "KSM-440ADM", "CXD1815Q", "C2",
        0x96, 0x09, 0x12, "CXD2545Q", "CXD2545Q", "CXD1815Q");
    public static final CdDriveProfile PU_20_C2 = identityFrom(
        SCPH_5501_PU_18, "KSM-440AEM", "CXD1817R", "C2",
        0x97, 0x08, 0x14, "CXD1817R", "CXD1817R", "CXD1817R");
    public static final CdDriveProfile PU_22_C3 = identityFrom(
        SCPH_5501_PU_18, "KSM-440AEM", "CXD2938Q", "C3",
        0x98, 0x06, 0x10, "CXD2938Q", "CXD2938Q", "CXD2938Q");
    public static final CdDriveProfile PU_23_PM_41_C3 = identityFrom(
        SCPH_5501_PU_18, "KSM-440BAM", "CXD2938Q", "C3",
        0x99, 0x02, 0x01, "CXD2938Q", "CXD2938Q", "CXD2938Q");
    public static final CdDriveProfile PM_41_2_C3 = identityFrom(
        SCPH_5501_PU_18, "KSM-440BAM", "CXD2941R", "C3",
        0xA1, 0x03, 0x06, "CXD2941R", "CXD2941R", "CXD2941R");

    private final String mechanismRevision;
    private final String decoderRevision;
    private final String controllerFirmwareRevision;
    private final int firmwareYearBcd;
    private final int firmwareMonthBcd;
    private final int firmwareDayBcd;
    private final String testServoIdentity;
    private final String testDspIdentity;
    private final String testDecoderIdentity;
    private final String testRegionIdentity;
    private final String requiredLicenseRegion;
    private final int firmwarePollPeriodTicks;
    private final TimingRange firstResponseSpinning;
    private final TimingRange firstResponseStopped;
    private final TimingRange initFirstResponse;
    private final TimingRange readTocFirstResponse;
    private final TimingRange getIdSecondResponse;
    private final TimingRange pauseSingleSpeed;
    private final TimingRange pauseDoubleSpeed;
    private final TimingRange pauseIdle;
    private final TimingRange stopSingleSpeed;
    private final TimingRange stopDoubleSpeed;
    private final TimingRange stopIdle;
    private final TimingRange spinUp;
    private final TimingRange initSecondResponse;
    private final TimingRange readTocSecondResponse;
    private final int seekSpinningMinCycles;
    private final int seekSpinningMaxCycles;
    private final int seekColdMaxCycles;
    private final String timingProvenance;

    private CdDriveProfile(
        String mechanismRevision,
        String decoderRevision,
        String controllerFirmwareRevision,
        int firmwareYearBcd,
        int firmwareMonthBcd,
        int firmwareDayBcd,
        String testServoIdentity,
        String testDspIdentity,
        String testDecoderIdentity,
        String testRegionIdentity,
        String requiredLicenseRegion,
        int firmwarePollPeriodTicks,
        TimingRange firstResponseSpinning,
        TimingRange firstResponseStopped,
        TimingRange initFirstResponse,
        TimingRange readTocFirstResponse,
        TimingRange getIdSecondResponse,
        TimingRange pauseSingleSpeed,
        TimingRange pauseDoubleSpeed,
        TimingRange pauseIdle,
        TimingRange stopSingleSpeed,
        TimingRange stopDoubleSpeed,
        TimingRange stopIdle,
        TimingRange spinUp,
        TimingRange initSecondResponse,
        TimingRange readTocSecondResponse,
        int seekSpinningMinCycles,
        int seekSpinningMaxCycles,
        int seekColdMaxCycles,
        String timingProvenance
    ) {
        this.mechanismRevision = mechanismRevision;
        this.decoderRevision = decoderRevision;
        this.controllerFirmwareRevision = controllerFirmwareRevision;
        this.firmwareYearBcd = firmwareYearBcd;
        this.firmwareMonthBcd = firmwareMonthBcd;
        this.firmwareDayBcd = firmwareDayBcd;
        this.testServoIdentity = testServoIdentity;
        this.testDspIdentity = testDspIdentity;
        this.testDecoderIdentity = testDecoderIdentity;
        this.testRegionIdentity = testRegionIdentity;
        this.requiredLicenseRegion = requiredLicenseRegion;
        this.firmwarePollPeriodTicks = firmwarePollPeriodTicks;
        this.firstResponseSpinning = firstResponseSpinning;
        this.firstResponseStopped = firstResponseStopped;
        this.initFirstResponse = initFirstResponse;
        this.readTocFirstResponse = readTocFirstResponse;
        this.getIdSecondResponse = getIdSecondResponse;
        this.pauseSingleSpeed = pauseSingleSpeed;
        this.pauseDoubleSpeed = pauseDoubleSpeed;
        this.pauseIdle = pauseIdle;
        this.stopSingleSpeed = stopSingleSpeed;
        this.stopDoubleSpeed = stopDoubleSpeed;
        this.stopIdle = stopIdle;
        this.spinUp = spinUp;
        this.initSecondResponse = initSecondResponse;
        this.readTocSecondResponse = readTocSecondResponse;
        this.seekSpinningMinCycles = seekSpinningMinCycles;
        this.seekSpinningMaxCycles = seekSpinningMaxCycles;
        this.seekColdMaxCycles = seekColdMaxCycles;
        this.timingProvenance = timingProvenance;
    }

    private static CdDriveProfile identityFrom(
        CdDriveProfile timingTemplate,
        String mechanismRevision,
        String decoderRevision,
        String controllerFirmwareRevision,
        int firmwareYearBcd,
        int firmwareMonthBcd,
        int firmwareDayBcd,
        String testServoIdentity,
        String testDspIdentity,
        String testDecoderIdentity
    ) {
        return new CdDriveProfile(
            mechanismRevision,
            decoderRevision,
            controllerFirmwareRevision,
            firmwareYearBcd,
            firmwareMonthBcd,
            firmwareDayBcd,
            testServoIdentity,
            testDspIdentity,
            testDecoderIdentity,
            timingTemplate.testRegionIdentity,
            timingTemplate.requiredLicenseRegion,
            timingTemplate.firmwarePollPeriodTicks,
            timingTemplate.firstResponseSpinning,
            timingTemplate.firstResponseStopped,
            timingTemplate.initFirstResponse,
            timingTemplate.readTocFirstResponse,
            timingTemplate.getIdSecondResponse,
            timingTemplate.pauseSingleSpeed,
            timingTemplate.pauseDoubleSpeed,
            timingTemplate.pauseIdle,
            timingTemplate.stopSingleSpeed,
            timingTemplate.stopDoubleSpeed,
            timingTemplate.stopIdle,
            timingTemplate.spinUp,
            timingTemplate.initSecondResponse,
            timingTemplate.readTocSecondResponse,
            timingTemplate.seekSpinningMinCycles,
            timingTemplate.seekSpinningMaxCycles,
            timingTemplate.seekColdMaxCycles,
            timingTemplate.timingProvenance
        );
    }

    CdDriveProfile withRegion(String testRegionIdentity, String requiredLicenseRegion) {
        return new CdDriveProfile(
            mechanismRevision,
            decoderRevision,
            controllerFirmwareRevision,
            firmwareYearBcd,
            firmwareMonthBcd,
            firmwareDayBcd,
            testServoIdentity,
            testDspIdentity,
            testDecoderIdentity,
            testRegionIdentity,
            requiredLicenseRegion,
            firmwarePollPeriodTicks,
            firstResponseSpinning,
            firstResponseStopped,
            initFirstResponse,
            readTocFirstResponse,
            getIdSecondResponse,
            pauseSingleSpeed,
            pauseDoubleSpeed,
            pauseIdle,
            stopSingleSpeed,
            stopDoubleSpeed,
            stopIdle,
            spinUp,
            initSecondResponse,
            readTocSecondResponse,
            seekSpinningMinCycles,
            seekSpinningMaxCycles,
            seekColdMaxCycles,
            timingProvenance
        );
    }

    public String mechanismRevision() { return mechanismRevision; }
    public String decoderRevision() { return decoderRevision; }
    public String controllerFirmwareRevision() { return controllerFirmwareRevision; }
    public int controllerFirmwareRevisionByte() {
        return Integer.parseInt(controllerFirmwareRevision, 16) & 0xFF;
    }
    public int firmwareYearBcd() { return firmwareYearBcd; }
    public int firmwareMonthBcd() { return firmwareMonthBcd; }
    public int firmwareDayBcd() { return firmwareDayBcd; }
    public String testServoIdentity() { return testServoIdentity; }
    public String testDspIdentity() { return testDspIdentity; }
    public String testDecoderIdentity() { return testDecoderIdentity; }
    public String testRegionIdentity() { return testRegionIdentity; }
    public String requiredLicenseRegion() { return requiredLicenseRegion; }
    public boolean acceptsLicenseRegion(String discRegion) {
        return "*".equals(requiredLicenseRegion) || requiredLicenseRegion.equals(discRegion);
    }
    public int firmwarePollPeriodTicks() { return firmwarePollPeriodTicks; }
    public TimingRange firstResponse(boolean spindleRunning) {
        return spindleRunning ? firstResponseSpinning : firstResponseStopped;
    }
    public TimingRange initFirstResponse() { return initFirstResponse; }
    public TimingRange readTocFirstResponse() { return readTocFirstResponse; }
    public TimingRange getIdSecondResponse() { return getIdSecondResponse; }
    public TimingRange pauseSingleSpeed() { return pauseSingleSpeed; }
    public TimingRange pauseDoubleSpeed() { return pauseDoubleSpeed; }
    public TimingRange pauseIdle() { return pauseIdle; }
    public TimingRange stopSingleSpeed() { return stopSingleSpeed; }
    public TimingRange stopDoubleSpeed() { return stopDoubleSpeed; }
    public TimingRange stopIdle() { return stopIdle; }
    public TimingRange spinUp() { return spinUp; }
    public TimingRange initSecondResponse() { return initSecondResponse; }
    public TimingRange readTocSecondResponse() { return readTocSecondResponse; }
    public int seekSpinningMinCycles() { return seekSpinningMinCycles; }
    public int seekSpinningMaxCycles() { return seekSpinningMaxCycles; }
    public int seekColdMaxCycles() { return seekColdMaxCycles; }
    public String timingProvenance() { return timingProvenance; }

    public record TimingRange(int minCycles, int typicalCycles, int maxCycles) {
        public TimingRange {
            if (minCycles < 0 || typicalCycles < minCycles || maxCycles < typicalCycles) {
                throw new IllegalArgumentException("Invalid CD timing range");
            }
        }
    }
}
