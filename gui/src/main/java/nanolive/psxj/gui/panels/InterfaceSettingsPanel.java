package nanolive.psxj.gui.panels;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.config.LanguageOption;
import nanolive.psxj.config.ThemeOption;
import nanolive.psxj.i18n.I18n;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

public final class InterfaceSettingsPanel extends JPanel implements SettingsSection {

    private final JComboBox<LanguageOption> language = new JComboBox<>(LanguageOption.values());
    private final JComboBox<ThemeOption> theme = new JComboBox<>(ThemeOption.values());
    private final JCheckBox confirmOnExit = new JCheckBox(I18n.tr("settings.interface.confirmOnExit"));
    private final LanguageOption initialLanguage;
    private final ThemeOption initialTheme;
    private final boolean initialConfirmOnExit;

    public InterfaceSettingsPanel(AppConfig config) {
        setLayout(new GridBagLayout());
        SettingsUi.humanizeCombo(language);
        SettingsUi.humanizeCombo(theme);
        var gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets.set(6, 6, 6, 6);
        gbc.gridx = 0;
        gbc.gridy = 0;

        add(new JLabel(I18n.tr("settings.interface.language")), gbc);
        gbc.gridx = 1;
        language.setSelectedItem(config.ui().language());
        add(language, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        add(new JLabel(I18n.tr("settings.interface.theme")), gbc);
        gbc.gridx = 1;
        theme.setSelectedItem(config.ui().theme());
        add(theme, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        confirmOnExit.setSelected(config.ui().confirmOnExit());
        add(confirmOnExit, gbc);

        initialLanguage = config.ui().language();
        initialTheme = config.ui().theme();
        initialConfirmOnExit = config.ui().confirmOnExit();
    }

    @Override
    public void apply(AppConfig config) {
        config.ui().setLanguage((LanguageOption) language.getSelectedItem());
        config.ui().setTheme((ThemeOption) theme.getSelectedItem());
        config.ui().setConfirmOnExit(confirmOnExit.isSelected());
    }

    @Override
    public boolean isModified() {
        return language.getSelectedItem() != initialLanguage
            || theme.getSelectedItem() != initialTheme
            || confirmOnExit.isSelected() != initialConfirmOnExit;
    }

    @Override
    public boolean requiresRestart() {
        return language.getSelectedItem() != initialLanguage;
    }

    public boolean isThemeChanged() {
        return theme.getSelectedItem() != initialTheme;
    }
}
