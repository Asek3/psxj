package nanolive.psxj.emu.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SaveStateManagerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void absoluteLibraryKeysCannotEscapeSaveStateDirectory() {
        SaveStateManager manager = new SaveStateManager(temporaryDirectory);

        Path first = manager.slotPath("E:\\games\\Crash Bandicoot", 1);
        Path same = manager.slotPath("E:\\games\\Crash Bandicoot", 1);
        Path different = manager.slotPath("E:\\games\\Spyro", 1);

        assertTrue(first.startsWith(temporaryDirectory.toAbsolutePath().normalize()));
        assertEquals(first, same);
        assertNotEquals(first, different);
        assertEquals(".json", first.getFileName().toString().substring(
            first.getFileName().toString().length() - 5
        ));
    }

    @Test
    void slotNumbersAreLimitedToVisibleGuiRange() {
        SaveStateManager manager = new SaveStateManager(temporaryDirectory);

        assertThrows(IllegalArgumentException.class, () -> manager.slotPath("game", 0));
        assertThrows(IllegalArgumentException.class, () -> manager.slotPath("game", 10));
    }
}
