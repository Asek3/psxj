package nanolive.psxj.platform.render;

import nanolive.psxj.emu.api.RenderBackend;
import nanolive.psxj.i18n.I18n;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

final class GameOverlay {

    private static final long TOAST_NANOS = 2_500_000_000L;
    private static final long ACHIEVEMENT_NANOS = 5_000_000_000L;
    private static final long ENTER_NANOS = 220_000_000L;
    private static final long TOAST_FADE_NANOS = 220_000_000L;
    private static final DirectColorModel RGB_MODEL = new DirectColorModel(
        24, 0x00FF_0000, 0x0000_FF00, 0x0000_00FF);

    private enum Mode { NONE, SAVE, LOAD, ACHIEVEMENTS }

    private volatile Mode mode = Mode.NONE;
    private volatile GameOverlayHost.SaveStateSlot[] occupiedSlots = emptySlots();
    private volatile Supplier<GameOverlayHost.SaveStateSlot[]> occupiedSupplier = GameOverlay::emptySlots;
    private volatile IntConsumer saveAction = ignored -> { };
    private volatile IntConsumer loadAction = ignored -> { };
    private volatile Consumer<Boolean> openListener = ignored -> { };
    private volatile List<GameOverlayHost.AchievementInfo> achievements = List.of();
    private volatile boolean retroAchievementsEnabled = true;
    private volatile int achievementPage;
    private volatile String toastMessage;
    private volatile long toastExpiresAt;
    private volatile long toastShownAt;
    private volatile long drawerOpenedAt;
    private volatile Achievement achievement;
    private volatile long achievementShownAt;
    private volatile long achievementExpiresAt;
    private volatile List<HitBox> hitBoxes = List.of();
    private volatile int hoveredSlot;
    private volatile int hoveredPageDelta;
    private volatile Mode hoveredCategory = Mode.NONE;
    private final AtomicLong redrawGeneration = new AtomicLong(1L);
    private volatile long renderedGeneration;

    void configure(Supplier<GameOverlayHost.SaveStateSlot[]> occupied, IntConsumer save, IntConsumer load) {
        occupiedSupplier = occupied != null ? occupied : GameOverlay::emptySlots;
        saveAction = save != null ? save : ignored -> { };
        loadAction = load != null ? load : ignored -> { };
        refreshSlots();
    }

    void refreshSlots() {
        GameOverlayHost.SaveStateSlot[] current;
        try {
            current = occupiedSupplier.get();
        } catch (RuntimeException ignored) {
            current = null;
        }
        occupiedSlots = current == null ? emptySlots() : normalizeSlots(current);
        requestRedraw();
    }

    void setAchievements(List<GameOverlayHost.AchievementInfo> values) {
        achievements = values == null ? List.of() : List.copyOf(values);
        achievementPage = 0;
        requestRedraw();
    }

    void setRetroAchievementsEnabled(boolean enabled) {
        retroAchievementsEnabled = enabled;
        achievementPage = 0;
        requestRedraw();
    }

    void updateAchievementBadge(int id, BufferedImage badge) {
        if (badge == null) return;
        List<GameOverlayHost.AchievementInfo> current = achievements;
        ArrayList<GameOverlayHost.AchievementInfo> updated = new ArrayList<>(current.size());
        boolean changed = false;
        for (GameOverlayHost.AchievementInfo item : current) {
            if (item.id() == id) {
                updated.add(new GameOverlayHost.AchievementInfo(item.id(), item.title(),
                    item.description(), item.points(), item.unlocked(), item.supported(),
                    item.unlockedAt(), badge));
                changed = true;
            } else {
                updated.add(item);
            }
        }
        if (changed) {
            achievements = List.copyOf(updated);
            requestRedraw();
        }
    }

    void setOpenListener(Consumer<Boolean> listener) {
        openListener = listener != null ? listener : ignored -> { };
    }

    void showToast(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        long now = System.nanoTime();
        toastMessage = message;
        toastShownAt = now;
        toastExpiresAt = now + TOAST_NANOS;
        requestRedraw();
    }

    void showAchievement(String title, String description, int points, BufferedImage badge) {
        if (title == null || title.isBlank()) {
            return;
        }
        long now = System.nanoTime();
        achievement = new Achievement(title, description == null ? "" : description,
            Math.max(0, points), badge);
        achievementShownAt = now;
        achievementExpiresAt = now + ACHIEVEMENT_NANOS;
        requestRedraw();
    }

