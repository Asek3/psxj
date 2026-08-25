package nanolive.psxj.config;

public final class UiConfig {

    private LanguageOption language = LanguageOption.SYSTEM;
    private ThemeOption theme = ThemeOption.SYSTEM;
    private boolean confirmOnExit = true;

    public LanguageOption language() {
        return language == null ? LanguageOption.SYSTEM : language;
    }

    public void setLanguage(LanguageOption language) {
        this.language = language;
    }

    public ThemeOption theme() {
        return theme == null ? ThemeOption.SYSTEM : theme;
    }

    public void setTheme(ThemeOption theme) {
        this.theme = theme;
    }

    public boolean confirmOnExit() {
        return confirmOnExit;
    }

    public void setConfirmOnExit(boolean confirmOnExit) {
        this.confirmOnExit = confirmOnExit;
    }

}
