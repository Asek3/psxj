package nanolive.psxj.library;

import java.nio.file.Path;
import java.time.Instant;

public record GameEntry(
    String libraryId,
    String title,
    String serial,
    String region,
    Path path,
    long totalPlayTimeSeconds,
    Instant lastPlayed
) {

    public String sourceExtension() {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    public boolean hasKnownSerial() {
        return serial != null && !serial.isBlank() && !"UNKNOWN".equalsIgnoreCase(serial);
    }

}