    void updateAchievementBadge(String title, BufferedImage badge) {
        Achievement current = achievement;
        if (current != null && badge != null && current.title().equals(title)
            && System.nanoTime() < achievementExpiresAt) {
            achievement = new Achievement(current.title(), current.description(),
                current.points(), badge);
            requestRedraw();
        }
    }

    boolean handleHostKey(RenderBackend.HostKey key, boolean pressed) {
        if (key == RenderBackend.HostKey.SAVE_STATE) {
            if (pressed) open(Mode.SAVE);
            return true;
        }
        if (key == RenderBackend.HostKey.LOAD_STATE) {
            if (pressed) open(Mode.LOAD);
            return true;
        }
        Mode current = mode;
        if (current == Mode.NONE) {
            return false;
        }
        if (!pressed) {
            return true;
        }
        if (key == RenderBackend.HostKey.CANCEL) {
            closePicker();
            return true;
        }
        if (key == RenderBackend.HostKey.CONFIRM) {
            chooseSlot(hoveredSlot > 0 ? hoveredSlot : 1);
            return true;
        }
        int slot = slotForKey(key);
        if (slot > 0) chooseSlot(slot);
        return true;
    }

    boolean handlePointer(RenderBackend.PointerEvent event) {
        if (mode == Mode.NONE) {
            return false;
        }
        HitBox hit = hitAt(event.x(), event.y());
        int previousSlot = hoveredSlot;
        int previousPageDelta = hoveredPageDelta;
        Mode previousCategory = hoveredCategory;
        hoveredSlot = hit != null ? hit.slot : 0;
        hoveredPageDelta = hit != null ? hit.pageDelta : 0;
        hoveredCategory = hit != null && hit.category != null ? hit.category : Mode.NONE;
        if (previousSlot != hoveredSlot || previousCategory != hoveredCategory
            || previousPageDelta != hoveredPageDelta) {
            requestRedraw();
        }
        if (event.action() == RenderBackend.PointerAction.DOWN && event.button() == 1 && hit != null) {
            if (hit.category != null) {
                mode = hit.category;
                hoveredSlot = 0;
                refreshSlots();
            } else if (hit.pageDelta != 0) {
                achievementPage = Math.max(0, achievementPage + hit.pageDelta);
                requestRedraw();
            } else if (hit.slot > 0) {
                chooseSlot(hit.slot);
            }
        }
        return true;
    }

    boolean wantsPointingHand() {
        return mode != Mode.NONE && (hoveredSlot > 0 || hoveredCategory != Mode.NONE
            || hoveredPageDelta != 0);
    }

    boolean consumesPadInput() {
        return mode != Mode.NONE;
    }

    boolean isVisible() {
        long now = System.nanoTime();
        return mode != Mode.NONE || now < toastExpiresAt || now < achievementExpiresAt;
    }

    boolean animationActive(long now) {
        if (mode != Mode.NONE && now - drawerOpenedAt < ENTER_NANOS) {
            return true;
        }
        boolean toastAnimating = now < toastExpiresAt
            && (now - toastShownAt < TOAST_FADE_NANOS
                || toastExpiresAt - now < TOAST_FADE_NANOS);
        boolean achievementAnimating = now < achievementExpiresAt
            && (now - achievementShownAt < ENTER_NANOS
                || achievementExpiresAt - now < TOAST_FADE_NANOS);
        return toastAnimating || achievementAnimating;
    }

    boolean redrawRequested() {
        return redrawGeneration.get() != renderedGeneration;
    }

    void requestRedraw() {
        redrawGeneration.incrementAndGet();
    }

    long redrawGeneration() {
        return redrawGeneration.get();
    }

    void markRedrawn(long generation) {
        renderedGeneration = generation;
    }

    void render(int[] pixels, int width, int height) {
        if (pixels == null || width <= 0 || height <= 0
            || pixels.length < (long) width * height || !isVisible()) {
            return;
        }
        DataBufferInt data = new DataBufferInt(pixels, width * height);
        WritableRaster raster = Raster.createPackedRaster(data, width, height, width,
            new int[] {0x00FF_0000, 0x0000_FF00, 0x0000_00FF}, null);
        BufferedImage image = new BufferedImage(RGB_MODEL, raster, false, null);
        Graphics2D graphics = image.createGraphics();
        try {
            render(graphics, width, height);
        } finally {
            graphics.dispose();
        }
    }

