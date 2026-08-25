package nanolive.psxj.platform.render;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Controls the game-window overlay without exposing it to the emulator core. */
public interface GameOverlayHost {

    void configureSaveStateOverlay(Supplier<SaveStateSlot[]> occupiedSlots,
                                   IntConsumer saveAction,
                                   IntConsumer loadAction);

    void setAchievements(List<AchievementInfo> achievements);

    void setRetroAchievementsEnabled(boolean enabled);

    void updateAchievementBadge(int id, BufferedImage badge);

    void setOverlayOpenListener(Consumer<Boolean> listener);

    void showOverlayToast(String message);

    void showAchievement(String title, String description, int points, BufferedImage badge);

    void updateAchievementBadge(String title, BufferedImage badge);

    void refreshOverlaySlots();

    record SaveStateSlot(boolean occupied, Instant savedAt) {
        public static SaveStateSlot empty() {
            return new SaveStateSlot(false, null);
        }
    }

    record AchievementInfo(int id, String title, String description, int points,
                           boolean unlocked, boolean supported, Instant unlockedAt,
                           BufferedImage badge) {
    }
}
