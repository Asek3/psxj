package nanolive.psxj.gui.panels;

import nanolive.psxj.gui.UiFormatters;
import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;

public final class SettingsUi {

    private SettingsUi() {
    }

    public static <T> void humanizeCombo(JComboBox<T> comboBox) {
        configureCombo(comboBox, true);
    }

    public static <T> void padCombo(JComboBox<T> comboBox) {
        configureCombo(comboBox, false);
    }

    private static <T> void configureCombo(JComboBox<T> comboBox, boolean humanizeEnums) {
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (humanizeEnums && value instanceof Enum<?> enumValue) {
                    setText(UiFormatters.humanizeEnum(enumValue));
                }
                setVerticalAlignment(CENTER);
                setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
                return this;
            }
        });
    }
}
