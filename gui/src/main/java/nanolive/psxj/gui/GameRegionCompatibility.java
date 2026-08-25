package nanolive.psxj.gui;

import nanolive.psxj.emu.hardware.HardwareProfile;

final class GameRegionCompatibility {

    private GameRegionCompatibility() {
    }

    static boolean isCompatible(String gameRegion, HardwareProfile.Region biosRegion) {
        if (biosRegion == null || biosRegion == HardwareProfile.Region.REGION_FREE
            || gameRegion == null || gameRegion.equalsIgnoreCase("Unknown")) {
            return true;
        }
        return switch (gameRegion.toUpperCase(java.util.Locale.ROOT)) {
            case "PAL" -> biosRegion == HardwareProfile.Region.EUROPE;
            case "NTSC-U" -> biosRegion == HardwareProfile.Region.NORTH_AMERICA;
            case "NTSC-J" -> biosRegion == HardwareProfile.Region.JAPAN
                || biosRegion == HardwareProfile.Region.ASIA;
            default -> true;
        };
    }

    static String displayName(HardwareProfile.Region region) {
        return switch (region) {
            case JAPAN -> "NTSC-J";
            case NORTH_AMERICA -> "NTSC-U";
            case EUROPE -> "PAL";
            case ASIA -> "NTSC-J (Asia)";
            case REGION_FREE -> "Region-free";
        };
    }
}
