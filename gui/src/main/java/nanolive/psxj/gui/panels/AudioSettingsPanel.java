package nanolive.psxj.gui.panels;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.config.AudioBackendType;
import nanolive.psxj.i18n.I18n;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

public final class AudioSettingsPanel extends JPanel implements SettingsSection {

    private final JComboBox<AudioBackendType> backend = new JComboBox<>(AudioBackendType.values());
    private final JSpinner latency = new JSpinner(new SpinnerNumberModel(80, 20, 250, 4));
    private final JSpinner volume = new JSpinner(new SpinnerNumberModel(100, 0, 200, 5));
    private final AudioBackendType initialBackend;
    private final int initialLatency;
    private final int initialVolume;

    public AudioSettingsPanel(AppConfig config) {
        setLayout(new GridBagLayout());
        SettingsUi.humanizeCombo(backend);
        var gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets.set(6, 6, 6, 6);
        gbc.gridx = 0;
        gbc.gridy = 0;

        add(new JLabel(I18n.tr("settings.audio.backend")), gbc);
        gbc.gridx = 1;
        backend.setSelectedItem(config.audio().backend());
        add(backend, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        add(new JLabel(I18n.tr("settings.audio.latency")), gbc);
        gbc.gridx = 1;
        latency.setValue(config.audio().latencyMs());
        add(latency, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        add(new JLabel(I18n.tr("settings.audio.volume")), gbc);
        gbc.gridx = 1;
        volume.setValue(config.audio().volumePercent());
        add(volume, gbc);

        initialBackend = config.audio().backend();
        initialLatency = config.audio().latencyMs();
        initialVolume = config.audio().volumePercent();
    }

    @Override
    public void apply(AppConfig config) {
        config.audio().setBackend((AudioBackendType) backend.getSelectedItem());
        config.audio().setLatencyMs((int) latency.getValue());
        config.audio().setVolumePercent((int) volume.getValue());
    }

    @Override
    public boolean isModified() {
        return backend.getSelectedItem() != initialBackend
            || ((Integer) latency.getValue()) != initialLatency
            || ((Integer) volume.getValue()) != initialVolume;
    }

    @Override
    public boolean requiresRestart() {
        return false;
    }
}
