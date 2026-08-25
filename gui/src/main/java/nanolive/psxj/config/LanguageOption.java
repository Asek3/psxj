package nanolive.psxj.config;

import java.util.Locale;

public enum LanguageOption {
    SYSTEM(Locale.getDefault()),
    ENGLISH(Locale.ENGLISH),
    RUSSIAN(Locale.of("ru"));

    private final Locale locale;

    LanguageOption(Locale locale) {
        this.locale = locale;
    }

    public Locale toLocale() {
        return locale;
    }
}
