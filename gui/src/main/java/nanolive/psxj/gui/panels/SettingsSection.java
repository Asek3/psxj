package nanolive.psxj.gui.panels;

import nanolive.psxj.config.AppConfig;

public interface SettingsSection {

    void apply(AppConfig config);

    boolean isModified();

    boolean requiresRestart();
}
