package nanolive.psxj.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public final class I18n {

    private static ResourceBundle bundle = ResourceBundle.getBundle("i18n.messages", Locale.getDefault());

    private I18n() {
    }

    public static synchronized void initialize(Locale locale) {
        bundle = ResourceBundle.getBundle("i18n.messages", locale);
    }

    public static synchronized String tr(String key, Object... arguments) {
        var value = bundle.containsKey(key) ? bundle.getString(key) : '!' + key + '!';
        return arguments.length == 0 ? value : MessageFormat.format(value, arguments);
    }
}
