package nanolive.psxj.gui;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.config.ConfigManager;
import nanolive.psxj.gui.panels.AudioSettingsPanel;
import nanolive.psxj.gui.panels.EmulationSettingsPanel;
import nanolive.psxj.gui.panels.InputSettingsPanel;
import nanolive.psxj.gui.panels.InterfaceSettingsPanel;
import nanolive.psxj.gui.panels.RetroAchievementsSettingsPanel;
import nanolive.psxj.gui.panels.SettingsSection;
import nanolive.psxj.gui.panels.VideoSettingsPanel;
import nanolive.psxj.i18n.I18n;
import nanolive.psxj.retroachievements.RetroAchievementsService;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

public final class SettingsDialog extends JDialog {

    public SettingsDialog(MainFrame owner, AppConfig config, ConfigManager configManager,
                          RetroAchievementsService retroAchievements) {
        super(owner, I18n.tr("settings.title"), true);
        setLayout(new BorderLayout(0, 12));
        setPreferredSize(new Dimension(820, 600));
        ((JComponent) getContentPane()).setBorder(ModernUi.windowContentBorder());

        InterfaceSettingsPanel interfacePanel = new InterfaceSettingsPanel(config);
        VideoSettingsPanel videoPanel = new VideoSettingsPanel(config);
        AudioSettingsPanel audioPanel = new AudioSettingsPanel(config);
        EmulationSettingsPanel emulationPanel = new EmulationSettingsPanel(config);
        InputSettingsPanel inputPanel = new InputSettingsPanel(config);
        RetroAchievementsSettingsPanel achievementsPanel =
            new RetroAchievementsSettingsPanel(config, retroAchievements);
        List<SettingsSection> sections = List.of(interfacePanel, videoPanel, audioPanel,
            emulationPanel, inputPanel, achievementsPanel);

        JPanel header = new JPanel();
        header.setBorder(BorderFactory.createEmptyBorder(18, 18, 0, 18));
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(I18n.tr("settings.title"));
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 22f));
        JLabel subtitle = new JLabel(I18n.tr("settings.subtitle"));
        subtitle.setForeground(ModernUi.secondaryText());
        header.add(title);
        header.add(Box.createVerticalStrut(6));
        header.add(subtitle);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 18));
        tabs.putClientProperty("JTabbedPane.tabType", "card");
        tabs.putClientProperty("JTabbedPane.tabAreaAlignment", "leading");
        tabs.putClientProperty("JTabbedPane.showTabSeparators", false);
        tabs.addTab(I18n.tr("settings.tab.interface"), wrapSection(interfacePanel));
        tabs.addTab(I18n.tr("settings.tab.video"), wrapSection(videoPanel));
        tabs.addTab(I18n.tr("settings.tab.audio"), wrapSection(audioPanel));
        tabs.addTab(I18n.tr("settings.tab.emulation"), wrapSection(emulationPanel));
        tabs.addTab(I18n.tr("settings.tab.input"), wrapSection(inputPanel));
        tabs.addTab(I18n.tr("settings.tab.retroachievements"), wrapSection(achievementsPanel));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 18, 18, 18));
        JButton saveButton = new JButton(I18n.tr("button.save"));
        JButton cancelButton = new JButton(I18n.tr("button.cancel"));
        ModernUi.styleButton(saveButton, true);
        ModernUi.styleButton(cancelButton, false);

        saveButton.addActionListener(event -> {
            boolean modified = sections.stream().anyMatch(SettingsSection::isModified);
            boolean restartRequired = sections.stream().filter(SettingsSection::isModified).anyMatch(SettingsSection::requiresRestart);

            if (modified) {
                sections.forEach(section -> section.apply(config));
                configManager.save(config);
                retroAchievements.configurationChanged();
                owner.applyLiveEmulationSettings();
                if (interfacePanel.isThemeChanged()) {
                    owner.applyThemeSelection();
                }
                if (restartRequired) {
                    JOptionPane.showMessageDialog(
                        this,
                        I18n.tr("settings.restartRecommended"),
                        I18n.tr("settings.title"),
                        JOptionPane.INFORMATION_MESSAGE
                    );
                }
            }
            dispose();
        });

        cancelButton.addActionListener(event -> dispose());
        buttons.add(saveButton);
        buttons.add(cancelButton);

        add(header, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(owner);
        ModernUi.installPointingHands(this);
    }

    private static JComponent wrapSection(JPanel section) {
        section.setOpaque(false);
        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(false);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(14, 0, 12, 0),
            ModernUi.cardBorder(18, 18)));
        card.add(section, BorderLayout.CENTER);
        return card;
    }
}
