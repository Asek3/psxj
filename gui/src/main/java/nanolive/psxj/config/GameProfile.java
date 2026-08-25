package nanolive.psxj.config;

import java.nio.file.Path;
import java.util.Optional;

public final class GameProfile {

    private Path memoryCard1Path;
    private Path memoryCard2Path;

    public Optional<Path> memoryCard1Path() {
        return Optional.ofNullable(memoryCard1Path);
    }

    public Optional<Path> memoryCard2Path() {
        return Optional.ofNullable(memoryCard2Path);
    }
}
