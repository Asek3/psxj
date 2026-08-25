package nanolive.psxj.gui.panels;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.i18n.I18n;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JCheckBox;
import javax.swing.SpinnerNumberModel;

public final class EmulationSettingsPanel extends JPanel implements SettingsSection {

    private final JSpinner overclock = new JSpinner(new SpinnerNumberModel(100, 1, null, 5));
    private final JCheckBox pauseOverlay = new JCheckBox(I18n.tr("settings.emu.pauseOverlay"));
    private final int initialOverclock;
    private final boolean initialPauseOverlay;

    public EmulationSettingsPanel(AppConfig config) {
        setLayout(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets.set(6, 6, 6, 6);
        gbc.gridx = 0;
        gbc.gridy = 0;

        add(new JLabel(I18n.tr("settings.emu.overclock")), gbc);
        gbc.gridx = 1;
        overclock.setValue(config.emulation().overclockPercent());
        add(overclock, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        pauseOverlay.setSelected(config.emulation().pauseWhenOverlayOpen());
        add(pauseOverlay, gbc);

        initialOverclock = config.emulation().overclockPercent();
        initialPauseOverlay = config.emulation().pauseWhenOverlayOpen();
    }

    @Override
    public void apply(AppConfig config) {
        config.emulation().setOverclockPercent((int) overclock.getValue());
        config.emulation().setPauseWhenOverlayOpen(pauseOverlay.isSelected());
    }

    @Override
    public boolean isModified() {
        return ((Integer) overclock.getValue()) != initialOverclock
            || pauseOverlay.isSelected() != initialPauseOverlay;
    }

    @Override
    public boolean requiresRestart() {
        return false;
    }
}
