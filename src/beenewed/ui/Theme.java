package beenewed.ui;

import java.awt.*;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Colours, fonts and formatters shared by the whole app (matches the Figma design). */
public final class Theme {
    private Theme() {}

    // ---- Colours -------------------------------------------------------
    // Not final: Theme.apply(dark) swaps the whole palette, then App rebuilds its UI.
    public static Color BG, SURFACE, ELEVATED, FIELD_BG, BORDER, BORDER_STRONG, TABLE_HEAD, SUBTLE, ROW_HOVER, FOOTER_BG;
    public static Color TEXT, TEXT_2, MUTED, PLACEHOLDER;
    /** PRIMARY = accent fill (buttons, toggles, indicators). ACCENT/LINK = accent used for text & icons (darker in light mode for contrast). */
    public static Color PRIMARY, PRIMARY_HOVER, ON_PRIMARY, PRIMARY_LIGHT, SOFT_HOVER, ACCENT, LINK, WARM;
    public static Color GREEN, GREEN_BG, ORANGE, ORANGE_BG, RED, RED_BG, RED_DARK, RED_HOVER, RED_BORDER, AMBER, AMBER_BG;
    public static Color INFO_BG, INFO_BORDER, AMBER_PANEL, ERR_DIVIDER, OVERLAY, SELECTION, SCROLL_THUMB;

    private static boolean dark;
    public static boolean isDark() { return dark; }

    static { apply(false); }

    /** Switches every colour to the light or dark palette. */
    public static void apply(boolean darkMode) {
        dark = darkMode;
        // Shared brand accents
        PRIMARY       = c(0xF4B315);
        PRIMARY_HOVER = c(0xE59312);
        ON_PRIMARY    = c(0x1A141A);
        WARM          = c(0xD3AF85);
        if (!darkMode) {
            BG = c(0xF6F8FC); SURFACE = c(0xFFFFFF); ELEVATED = c(0xFFFFFF); FIELD_BG = c(0xFFFFFF);
            BORDER = c(0xEAE3DB); BORDER_STRONG = c(0xD9C6AE); TABLE_HEAD = c(0xF8F4EE); SUBTLE = c(0xF6F1EA);
            ROW_HOVER = c(0xFCF9F4); FOOTER_BG = c(0xFAF7F3);
            TEXT = c(0x1A141A); TEXT_2 = c(0x423738); MUTED = c(0x6E6062); PLACEHOLDER = c(0x9C8F90);
            PRIMARY_LIGHT = c(0xFDF2D3); SOFT_HOVER = c(0xFBE6AE); ACCENT = c(0x9A5B00); LINK = c(0x9A5B00);
            GREEN = c(0x15803D); GREEN_BG = c(0xE6F6EC); ORANGE = c(0xC2410C); ORANGE_BG = c(0xFFF1E2);
            RED = c(0xDC2626); RED_BG = c(0xFEF0F0); RED_DARK = c(0xB91C1C); RED_HOVER = c(0x991B1B); RED_BORDER = c(0xF8C4C4);
            AMBER = c(0xB45309); AMBER_BG = c(0xFFF7E6);
            INFO_BG = c(0xFDF6E7); INFO_BORDER = c(0xF0E0BE); AMBER_PANEL = c(0xFFFAF0); ERR_DIVIDER = c(0xF5B5B5);
            OVERLAY = new Color(26, 20, 26, 115); SELECTION = c(0xF9DE9A); SCROLL_THUMB = c(0xD9C6AE);
        } else {
            BG = c(0x1A141A); SURFACE = c(0x423738); ELEVATED = c(0x514344); FIELD_BG = c(0x362D2F);
            BORDER = c(0x5B4B4C); BORDER_STRONG = c(0x726061); TABLE_HEAD = c(0x514344); SUBTLE = c(0x4B3F40);
            ROW_HOVER = c(0x4B3E3F); FOOTER_BG = c(0x483B3C);
            TEXT = c(0xF6F8FC); TEXT_2 = c(0xD3AF85); MUTED = c(0xB5A193); PLACEHOLDER = c(0x8F7D77);
            PRIMARY_LIGHT = c(0x5E4B27); SOFT_HOVER = c(0x6E5629); ACCENT = c(0xF4B315); LINK = c(0xF4B315);
            GREEN = c(0x7EE2A8); GREEN_BG = c(0x23402F); ORANGE = c(0xFDBA74); ORANGE_BG = c(0x4D3322);
            RED = c(0xF89A9A); RED_BG = c(0x4D2A2B); RED_DARK = c(0xDC2626); RED_HOVER = c(0xB91C1C); RED_BORDER = c(0x7D3B3C);
            AMBER = c(0xFCD27A); AMBER_BG = c(0x4D3D20);
            INFO_BG = c(0x4E4233); INFO_BORDER = c(0x6E5B3F); AMBER_PANEL = c(0x4B3D27); ERR_DIVIDER = c(0x7D3B3C);
            OVERLAY = new Color(0, 0, 0, 150); SELECTION = c(0x7A5E22); SCROLL_THUMB = c(0x726061);
        }
    }

    private static Color c(int rgb) { return new Color(rgb); }

    /** "#RRGGBB" for use inside HTML labels. */
    public static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    // ---- Saved theme preference (java.util.prefs = Swing's equivalent of localStorage) ----
    private static final java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userRoot().node("beenewed");
    /** Light is the default for first-time users. */
    public static boolean savedDark() { return PREFS.getBoolean("darkMode", false); }
    public static void saveDark(boolean d) {
        PREFS.putBoolean("darkMode", d);
        try { PREFS.flush(); } catch (Exception ignored) { }
    }

    // ---- Fonts ---------------------------------------------------------
    private static final String FAMILY = pickFamily();

    private static String pickFamily() {
        String[] wanted = {"Inter", "Segoe UI", "SF Pro Text", "Helvetica Neue", "Roboto", "Noto Sans", "DejaVu Sans"};
        String[] have = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String w : wanted) for (String h : have) if (h.equalsIgnoreCase(w)) return h;
        return Font.SANS_SERIF;
    }

    public static Font font(int style, float size) {
        return new Font(FAMILY, style, 12).deriveFont(style, size);
    }
    public static Font regular(float size) { return font(Font.PLAIN, size); }
    public static Font bold(float size)    { return font(Font.BOLD, size); }

    public static final Font H1      = bold(22f);
    public static final Font H2      = bold(16f);
    public static final Font H3      = bold(14f);
    public static final Font BODY    = regular(13f);
    public static final Font BODY_B  = bold(13f);
    public static final Font SMALL   = regular(12f);
    public static final Font SMALL_B = bold(12f);
    public static final Font TINY    = regular(11f);
    public static final Font CAPS    = bold(11f);
    public static final Font BIG_NUM = bold(24f);

    // ---- Formatting ----------------------------------------------------
    private static final DecimalFormat PESO = new DecimalFormat("#,##0.00");
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    public static final DateTimeFormatter INPUT_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH);

    public static String peso(double v) { return "\u20B1" + PESO.format(v); }
    public static String num(double v)  { return PESO.format(v); }
    public static String date(LocalDate d) { return d == null ? "\u2014" : DATE.format(d); }
}
