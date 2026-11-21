package com.storagewizard.ui;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public final class I18n {
    private static final String BUNDLE_BASE = "i18n.messages";
    private static Locale currentLocale = Locale.ENGLISH;
    private static ResourceBundle bundle = loadBundle(currentLocale);

    private I18n() {}

    public static void setLocale(Locale locale) {
        currentLocale = (locale == null) ? Locale.ENGLISH : locale;
        bundle = loadBundle(currentLocale);
    }

    public static Locale getLocale() {
        return currentLocale;
    }

    /** Devuelve el texto traducido para la clave dada. */
    public static String t(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /** Devuelve el texto traducido con parámetros ({0}, {1}, ...). */
    public static String t(String key, Object... args) {
        return MessageFormat.format(t(key), args);
    }

    private static ResourceBundle loadBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle(BUNDLE_BASE, locale);
        } catch (MissingResourceException e) {
            return ResourceBundle.getBundle(BUNDLE_BASE, Locale.ENGLISH);
        }
    }
}
