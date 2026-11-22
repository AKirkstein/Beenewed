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
    public static final Color BG            = new Color(0xF5F7FB);
    public static final Color SURFACE       = Color.WHITE;
    public static final Color BORDER        = new Color(0xE3E8EF);
    public static final Color BORDER_STRONG = new Color(0xCBD5E1);
    public static final Color TABLE_HEAD    = new Color(0xF1F4F9);
    public static final Color SUBTLE        = new Color(0xF4F6FA);

    public static final Color TEXT          = new Color(0x0F172A);
    public static final Color TEXT_2        = new Color(0x334155);
    public static final Color MUTED         = new Color(0x64748B);
    public static final Color PLACEHOLDER   = new Color(0x94A3B8);

    public static final Color PRIMARY       = new Color(0x0B4FCF);
    public static final Color PRIMARY_HOVER = new Color(0x0A43B0);
    public static final Color PRIMARY_LIGHT = new Color(0xEAF1FE);
    public static final Color LINK          = new Color(0x1D5BD8);

    public static final Color GREEN         = new Color(0x15803D);
    public static final Color GREEN_BG      = new Color(0xE6F6EC);
    public static final Color ORANGE        = new Color(0xC2410C);
    public static final Color ORANGE_BG     = new Color(0xFFF1E2);
    public static final Color RED           = new Color(0xDC2626);
    public static final Color RED_BG        = new Color(0xFEF0F0);
    public static final Color RED_DARK      = new Color(0xB91C1C);
    public static final Color AMBER         = new Color(0xB45309);
    public static final Color AMBER_BG      = new Color(0xFFF7E6);
    public static final Color INFO_BG       = new Color(0xEEF4FF);

    public static final Color OVERLAY       = new Color(15, 23, 42, 115);

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
