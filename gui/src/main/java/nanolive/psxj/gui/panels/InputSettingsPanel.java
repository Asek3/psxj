package nanolive.psxj.gui.panels;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.i18n.I18n;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

public final class InputSettingsPanel extends JPanel implements SettingsSection {

    private final JCheckBox enableGamepad = new JCheckBox(I18n.tr("settings.input.enableGamepad"));
    private final JCheckBox enableRumble = new JCheckBox(I18n.tr("settings.input.enableRumble"));
    private final JSpinner deadZone = new JSpinner(new SpinnerNumberModel(18, 0, 50, 1));
    private final boolean initialEnableGamepad;
    private final boolean initialEnableRumble;
    private final int initialDeadZone;

    public InputSettingsPanel(AppConfig config) {
        setLayout(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets.set(6, 6, 6, 6);

        enableGamepad.setSelected(config.input().enableGamepad());
        add(enableGamepad, gbc);

        gbc.gridy++;
        enableRumble.setSelected(config.input().enableRumble());
        add(enableRumble, gbc);

        gbc.gridy++;
        add(new JLabel(I18n.tr("settings.input.deadZone")), gbc);
        gbc.gridx = 1;
        deadZone.setValue(config.input().deadZonePercent());
        add(deadZone, gbc);

        initialEnableGamepad = config.input().enableGamepad();
        initialEnableRumble = config.input().enableRumble();
        initialDeadZone = config.input().deadZonePercent();
    }

    @Override
    public void apply(AppConfig config) {
        config.input().setEnableGamepad(enableGamepad.isSelected());
        config.input().setEnableRumble(enableRumble.isSelected());
        config.input().setDeadZonePercent((int) deadZone.getValue());
    }

    @Override
    public boolean isModified() {
        return enableGamepad.isSelected() != initialEnableGamepad
            || enableRumble.isSelected() != initialEnableRumble
            || ((Integer) deadZone.getValue()) != initialDeadZone;
    }

    @Override
    public boolean requiresRestart() {
        return false;
    }
}
