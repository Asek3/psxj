package nanolive.psxj.gui;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import nanolive.psxj.config.ThemeOption;
import nanolive.psxj.util.Log;

import java.awt.Window;
import java.awt.Cursor;
import java.util.Locale;
import java.util.prefs.Preferences;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class ThemeManager {

    private static final AtomicBoolean DEFAULTS_REGISTERED = new AtomicBoolean();

    private ThemeManager() {
    }

    public static void applyTheme(ThemeOption option) {
        ThemeOption resolved = resolve(option);
        try {
            registerDefaults();
            switch (resolved) {
                case LIGHT -> UIManager.setLookAndFeel(new FlatLightLaf());
                case DARK -> UIManager.setLookAndFeel(new FlatDarculaLaf());
                default -> throw new IllegalStateException("Unsupported theme: " + resolved);
            }
            installInteractiveCursors();
            for (Window window : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(window);
            }
        } catch (Exception ex) {
            Log.warn("Failed to apply theme " + resolved + ": " + ex.getMessage());
        }
    }

    private static void installInteractiveCursors() {
        Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        for (String key : new String[]{
            "Button.cursor",
            "ToggleButton.cursor",
            "CheckBox.cursor",
            "RadioButton.cursor",
            "ComboBox.cursor",
            "Menu.cursor",
            "MenuItem.cursor",
            "CheckBoxMenuItem.cursor",
            "RadioButtonMenuItem.cursor",
            "TabbedPane.cursor"
        }) {
            UIManager.put(key, hand);
        }
    }

    private static void registerDefaults() {
        if (!DEFAULTS_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        System.setProperty("flatlaf.animation", "true");
        System.setProperty("flatlaf.useWindowDecorations", "true");
        System.setProperty("flatlaf.menuBarEmbedded", "true");
        FlatLaf.registerCustomDefaultsSource("nanolive.psxj.themes");
    }

    public static ThemeOption resolve(ThemeOption option) {
        if (option == ThemeOption.SYSTEM) {
            return detectSystemTheme();
        }
        return option == null ? ThemeOption.DARK : option;
    }

    public static boolean isDarkTheme() {
        if (UIManager.getLookAndFeel() == null) {
            return true;
        }
        String value = (UIManager.getLookAndFeel().getName() + " " + UIManager.getLookAndFeel().getClass().getName()).toLowerCase(Locale.ROOT);
        return value.contains("dark") || value.contains("darcula");
    }

    private static ThemeOption detectSystemTheme() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            try {
                Preferences prefs = Preferences.userRoot().node("Software/Microsoft/Windows/CurrentVersion/Themes/Personalize");
                int lightTheme = prefs.getInt("AppsUseLightTheme", 1);
                return lightTheme == 0 ? ThemeOption.DARK : ThemeOption.LIGHT;
            } catch (Exception ex) {
                Log.debug("System theme detection failed on Windows: " + ex.getMessage());
            }
        }
        String lookAndFeelName = UIManager.getSystemLookAndFeelClassName().toLowerCase(Locale.ROOT);
        return lookAndFeelName.contains("dark") ? ThemeOption.DARK : ThemeOption.LIGHT;
    }
}
