package nanolive.psxj.gui.panels;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.config.RendererType;
import nanolive.psxj.i18n.I18n;
import nanolive.psxj.platform.render.RendererCapabilities;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

public final class VideoSettingsPanel extends JPanel implements SettingsSection {

    private final JComboBox<RendererType> renderer = new JComboBox<>(RendererCapabilities.availableRenderers());
    private final RendererType initialRenderer;

    public VideoSettingsPanel(AppConfig config) {
        setLayout(new GridBagLayout());
        SettingsUi.humanizeCombo(renderer);
        var gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets.set(6, 6, 6, 6);
        gbc.gridx = 0;
        gbc.gridy = 0;

        add(new JLabel(I18n.tr("settings.video.renderer")), gbc);
        gbc.gridx = 1;
        RendererType configuredRenderer = config.video().renderer();
        renderer.setSelectedItem(RendererCapabilities.isAvailable(configuredRenderer)
            ? configuredRenderer
            : RendererType.AWT);
        add(renderer, gbc);

        initialRenderer = config.video().renderer();
    }

    @Override
    public void apply(AppConfig config) {
        config.video().setRenderer((RendererType) renderer.getSelectedItem());
    }

    @Override
    public boolean isModified() {
        return renderer.getSelectedItem() != initialRenderer;
    }

    @Override
    public boolean requiresRestart() {
        return false;
    }
}
