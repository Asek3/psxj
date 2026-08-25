package nanolive.psxj.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.border.Border;

final class ModernUi {

    static final String STYLE = "FlatLaf.style";

    private ModernUi() {
    }

    static Color accent() {
        return ThemeManager.isDarkTheme() ? new Color(104, 164, 255) : new Color(45, 104, 210);
    }

    static Color divider() {
        return ThemeManager.isDarkTheme() ? new Color(255, 255, 255, 24) : new Color(27, 45, 73, 28);
    }

    static Color secondaryText() {
        return ThemeManager.isDarkTheme() ? new Color(159, 170, 191) : new Color(82, 96, 119);
    }

    static Color cardOutline() {
        return ThemeManager.isDarkTheme() ? new Color(255, 255, 255, 25) : new Color(52, 82, 126, 35);
    }

    static Border windowContentBorder() {
        return BorderFactory.createMatteBorder(1, 0, 0, 0, divider());
    }

    static Border cardBorder(int vertical, int horizontal) {
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(cardOutline(), 1, true),
            BorderFactory.createEmptyBorder(vertical, horizontal, vertical, horizontal));
    }

    static void styleButton(AbstractButton button, boolean primary) {
        String style = primary
            ? "arc: 16; borderWidth: 0; focusWidth: 1; innerFocusWidth: 0; "
                + "margin: 9,16,9,16; font: +1; background: $Component.accentColor; foreground: #ffffff"
            : "arc: 16; borderWidth: 1; focusWidth: 1; innerFocusWidth: 0; "
                + "margin: 9,14,9,14; font: +1";
        button.putClientProperty(STYLE, style);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    static void installPointingHands(Component component) {
        if (isInteractive(component)) {
            component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                installPointingHands(child);
            }
        }
        if (component instanceof JMenu menu) {
            for (Component child : menu.getMenuComponents()) {
                installPointingHands(child);
            }
        }
    }

    private static boolean isInteractive(Component component) {
        return component instanceof AbstractButton
            || component instanceof JComboBox<?>
            || component instanceof JList<?>
            || component instanceof JSpinner
            || component instanceof JTable
            || component instanceof JMenu
            || component instanceof JMenuItem
            || component instanceof JTabbedPane;
    }
}