    void render(Graphics2D graphics, int width, int height) {
        long now = System.nanoTime();
        Mode currentMode = mode;
        String toast = now < toastExpiresAt ? toastMessage : null;
        Achievement currentAchievement = now < achievementExpiresAt ? achievement : null;
        if (currentMode == Mode.NONE && toast == null && currentAchievement == null) {
            hitBoxes = List.of();
            return;
        }
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (currentMode != Mode.NONE) {
            drawDrawer(graphics, width, height, currentMode, now);
        } else {
            hitBoxes = List.of();
        }
        if (toast != null) drawToast(graphics, width, height, toast, now);
        if (currentAchievement != null) {
            drawAchievement(graphics, width, height, currentAchievement, now);
        }
    }

    private void open(Mode requestedMode) {
        refreshSlots();
        if (mode == Mode.NONE) {
            drawerOpenedAt = System.nanoTime();
            openListener.accept(true);
        }
        mode = requestedMode;
        hoveredSlot = 0;
        hoveredPageDelta = 0;
        hoveredCategory = Mode.NONE;
        requestRedraw();
    }

    private void closePicker() {
        mode = Mode.NONE;
        hitBoxes = List.of();
        hoveredSlot = 0;
        hoveredPageDelta = 0;
        hoveredCategory = Mode.NONE;
        requestRedraw();
        openListener.accept(false);
    }

    private void chooseSlot(int slot) {
        Mode selectedMode = mode;
        if (selectedMode != Mode.SAVE && selectedMode != Mode.LOAD || slot < 1 || slot > 9) return;
        if (selectedMode == Mode.LOAD && !occupiedSlots[slot - 1].occupied()) {
            showToast(I18n.tr("overlay.slotEmpty", slot));
            return;
        }
        closePicker();
        if (selectedMode == Mode.SAVE) {
            showToast(I18n.tr("overlay.stateSaving", slot));
            saveAction.accept(slot);
        } else {
            showToast(I18n.tr("overlay.stateLoading", slot));
            loadAction.accept(slot);
        }
    }

    private void drawDrawer(Graphics2D graphics, int width, int height, Mode currentMode, long now) {
        float scale = Math.clamp(height / 720f, 0.65f, 1.65f);
        int margin = Math.max(8, Math.round(18 * scale));
        int drawerWidth = Math.min(width - margin * 2, Math.round(680 * scale));
        int drawerHeight = height - margin * 2;
        int radius = Math.max(12, Math.round(22 * scale));
        int categoryWidth = Math.min(drawerWidth / 3, Math.round(170 * scale));
        int padding = Math.max(8, Math.round(20 * scale));
        float progress = Math.clamp((now - drawerOpenedAt) / (float) ENTER_NANOS, 0f, 1f);
        float eased = 1f - (1f - progress) * (1f - progress) * (1f - progress);
        int drawerX = margin - Math.round((1f - eased) * (drawerWidth + margin));
        int drawerY = margin;

        graphics.setComposite(AlphaComposite.SrcOver.derive(0.42f * eased));
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, width, height);
        graphics.setComposite(AlphaComposite.SrcOver);
        for (int shadow = Math.max(2, Math.round(8 * scale)); shadow >= 2; shadow -= 2) {
            graphics.setColor(new Color(0, 0, 0, 13));
            graphics.fillRoundRect(drawerX + shadow, drawerY + shadow,
                drawerWidth, drawerHeight, radius, radius);
        }
        graphics.setPaint(new GradientPaint(drawerX, drawerY, new Color(23, 29, 43, 252),
            drawerX + drawerWidth, drawerY + drawerHeight, new Color(11, 16, 27, 253)));
        graphics.fillRoundRect(drawerX, drawerY, drawerWidth, drawerHeight, radius, radius);
        graphics.setColor(new Color(255, 255, 255, 30));
        graphics.setStroke(new BasicStroke(Math.max(1f, scale)));
        graphics.drawRoundRect(drawerX, drawerY, drawerWidth - 1, drawerHeight - 1, radius, radius);

