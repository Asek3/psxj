package nanolive.psxj.gui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class UiFormatters {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private UiFormatters() {
    }

    public static String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "0 min";
        }
        long hours = totalSeconds / 3600;
        long minutes = Math.max(1, (totalSeconds % 3600) / 60);
        if (hours <= 0) {
            return minutes + " min";
        }
        return hours + " h " + minutes + " min";
    }

    public static String formatInstant(Instant instant) {
        if (instant == null || Instant.EPOCH.equals(instant)) {
            return "-";
        }
        return DATE_TIME_FORMATTER.format(instant.atZone(ZoneId.systemDefault()));
    }

    public static String humanizeEnum(Enum<?> value) {
        if (value == null) {
            return "-";
        }
        String canonical = switch (value.name()) {
            case "AWT", "SDL", "D3D11", "D3D12" -> value.name();
            case "OPENAL" -> "OpenAL";
            case "OPENGL" -> "OpenGL";
            default -> null;
        };
        if (canonical != null) {
            return canonical;
        }
        String[] parts = value.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
