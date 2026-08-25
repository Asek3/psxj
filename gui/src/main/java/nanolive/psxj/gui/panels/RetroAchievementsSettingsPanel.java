package nanolive.psxj.gui.panels;

import nanolive.psxj.config.AppConfig;
import nanolive.psxj.i18n.I18n;
import nanolive.psxj.retroachievements.RetroAchievementsService;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.Arrays;

public final class RetroAchievementsSettingsPanel extends JPanel implements SettingsSection {

    private final RetroAchievementsService service;
    private final JCheckBox enabled = new JCheckBox(I18n.tr("settings.retroachievements.enabled"));
    private final JTextField username = new JTextField(24);
    private final JPasswordField password = new JPasswordField(24);
    private final JLabel status = new JLabel();
    private final boolean initialEnabled;
    private final String initialUsername;
    private final String initialToken;
    private String token;

    public RetroAchievementsSettingsPanel(AppConfig config, RetroAchievementsService service) {
        this.service = service;
        this.initialEnabled = config.retroAchievements().enabled();
        this.initialUsername = config.retroAchievements().username();
        this.initialToken = config.retroAchievements().token();
        this.token = initialToken;

        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.insets.set(7, 7, 7, 7);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        enabled.setSelected(initialEnabled);
        add(enabled, gbc);

        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        add(new JLabel(I18n.tr("settings.retroachievements.username")), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        username.setText(initialUsername);
        add(username, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.weightx = 0;
        add(new JLabel(I18n.tr("settings.retroachievements.password")), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        add(password, gbc);

        JButton login = new JButton(I18n.tr("settings.retroachievements.login"));
        JButton logout = new JButton(I18n.tr("settings.retroachievements.logout"));
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.add(login);
        actions.add(logout);
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        add(actions, gbc);

        gbc.gridy++;
        status.setText(initialStatus(config));
        add(status, gbc);

        gbc.gridy++;
        add(new JLabel(I18n.tr("settings.retroachievements.softcoreNote")), gbc);

        login.setEnabled(service.isAvailable());
        logout.setEnabled(service.isAvailable() && !initialUsername.isBlank());
        login.addActionListener(event -> {
            char[] secret = password.getPassword();
            login.setEnabled(false);
            status.setText(I18n.tr("settings.retroachievements.loggingIn"));
            service.login(username.getText(), secret).whenComplete((account, failure) ->
                SwingUtilities.invokeLater(() -> {
                    Arrays.fill(secret, '\0');
                    password.setText("");
                    login.setEnabled(true);
                    if (failure != null) {
                        status.setText(I18n.tr("settings.retroachievements.loginFailed",
                            rootMessage(failure)));
                        return;
                    }
                    username.setText(account.username());
                    token = account.token();
                    enabled.setSelected(true);
                    logout.setEnabled(true);
                    status.setText(I18n.tr("settings.retroachievements.loggedIn",
                        account.displayName(), account.score()));
                }));
        });
        logout.addActionListener(event -> {
            service.logout();
            token = "";
            enabled.setSelected(false);
            logout.setEnabled(false);
            status.setText(I18n.tr("settings.retroachievements.loggedOut"));
        });
    }

    @Override
    public void apply(AppConfig config) {
        config.retroAchievements().setEnabled(enabled.isSelected());
        config.retroAchievements().setUsername(username.getText());
        config.retroAchievements().setToken(token);
    }

    @Override
    public boolean isModified() {
        return enabled.isSelected() != initialEnabled
            || !username.getText().trim().equals(initialUsername)
            || !token.equals(initialToken);
    }

    @Override
    public boolean requiresRestart() {
        return false;
    }

    private String initialStatus(AppConfig config) {
        if (!service.isAvailable()) {
            return I18n.tr("retroachievements.unavailable", service.unavailableReason());
        }
        if (config.retroAchievements().hasStoredLogin()) {
            return I18n.tr("settings.retroachievements.savedLogin", initialUsername);
        }
        return I18n.tr("settings.retroachievements.notLoggedIn");
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
