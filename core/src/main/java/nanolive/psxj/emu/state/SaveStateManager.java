package nanolive.psxj.emu.state;

import nanolive.psxj.emu.PsxEmulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class SaveStateManager {

    private final Path root;

    public SaveStateManager(Path root) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
    }

    public Path slotPath(String gameKey, int slot) {
        if (slot < 1 || slot > 9) {
            throw new IllegalArgumentException("Save-state slot must be between 1 and 9.");
        }
        return root.resolve(fileKey(gameKey) + ".slot" + slot + ".json");
    }

    public void save(PsxEmulator emulator, String gameKey, int slot) throws IOException {
        Files.createDirectories(root);
        Path destination = slotPath(gameKey, slot);
        Path temporary = Files.createTempFile(root, destination.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, emulator.saveStateJson(), StandardCharsets.UTF_8);
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void load(PsxEmulator emulator, String gameKey, int slot) throws IOException {
        emulator.loadStateJson(Files.readString(slotPath(gameKey, slot), StandardCharsets.UTF_8));
    }

    private static String fileKey(String gameKey) {
        Objects.requireNonNull(gameKey);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(gameKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