        int dividerX = drawerX + categoryWidth;
        graphics.setColor(new Color(255, 255, 255, 22));
        graphics.drawLine(dividerX, drawerY + padding, dividerX, drawerY + drawerHeight - padding);
        ArrayList<HitBox> boxes = new ArrayList<>(11);
        drawCategories(graphics, boxes, width, height, drawerX, drawerY,
            categoryWidth, drawerHeight, padding, scale, currentMode);
        if (currentMode == Mode.ACHIEVEMENTS) {
            drawAchievementList(graphics, boxes, width, height, dividerX, drawerY,
                drawerWidth - categoryWidth, drawerHeight, padding, scale);
        } else {
            drawSlotList(graphics, boxes, width, height, dividerX, drawerY,
                drawerWidth - categoryWidth, drawerHeight, padding, scale, currentMode);
        }
        hitBoxes = List.copyOf(boxes);
    }

    private void drawCategories(Graphics2D graphics, ArrayList<HitBox> boxes,
                                int width, int height, int x, int y, int areaWidth,
                                int areaHeight, int padding, float scale, Mode currentMode) {
        int brandSize = Math.clamp(Math.round(21 * scale), 13, 34);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, brandSize));
        graphics.setColor(Color.WHITE);
        graphics.drawString("PSXJ", x + padding, y + padding + brandSize);
        int itemHeight = Math.max(28, Math.round(52 * scale));
        int gap = Math.max(5, Math.round(8 * scale));
        int top = y + padding + brandSize + Math.max(14, Math.round(28 * scale));
        drawCategory(graphics, boxes, width, height, Mode.SAVE, currentMode,
            I18n.tr("overlay.categorySave"), x + padding, top,
            areaWidth - padding * 2, itemHeight, scale);
        drawCategory(graphics, boxes, width, height, Mode.LOAD, currentMode,
            I18n.tr("overlay.categoryLoad"), x + padding, top + itemHeight + gap,
            areaWidth - padding * 2, itemHeight, scale);
        drawCategory(graphics, boxes, width, height, Mode.ACHIEVEMENTS, currentMode,
            I18n.tr("overlay.categoryAchievements"), x + padding, top + (itemHeight + gap) * 2,
            areaWidth - padding * 2, itemHeight, scale);
        int hintSize = Math.clamp(Math.round(12 * scale), 9, 18);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, hintSize));
        graphics.setColor(new Color(139, 151, 174));
        graphics.drawString("F5  /  F8", x + padding, y + areaHeight - padding);
    }

    private void drawCategory(Graphics2D graphics, ArrayList<HitBox> boxes,
                              int width, int height, Mode category, Mode currentMode,
                              String text, int x, int y, int itemWidth, int itemHeight,
                              float scale) {
        boolean selected = category == currentMode;
        boolean hovered = category == hoveredCategory;
        if (selected || hovered) {
            graphics.setColor(selected ? new Color(65, 113, 187, 190)
                : new Color(255, 255, 255, 18));
            graphics.fillRoundRect(x, y, itemWidth, itemHeight, itemHeight / 3, itemHeight / 3);
        }
        if (selected) {
            graphics.setColor(new Color(101, 164, 255));
            graphics.fillRoundRect(x + Math.max(3, Math.round(5 * scale)), y + itemHeight / 4,
                Math.max(3, Math.round(4 * scale)), itemHeight / 2, 6, 6);
        }
        int fontSize = Math.clamp(Math.round(14 * scale), 10, 22);
        graphics.setFont(new Font(Font.SANS_SERIF, selected ? Font.BOLD : Font.PLAIN, fontSize));
        graphics.setColor(selected ? Color.WHITE : new Color(177, 187, 205));
        graphics.drawString(text, x + Math.max(12, Math.round(18 * scale)),
            y + (itemHeight + fontSize) / 2 - 2);
        boxes.add(HitBox.category(category, x, y, itemWidth, itemHeight, width, height));
    }

    private void drawSlotList(Graphics2D graphics, ArrayList<HitBox> boxes,
                              int width, int height, int x, int y, int areaWidth,
                              int areaHeight, int padding, float scale, Mode currentMode) {
        int titleSize = Math.clamp(Math.round(24 * scale), 14, 38);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, titleSize));
        graphics.setColor(Color.WHITE);
        graphics.drawString(I18n.tr(currentMode == Mode.SAVE
            ? "overlay.saveTitle" : "overlay.loadTitle"), x + padding, y + padding + titleSize);
        int helpSize = Math.clamp(Math.round(12 * scale), 9, 18);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, helpSize));
        graphics.setColor(new Color(155, 167, 188));
        int helpY = y + padding + titleSize + Math.max(10, Math.round(18 * scale));
        graphics.drawString(I18n.tr("overlay.slotHelpShort"), x + padding, helpY);
        int listTop = helpY + Math.max(10, Math.round(16 * scale));
        int listBottom = y + areaHeight - padding;
        int gap = Math.max(2, Math.round(6 * scale));
        int rowHeight = Math.max(10, (listBottom - listTop - gap * 8) / 9);
        int rowWidth = areaWidth - padding * 2;
        GameOverlayHost.SaveStateSlot[] slots = occupiedSlots;
        for (int slot = 1; slot <= 9; slot++) {
            int rowY = listTop + (slot - 1) * (rowHeight + gap);
            GameOverlayHost.SaveStateSlot slotInfo = slots[slot - 1];
            boolean occupied = slotInfo.occupied();
            boolean hovered = hoveredSlot == slot;
            graphics.setColor(hovered ? new Color(54, 83, 137, 245)
                : new Color(255, 255, 255, 12));
            graphics.fillRoundRect(x + padding, rowY, rowWidth, rowHeight,
                Math.max(8, rowHeight / 3), Math.max(8, rowHeight / 3));
            graphics.setColor(hovered ? new Color(112, 169, 255)
                : new Color(255, 255, 255, 22));
            graphics.drawRoundRect(x + padding, rowY, rowWidth, rowHeight,
                Math.max(8, rowHeight / 3), Math.max(8, rowHeight / 3));
            int rowFont = Math.clamp(Math.round(14 * scale), 9, Math.max(9, rowHeight / 2));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, rowFont));
            graphics.setColor(Color.WHITE);
            int baseline = rowY + (rowHeight + rowFont) / 2 - 2;
            graphics.drawString(I18n.tr("overlay.slotNumber", slot),
                x + padding + Math.max(10, Math.round(14 * scale)), baseline);
            String state = I18n.tr(occupied ? "overlay.slotOccupied" : "overlay.slotEmptyLabel");
            if (occupied && slotInfo.savedAt() != null) {
                state += " · " + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault()).format(slotInfo.savedAt());
            }
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, rowFont));
            graphics.setColor(occupied ? new Color(100, 222, 165) : new Color(148, 159, 180));
            FontMetrics metrics = graphics.getFontMetrics();
            graphics.drawString(state, x + padding + rowWidth - metrics.stringWidth(state)
                - Math.max(10, Math.round(14 * scale)), baseline);
            boxes.add(HitBox.slot(slot, x + padding, rowY, rowWidth, rowHeight, width, height));
        }
    }

    private void drawAchievementList(Graphics2D graphics, ArrayList<HitBox> boxes,
                                     int width, int height, int x, int y, int areaWidth,
                                     int areaHeight, int padding, float scale) {
        boolean featureEnabled = retroAchievementsEnabled;
        List<GameOverlayHost.AchievementInfo> items = featureEnabled
            ? achievements
            : List.of();
        long unlocked = items.stream().filter(GameOverlayHost.AchievementInfo::unlocked).count();
        int titleSize = Math.clamp(Math.round(24 * scale), 14, 38);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, titleSize));
        graphics.setColor(Color.WHITE);
        graphics.drawString(I18n.tr("overlay.achievementsTitle"), x + padding,
            y + padding + titleSize);
        int summarySize = Math.clamp(Math.round(12 * scale), 9, 18);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, summarySize));
        graphics.setColor(new Color(155, 167, 188));
        int summaryY = y + padding + titleSize + Math.max(10, Math.round(18 * scale));
        graphics.drawString(I18n.tr("overlay.achievementsProgress", unlocked, items.size()),
            x + padding, summaryY);

        int footerHeight = Math.max(26, Math.round(38 * scale));
        int listTop = summaryY + Math.max(10, Math.round(16 * scale));
        int listBottom = y + areaHeight - padding - footerHeight;
        int gap = Math.max(4, Math.round(7 * scale));
        int desiredRowHeight = Math.max(48, Math.round(72 * scale));
        int perPage = Math.max(1, (listBottom - listTop + gap) / (desiredRowHeight + gap));
        int pageCount = Math.max(1, (items.size() + perPage - 1) / perPage);
        achievementPage = Math.min(achievementPage, pageCount - 1);
        int start = achievementPage * perPage;
        int end = Math.min(items.size(), start + perPage);
        int rowWidth = areaWidth - padding * 2;
        for (int index = start; index < end; index++) {
            GameOverlayHost.AchievementInfo item = items.get(index);
            int row = index - start;
            int rowY = listTop + row * (desiredRowHeight + gap);
            graphics.setColor(item.unlocked() ? new Color(35, 83, 70, 190)
                : new Color(255, 255, 255, 12));
            graphics.fillRoundRect(x + padding, rowY, rowWidth, desiredRowHeight,
                Math.max(9, Math.round(13 * scale)), Math.max(9, Math.round(13 * scale)));
            int iconSize = desiredRowHeight - Math.max(10, Math.round(14 * scale));
            int iconX = x + padding + Math.max(5, Math.round(7 * scale));
            int iconY = rowY + (desiredRowHeight - iconSize) / 2;
            if (item.badge() != null) {
                graphics.drawImage(item.badge(), iconX, iconY, iconSize, iconSize, null);
            } else {
                graphics.setColor(new Color(55, 67, 88));
                graphics.fillRoundRect(iconX, iconY, iconSize, iconSize, 9, 9);
            }
            int textX = iconX + iconSize + Math.max(8, Math.round(11 * scale));
            int available = x + padding + rowWidth - textX - Math.max(8, Math.round(10 * scale));
            int rowTitleSize = Math.clamp(Math.round(14 * scale), 10, 21);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, rowTitleSize));
            graphics.setColor(item.supported() ? Color.WHITE : new Color(150, 154, 164));
            String points = I18n.tr("overlay.achievementPoints", item.points());
            FontMetrics titleMetrics = graphics.getFontMetrics();
            int pointsWidth = titleMetrics.stringWidth(points);
            graphics.drawString(ellipsize(titleMetrics, item.title(), Math.max(20, available - pointsWidth - 8)),
                textX, rowY + Math.round(25 * scale));
            graphics.setColor(item.unlocked() ? new Color(100, 222, 165) : new Color(130, 155, 195));
            graphics.drawString(points, x + padding + rowWidth - pointsWidth - 9,
                rowY + Math.round(25 * scale));
            int descriptionSize = Math.clamp(Math.round(11 * scale), 8, 17);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, descriptionSize));
            graphics.setColor(new Color(166, 177, 196));
            String description = item.supported() ? item.description()
                : I18n.tr("overlay.achievementUnsupported");
            graphics.drawString(ellipsize(graphics.getFontMetrics(), description, available),
                textX, rowY + desiredRowHeight - Math.max(9, Math.round(12 * scale)));
        }
        if (items.isEmpty()) {
            graphics.setColor(new Color(155, 167, 188));
            String messageKey = featureEnabled
                ? "overlay.achievementsUnavailable"
                : "overlay.retroAchievementsDisabled";
            graphics.drawString(I18n.tr(messageKey), x + padding, listTop + 24);
        }

        int buttonWidth = Math.max(70, Math.round(92 * scale));
        int buttonHeight = Math.max(24, Math.round(32 * scale));
        int buttonY = y + areaHeight - padding - buttonHeight;
        if (achievementPage > 0) {
            drawPageButton(graphics, boxes, width, height, -1, I18n.tr("overlay.previousPage"),
                x + padding, buttonY, buttonWidth, buttonHeight, scale);
        }
        String page = I18n.tr("overlay.page", achievementPage + 1, pageCount);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, summarySize));
        graphics.setColor(new Color(155, 167, 188));
        graphics.drawString(page, x + (areaWidth - graphics.getFontMetrics().stringWidth(page)) / 2,
            buttonY + (buttonHeight + summarySize) / 2 - 2);
        if (achievementPage + 1 < pageCount) {
            drawPageButton(graphics, boxes, width, height, 1, I18n.tr("overlay.nextPage"),
                x + areaWidth - padding - buttonWidth, buttonY, buttonWidth, buttonHeight, scale);
        }
    }

    private void drawPageButton(Graphics2D graphics, ArrayList<HitBox> boxes,
                                int width, int height, int delta, String label,
                                int x, int y, int buttonWidth, int buttonHeight, float scale) {
        graphics.setColor(hoveredPageDelta == delta ? new Color(65, 113, 187, 210)
            : new Color(255, 255, 255, 18));
        graphics.fillRoundRect(x, y, buttonWidth, buttonHeight, buttonHeight / 3, buttonHeight / 3);
        int fontSize = Math.clamp(Math.round(12 * scale), 9, 18);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        graphics.setColor(Color.WHITE);
        FontMetrics metrics = graphics.getFontMetrics();
        graphics.drawString(label, x + (buttonWidth - metrics.stringWidth(label)) / 2,
            y + (buttonHeight + fontSize) / 2 - 2);
        boxes.add(HitBox.page(delta, x, y, buttonWidth, buttonHeight, width, height));
    }

    private void drawToast(Graphics2D graphics, int width, int height, String message, long now) {
        long remaining = toastExpiresAt - now;
        float fadeIn = Math.clamp((now - toastShownAt) / (float) TOAST_FADE_NANOS, 0f, 1f);
        float fadeOut = Math.clamp(remaining / (float) TOAST_FADE_NANOS, 0f, 1f);
        float opacity = Math.min(fadeIn, fadeOut);
        int fontSize = Math.clamp(height / 45, 12, 24);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        FontMetrics metrics = graphics.getFontMetrics();
        int padding = Math.max(10, fontSize / 2);
        int boxWidth = Math.min(width - 24, metrics.stringWidth(message) + padding * 3);
        int boxHeight = metrics.getHeight() + padding;
        int x = (width - boxWidth) / 2;
        int y = height - boxHeight - Math.max(16, height / 18);
        graphics.setComposite(AlphaComposite.SrcOver.derive(opacity));
        graphics.setColor(new Color(15, 21, 34, 244));
        graphics.fillRoundRect(x, y, boxWidth, boxHeight, padding, padding);
        graphics.setColor(new Color(255, 255, 255, 42));
        graphics.drawRoundRect(x, y, boxWidth, boxHeight, padding, padding);
        graphics.setColor(new Color(96, 163, 255));
        graphics.fillRoundRect(x + padding / 2, y + padding / 2,
            Math.max(3, padding / 3), boxHeight - padding, padding, padding);
        graphics.setColor(Color.WHITE);
        int textX = Math.max(x + padding * 2, (width - metrics.stringWidth(message)) / 2);
        graphics.drawString(message, textX, y + padding / 2 + metrics.getAscent());
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private void drawAchievement(Graphics2D graphics, int width, int height,
                                 Achievement current, long now) {
        float scale = Math.clamp(height / 720f, 0.75f, 1.75f);
        int cardWidth = Math.min(width - 24, Math.round(380 * scale));
        int cardHeight = Math.round(88 * scale);
        int margin = Math.max(12, Math.round(18 * scale));
        float enter = Math.clamp((now - achievementShownAt) / (float) ENTER_NANOS, 0f, 1f);
        float exit = Math.clamp((achievementExpiresAt - now) / (float) TOAST_FADE_NANOS, 0f, 1f);
        float opacity = Math.min(enter, exit);
        int x = width - cardWidth - margin + Math.round((1f - enter) * (cardWidth + margin));
        int y = margin;
        int radius = Math.round(17 * scale);
        int padding = Math.round(10 * scale);
        int badgeSize = Math.round(64 * scale);
        int badgeY = y + (cardHeight - badgeSize) / 2;

        graphics.setComposite(AlphaComposite.SrcOver.derive(opacity));
        graphics.setColor(new Color(8, 13, 23, 236));
        graphics.fillRoundRect(x, y, cardWidth, cardHeight, radius, radius);
        graphics.setColor(new Color(103, 166, 255, 80));
        graphics.setStroke(new BasicStroke(Math.max(1f, scale)));
        graphics.drawRoundRect(x, y, cardWidth - 1, cardHeight - 1, radius, radius);

        BufferedImage badge = current.badge();
        if (badge != null) {
            graphics.drawImage(badge, x + padding, badgeY, badgeSize, badgeSize, null);
        } else {
            graphics.setColor(new Color(65, 113, 187));
            graphics.fillRoundRect(x + padding, badgeY, badgeSize, badgeSize,
                Math.round(12 * scale), Math.round(12 * scale));
            graphics.setColor(new Color(255, 214, 93));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(28 * scale)));
            graphics.drawString("★", x + padding + badgeSize / 4,
                badgeY + badgeSize * 3 / 4);
        }

        int textX = x + padding + badgeSize + padding;
        int available = cardWidth - (textX - x) - padding;
        int labelSize = Math.clamp(Math.round(10 * scale), 9, 17);
        int labelBaseline = y + padding + labelSize;
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, labelSize));
        graphics.setColor(new Color(103, 166, 255));
        graphics.drawString(I18n.tr("overlay.achievementUnlocked"), textX,
            labelBaseline);
        String points = I18n.tr("overlay.achievementPoints", current.points());
        FontMetrics labelMetrics = graphics.getFontMetrics();
        graphics.drawString(points, x + cardWidth - padding - labelMetrics.stringWidth(points),
            labelBaseline);

        int titleSize = Math.clamp(Math.round(15 * scale), 12, 25);
        int titleBaseline = labelBaseline + titleSize + Math.round(4 * scale);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, titleSize));
        graphics.setColor(Color.WHITE);
        graphics.drawString(ellipsize(graphics.getFontMetrics(), current.title(), available),
            textX, titleBaseline);
        int descriptionSize = Math.clamp(Math.round(11 * scale), 9, 18);
        int descriptionBaseline = titleBaseline + descriptionSize + Math.round(4 * scale);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, descriptionSize));
        graphics.setColor(new Color(172, 183, 202));
        graphics.drawString(ellipsize(graphics.getFontMetrics(), current.description(), available),
            textX, descriptionBaseline);
        graphics.setComposite(AlphaComposite.SrcOver);
    }

    private static String ellipsize(FontMetrics metrics, String value, int width) {
        if (metrics.stringWidth(value) <= width) {
            return value;
        }
        String suffix = "…";
        int end = value.length();
        while (end > 0 && metrics.stringWidth(value.substring(0, end) + suffix) > width) {
            end--;
        }
        return value.substring(0, end) + suffix;
    }

    private HitBox hitAt(float x, float y) {
        for (HitBox box : hitBoxes) {
            if (x >= box.left && x <= box.right && y >= box.top && y <= box.bottom) return box;
        }
        return null;
    }

    private static int slotForKey(RenderBackend.HostKey key) {
        return switch (key) {
            case SLOT_1 -> 1;
            case SLOT_2 -> 2;
            case SLOT_3 -> 3;
            case SLOT_4 -> 4;
            case SLOT_5 -> 5;
            case SLOT_6 -> 6;
            case SLOT_7 -> 7;
            case SLOT_8 -> 8;
            case SLOT_9 -> 9;
            default -> 0;
        };
    }

    private static GameOverlayHost.SaveStateSlot[] emptySlots() {
        GameOverlayHost.SaveStateSlot[] slots = new GameOverlayHost.SaveStateSlot[9];
        Arrays.fill(slots, GameOverlayHost.SaveStateSlot.empty());
        return slots;
    }

    private static GameOverlayHost.SaveStateSlot[] normalizeSlots(
        GameOverlayHost.SaveStateSlot[] source) {
        GameOverlayHost.SaveStateSlot[] slots = emptySlots();
        for (int i = 0; i < Math.min(slots.length, source.length); i++) {
            if (source[i] != null) slots[i] = source[i];
        }
        return slots;
    }

    private record HitBox(Mode category, int slot, int pageDelta,
                          float left, float top, float right, float bottom) {
        private static HitBox category(Mode mode, int x, int y, int width, int height,
                                       int surfaceWidth, int surfaceHeight) {
            return create(mode, 0, 0, x, y, width, height, surfaceWidth, surfaceHeight);
        }

        private static HitBox slot(int slot, int x, int y, int width, int height,
                                   int surfaceWidth, int surfaceHeight) {
            return create(null, slot, 0, x, y, width, height, surfaceWidth, surfaceHeight);
        }

        private static HitBox page(int delta, int x, int y, int width, int height,
                                   int surfaceWidth, int surfaceHeight) {
            return create(null, 0, delta, x, y, width, height, surfaceWidth, surfaceHeight);
        }

        private static HitBox create(Mode mode, int slot, int pageDelta,
                                     int x, int y, int width, int height,
                                     int surfaceWidth, int surfaceHeight) {
            return new HitBox(mode, slot, pageDelta,
                x / (float) surfaceWidth, y / (float) surfaceHeight,
                (x + width) / (float) surfaceWidth, (y + height) / (float) surfaceHeight);
        }
    }

    private record Achievement(String title, String description, int points, BufferedImage badge) {
    }
}
