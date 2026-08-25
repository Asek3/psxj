package nanolive.psxj.emu.hardware;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardwareProfileTest {

    @Test
    void scph5501Pu18ConstantsDescribeFixedNtscUHardware() {
        HardwareProfile profile = HardwareProfile.SCPH_5501_PU_18_NTSC_U;

        assertEquals("SCPH-5501", profile.model());
        assertEquals("PU-18", profile.mainboardRevision());
        assertEquals(HardwareProfile.VideoOscillator.NTSC, profile.videoOscillator());
        assertEquals(33_868_800, profile.cpuClockHz());
        assertEquals(53_693_175, profile.gpuClockHz());
        assertEquals(44_100, profile.cpuClockHz() / profile.spuClocksPerSample());
        assertEquals(4_233_600, profile.cdControllerOscillatorHz());
        assertEquals(2_116_800, profile.cdControllerSystemClockHz());

        CdDriveProfile drive = profile.cdDriveProfile();
        assertEquals("KSM-440ADM", drive.mechanismRevision());
        assertEquals("CXD1815Q", drive.decoderRevision());
        assertEquals("C2", drive.controllerFirmwareRevision());
        assertEquals(0x97, drive.firmwareYearBcd());
        assertEquals(0x01, drive.firmwareMonthBcd());
        assertEquals(0x10, drive.firmwareDayBcd());
        assertEquals("CXD2545Q", drive.testServoIdentity());
        assertEquals("for U/C", drive.testRegionIdentity());
        assertEquals("SCEA", drive.requiredLicenseRegion());
        assertTrue(drive.timingProvenance().contains("PAL PSone"));
        assertTrue(drive.timingProvenance().contains("approximation"));
    }

    @Test
    void gpuClockConversionRatioExactlyMatchesProfileFrequencies() {
        HardwareProfile profile = HardwareProfile.SCPH_5501_PU_18_NTSC_U;

        long ratioFromGpuClock = (long) profile.gpuClockHz()
            * profile.gpuClockRatioDenominator();
        long ratioFromCpuClock = (long) profile.cpuClockHz()
            * profile.gpuClockRatioNumerator();

        assertEquals(ratioFromGpuClock, ratioFromCpuClock);
    }

    @Test
    void everyRetailModelAndElectricallyDistinctPsoneBoardHasAProfile() {
        Set<String> expectedModels = Set.of(
            "SCPH-1000", "SCPH-1001", "SCPH-1002", "SCPH-3000", "SCPH-3500",
            "SCPH-5000", "SCPH-5001", "SCPH-5003", "SCPH-5500", "SCPH-5501",
            "SCPH-5502", "SCPH-5552", "SCPH-5903", "SCPH-7000", "SCPH-7001",
            "SCPH-7002", "SCPH-7003", "SCPH-7000W", "SCPH-7500", "SCPH-7501",
            "SCPH-7502", "SCPH-7503", "SCPH-9000", "SCPH-9001", "SCPH-9002",
            "SCPH-9003", "SCPH-9903", "SCPH-100", "SCPH-101", "SCPH-102",
            "SCPH-103"
        );
        Set<String> actualModels = Arrays.stream(HardwareProfile.values())
            .filter(profile -> profile.model().startsWith("SCPH-"))
            .map(HardwareProfile::model)
            .collect(Collectors.toSet());

        assertEquals(expectedModels, actualModels);
        for (String psoneModel : Set.of("SCPH-100", "SCPH-101", "SCPH-102", "SCPH-103")) {
            Set<String> boards = Arrays.stream(HardwareProfile.values())
                .filter(profile -> profile.model().equals(psoneModel))
                .map(HardwareProfile::mainboardRevision)
                .collect(Collectors.toSet());
            assertEquals(Set.of("PM-41/-11..-51", "PM-41/-61", "PM-41(2)/-71"), boards);
        }
    }

    @Test
    void psxonpspUsesItsOwnRegionFreeVirtualProfile() {
        HardwareProfile profile = HardwareProfile.PSXONPSP_660;

        assertEquals("PSXONPSP660", profile.model());
        assertEquals("PSP virtual PS1", profile.mainboardRevision());
        assertEquals(HardwareProfile.Region.REGION_FREE, profile.region());
        assertEquals("CXD1817R", profile.cdDriveProfile().decoderRevision());
    }

    @Test
    void everyProfileUsesAnExactClockRatioAndMatchingCdRegion() {
        for (HardwareProfile profile : HardwareProfile.values()) {
            assertEquals(
                (long) profile.gpuClockHz() * profile.gpuClockRatioDenominator(),
                (long) profile.cpuClockHz() * profile.gpuClockRatioNumerator(),
                profile.name()
            );
            assertEquals(44_100, profile.cpuClockHz() / profile.spuClocksPerSample());
            assertNotNull(profile.cdDriveProfile().controllerFirmwareRevision());
            switch (profile.region()) {
                case JAPAN, ASIA -> assertTrue(profile.cdDriveProfile().acceptsLicenseRegion("SCEI"));
                case NORTH_AMERICA -> assertTrue(profile.cdDriveProfile().acceptsLicenseRegion("SCEA"));
                case EUROPE -> assertTrue(profile.cdDriveProfile().acceptsLicenseRegion("SCEE"));
                case REGION_FREE -> {
                    assertTrue(profile.cdDriveProfile().acceptsLicenseRegion("SCEI"));
                    assertTrue(profile.cdDriveProfile().acceptsLicenseRegion("SCEA"));
                    assertTrue(profile.cdDriveProfile().acceptsLicenseRegion("SCEE"));
                }
            }
        }
    }

    @Test
    void biosNameAndCrcDetectionSelectDocumentedModels() {
        assertEquals(HardwareProfile.SCPH_5500_PU_18_NTSC_J,
            HardwareProfile.detect(Path.of("scph5500 (ps-30j).bin")));
        assertEquals(HardwareProfile.SCPH_5903_PU_16_NTSC_ASIA,
            HardwareProfile.forBiosCrc32(0x446EC5B2L));
        assertEquals(HardwareProfile.SCPH_102_PM_41_PAL,
            HardwareProfile.forModel("SCPH-102B"));
        assertEquals(HardwareProfile.PSXONPSP_660,
            HardwareProfile.forBiosCrc32(0x5660F34FL));
        assertEquals(null, HardwareProfile.detectKnown(Path.of("PSXONPSP_fake.bin")));
        assertEquals(null, HardwareProfile.detectKnown(Path.of("unrecognized-bios.bin")));
    }
}
