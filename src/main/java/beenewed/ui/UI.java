package beenewed.ui;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/** Reusable Swing building blocks styled after the Beenewed Figma design. */
public final class UI {
    private UI() {}

    public static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    // ===================================================================
    // Labels & layout helpers
    // ===================================================================
    public static JLabel label(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(f);
        l.setForeground(c);
        return l;
    }
    public static JLabel title(String t)    { return label(t, Theme.H1, Theme.TEXT); }
    public static JLabel h2(String t)       { return label(t, Theme.H2, Theme.TEXT); }
    public static JLabel muted(String t)    { return label(t, Theme.SMALL, Theme.MUTED); }
    public static JLabel body(String t)     { return label(t, Theme.BODY, Theme.TEXT_2); }
    public static JLabel caps(String t)     { return label(t.toUpperCase(), Theme.CAPS, Theme.MUTED); }

    /** Multi-line label that wraps at the given pixel width. */
    public static JLabel wrap(String html, int width, Font f, Color c) {
        JLabel l = label("<html><div style='width:" + width + "px'>" + html + "</div></html>", f, c);
        return l;
    }

    public static JPanel clear(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setOpaque(false);
        return p;
    }

    /** Vertical stack (BoxLayout Y) with left-aligned children. */
    public static JPanel vstack(int gap, Component... cs) {
        JPanel p = clear(null);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        boolean first = true;
        for (Component c : cs) {
            if (!first && gap > 0) p.add(Box.createVerticalStrut(gap));
            if (c instanceof JComponent jc) jc.setAlignmentX(0f);
            p.add(c);
            first = false;
        }
        return p;
    }

    /** Horizontal row (BoxLayout X), children vertically centred. */
    public static JPanel hrow(int gap, Component... cs) {
        JPanel p = clear(null);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        boolean first = true;
        for (Component c : cs) {
            if (!first && gap > 0) p.add(Box.createHorizontalStrut(gap));
            if (c instanceof JComponent jc) jc.setAlignmentY(0.5f);
            p.add(c);
            first = false;
        }
        return p;
    }

    /** A row where `left` stretches and `right` hugs the right edge. */
    public static JPanel split(Component left, Component right) {
        JPanel p = clear(new BorderLayout(12, 0));
        if (left != null) p.add(left, BorderLayout.CENTER);
        if (right != null) {
            JPanel r = clear(new GridBagLayout());
            r.add(right);
            p.add(r, BorderLayout.EAST);
        }
        return p;
    }

    public static Component hglue() { return Box.createHorizontalGlue(); }

    // ===================================================================
    // Rounded surfaces
    // ===================================================================
    public static class RoundPanel extends JPanel {
        private final int radius;
        private Color fill, stroke;
        public RoundPanel(LayoutManager lm, int radius, Color fill, Color stroke) {
            super(lm);
            this.radius = radius; this.fill = fill; this.stroke = stroke;
            setOpaque(false);
        }
        public void setColors(Color fill, Color stroke) { this.fill = fill; this.stroke = stroke; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            if (fill != null) {
                g.setColor(fill);
                g.fill(new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, radius, radius));
            }
            if (stroke != null) {
                g.setColor(stroke);
                g.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 2, getHeight() - 2, radius, radius));
            }
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** White card with a light border, like every section in the design. */
    public static RoundPanel card(LayoutManager lm, int pad) {
        RoundPanel p = new RoundPanel(lm, 12, Theme.SURFACE, Theme.BORDER);
        p.setBorder(new EmptyBorder(pad, pad, pad, pad));
        return p;
    }

    /** Small rounded square holding an icon (subscription avatars). */
    public static JComponent iconBox(Icons.Type t, int box, int icon) {
        RoundPanel p = new RoundPanel(new GridBagLayout(), box >= 40 ? 12 : 8, Theme.SUBTLE, Theme.BORDER);
        p.add(new JLabel(Icons.of(t, icon, Theme.TEXT_2)));
        Dimension d = new Dimension(box, box);
        p.setPreferredSize(d); p.setMinimumSize(d); p.setMaximumSize(d);
        return p;
    }

    /**
     * Creates the Beenewed brand logo component at {@code size x size} using {@code /icons/logo.png}
     * smoothly scaled with aspect ratio preserved, or falls back cleanly to the vector badge if missing.
     */
    public static JComponent brandLogo(int size, int fallbackCornerRadius, int fallbackIconSize) {
        Dimension dim = new Dimension(size, size);
        ImageIcon icon = Icons.brandLogoIcon(size);
        if (icon != null) {
            JLabel lbl = new JLabel(icon, SwingConstants.CENTER);
            lbl.setOpaque(false);
            lbl.setPreferredSize(dim);
            lbl.setMinimumSize(dim);
            lbl.setMaximumSize(dim);
            return lbl;
        }
        RoundPanel fallback = new RoundPanel(new GridBagLayout(), fallbackCornerRadius, Theme.PRIMARY, null);
        fallback.add(new JLabel(Icons.of(Icons.Type.WALLET, fallbackIconSize, Theme.ON_PRIMARY)));
        fallback.setPreferredSize(dim);
        fallback.setMinimumSize(dim);
        fallback.setMaximumSize(dim);
        return fallback;
    }

    /**
     * Applies the Beenewed brand icon to a {@link Window} (JFrame or JDialog) if available,
     * safely doing nothing when {@code window} or the logo image resource is missing.
     */
    public static void applyWindowIcon(Window window) {
        if (window == null) return;
        java.util.List<Image> icons = Icons.brandWindowIcons();
        if (!icons.isEmpty()) {
            window.setIconImages(icons);
        } else {
            Image single = Icons.brandWindowIcon();
            if (single != null) {
                window.setIconImage(single);
            }
        }
    }

    /**
     * Paints a true circular avatar centered inside the component's bounds using
     * antialiasing and {@link Ellipse2D.Double} circular clipping.
     * Supports either a center-cropped {@link Image} or a centered fallback {@link Icon},
     * with an optional circular border ring.
     */
    public static void paintCircularAvatar(
            Graphics2D g0,
            Component owner,
            int width,
            int height,
            Image photo,
            Icon fallbackIcon,
            Color bg,
            Color borderColor,
            float borderWidth) {
        int diameter = Math.min(width, height);
        if (diameter <= 0) return;

        double pad = borderWidth > 0f ? borderWidth / 2.0 : 0.5;
        double d = Math.max(1.0, diameter - pad * 2.0);
        double x = (width - d) / 2.0;
        double y = (height - d) / 2.0;

        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            Ellipse2D.Double circle = new Ellipse2D.Double(x, y, d, d);

            Graphics2D clipped = (Graphics2D) g.create();
            try {
                clipped.clip(circle);
                if (bg != null) {
                    clipped.setColor(bg);
                    clipped.fill(circle);
                }
                if (photo != null) {
                    int imgW = photo.getWidth(owner);
                    int imgH = photo.getHeight(owner);
                    if (imgW > 0 && imgH > 0) {
                        double scale = Math.max(d / imgW, d / imgH);
                        double drawW = imgW * scale;
                        double drawH = imgH * scale;
                        double drawX = x + (d - drawW) / 2.0;
                        double drawY = y + (d - drawH) / 2.0;
                        clipped.drawImage(
                                photo,
                                (int) Math.floor(drawX),
                                (int) Math.floor(drawY),
                                (int) Math.ceil(drawW),
                                (int) Math.ceil(drawH),
                                owner);
                    }
                } else if (fallbackIcon != null) {
                    int ix = (int) Math.round(x + (d - fallbackIcon.getIconWidth()) / 2.0);
                    int iy = (int) Math.round(y + (d - fallbackIcon.getIconHeight()) / 2.0);
                    fallbackIcon.paintIcon(owner, clipped, ix, iy);
                }
            } finally {
                clipped.dispose();
            }

            if (borderColor != null && borderWidth > 0f) {
                g.setColor(borderColor);
                g.setStroke(new BasicStroke(borderWidth));
                g.draw(circle);
            }
        } finally {
            g.dispose();
        }
    }

    /** Coloured info / note panel with an icon, optional bold title and body. */
    public static JComponent note(Icons.Type icon, Color bg, Color iconColor, String titleHtml, String bodyHtml, int width) {
        RoundPanel p = new RoundPanel(new BorderLayout(10, 0), 10, bg, null);
        p.setBorder(new EmptyBorder(12, 14, 12, 14));
        JLabel ic = new JLabel(Icons.of(icon, 16, iconColor));
        ic.setVerticalAlignment(SwingConstants.TOP);
        ic.setBorder(new EmptyBorder(1, 0, 0, 0));
        p.add(ic, BorderLayout.WEST);
        JPanel text = vstack(3);
        if (titleHtml != null) text.add(label(titleHtml, Theme.BODY_B, Theme.TEXT));
        if (bodyHtml != null) {
            if (titleHtml != null) text.add(Box.createVerticalStrut(3));
            text.add(wrap(bodyHtml, width, Theme.SMALL, Theme.TEXT_2));
        }
        p.add(text, BorderLayout.CENTER);
        return p;
    }

    // ===================================================================
    // Buttons
    // ===================================================================
    public enum Kind { PRIMARY, SECONDARY, GHOST, LINK, DANGER, SOFT }

    public static class Btn extends JButton {
        private Kind kind;
        private boolean hover;
        public void setKind(Kind k) {
            kind = k;
            setForeground(k == Kind.PRIMARY ? Theme.ON_PRIMARY : k == Kind.DANGER ? Color.WHITE : k == Kind.LINK ? Theme.LINK : k == Kind.SOFT ? Theme.ACCENT : Theme.TEXT);
            repaint();
        }
        public Btn(String text, Icons.Type icon, Kind kind) {
            super(text);
            this.kind = kind;
            setFont(kind == Kind.LINK ? Theme.SMALL_B : Theme.BODY_B);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setForeground(switch (kind) {
                case PRIMARY -> Theme.ON_PRIMARY;
                case DANGER -> Color.WHITE;
                case LINK -> Theme.LINK;
                case SOFT -> Theme.ACCENT;
                default -> Theme.TEXT;
            });
            if (icon != null) {
                setIcon(Icons.of(icon, 15, getForeground()));
                setIconTextGap(6);
            }
            setBorder(kind == Kind.LINK ? new EmptyBorder(2, 2, 2, 2) : new EmptyBorder(8, 14, 8, 14));
            if (kind == Kind.SECONDARY) {
                putClientProperty("JButton.buttonType", "roundRect");
            }
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
            });
        }
        public Btn small() {
            setFont(Theme.SMALL_B);
            setBorder(kind == Kind.LINK ? new EmptyBorder(2, 2, 2, 2) : new EmptyBorder(5, 10, 5, 10));
            return this;
        }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            RoundRectangle2D r = new RoundRectangle2D.Double(0.5, 0.5, w - 1.5, h - 1.5, 8, 8);
            boolean on = isEnabled();
            switch (kind) {
                case PRIMARY -> { g.setColor(!on ? Theme.BORDER_STRONG : hover ? Theme.PRIMARY_HOVER : Theme.PRIMARY); g.fill(r); }
                case DANGER  -> { g.setColor(hover ? Theme.RED_HOVER : Theme.RED_DARK); g.fill(r); }
                case SECONDARY -> {
                    boolean pressed = on && getModel().isPressed();
                    Color fill = !on ? Theme.SUBTLE
                            : pressed ? Theme.PRIMARY_LIGHT
                            : hover ? (Theme.isDark() ? Theme.BORDER : Theme.SUBTLE)
                            : (Theme.isDark() ? Theme.ELEVATED : Theme.SURFACE);
                    g.setColor(fill);
                    g.fill(r);
                    g.setColor(pressed || hover ? Theme.PRIMARY : Theme.BORDER_STRONG);
                    g.draw(r);
                }
                case GHOST -> { if (hover) { g.setColor(Theme.SUBTLE); g.fill(r); } }
                case SOFT  -> { g.setColor(hover ? Theme.SOFT_HOVER : Theme.PRIMARY_LIGHT); g.fill(r); }
                case LINK  -> { }
            }
            g.dispose();
            if (kind == Kind.LINK && hover) {
                setText("<html><u>" + stripHtml(getText()) + "</u></html>");
            } else if (kind == Kind.LINK && getText().startsWith("<html><u>")) {
                setText(stripHtml(getText()));
            }
            super.paintComponent(g0);
        }
        private static String stripHtml(String s) { return s.replaceAll("<[^>]+>", ""); }
    }

    public static Btn primary(String t, Icons.Type i)   { return new Btn(t, i, Kind.PRIMARY); }
    public static Btn secondary(String t, Icons.Type i) { return new Btn(t, i, Kind.SECONDARY); }
    public static Btn ghost(String t, Icons.Type i)     { return new Btn(t, i, Kind.GHOST); }
    public static Btn link(String t)                    { return new Btn(t, null, Kind.LINK); }
    public static Btn soft(String t, Icons.Type i)      { return new Btn(t, i, Kind.SOFT); }

    /**
     * Compact boxed action button for table "Actions" columns (e.g., "View", "View Details", "Edit", "Mark Paid").
     * Renders with a visible outlined button box, consistent padding, and theme-aware hover/pressed colors.
     */
    public static Btn tableAction(String text) {
        Btn b = new Btn(text, null, Kind.SECONDARY);
        b.setFont(Theme.SMALL_B);
        b.setForeground(Theme.TEXT);
        b.setMargin(new Insets(4, 12, 4, 12));
        b.setBorder(new EmptyBorder(4, 12, 4, 12));
        b.putClientProperty("JButton.buttonType", "roundRect");
        return b;
    }

    public static Btn iconButton(Icons.Type t, Runnable action) {
        Btn b = new Btn(null, null, Kind.GHOST);
        b.setIcon(Icons.of(t, 16, Theme.MUTED));
        b.setBorder(new EmptyBorder(5, 5, 5, 5));
        b.addActionListener(e -> action.run());
        return b;
    }

    // ===================================================================
    // Badges & chips
    // ===================================================================
    public static class Pill extends JLabel {
        private final Color bg; private final Color dot; private final int radius;
        public Pill(String text, Color fg, Color bg, Color dot, int radius) {
            super(text);
            this.bg = bg; this.dot = dot; this.radius = radius;
            setFont(Theme.TINY.deriveFont(Font.BOLD));
            setForeground(fg);
            setBorder(new EmptyBorder(3, dot != null ? 18 : 8, 3, 8));
        }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(bg);
            int r = Math.min(radius, getHeight());
            g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), r, r));
            if (dot != null) {
                g.setColor(dot);
                g.fillOval(8, getHeight() / 2 - 3, 6, 6);
            }
            g.dispose();
            super.paintComponent(g0);
        }
    }

    public static Pill badge(String text, Color fg, Color bg) { return new Pill(text, fg, bg, fg, 999); }
    public static Pill chip(String text) {
        Pill p = new Pill(text, Theme.TEXT_2, Theme.SUBTLE, null, 8);
        p.setFont(Theme.TINY);
        return p;
    }
    public static Pill countBadge(String text) { return new Pill(text, Theme.TEXT_2, Theme.SUBTLE, null, 999); }

    // ===================================================================
    // Inputs
    // ===================================================================
    /** Rounded field border that turns blue on focus and red when the component has client property "error". */
    public static class FieldBorder extends AbstractBorder {
        private final Insets pad;
        public FieldBorder(int v, int h) { pad = new Insets(v, h, v, h); }
        @Override public Insets getBorderInsets(Component c) { return pad; }
        @Override public Insets getBorderInsets(Component c, Insets i) { i.set(pad.top, pad.left, pad.bottom, pad.right); return i; }
        @Override public void paintBorder(Component c, Graphics g0, int x, int y, int w, int h) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            boolean err = c instanceof JComponent jc && Boolean.TRUE.equals(jc.getClientProperty("error"));
            boolean focus = focusWithin(c);
            g.setColor(err ? Theme.RED : focus ? Theme.PRIMARY : Theme.BORDER_STRONG);
            g.setStroke(new BasicStroke(focus || err ? 1.4f : 1f));
            g.draw(new RoundRectangle2D.Double(x + 0.7, y + 0.7, w - 1.6, h - 1.6, 8, 8));
            g.dispose();
        }
    }

    static boolean focusWithin(Component c) {
        Component f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return f != null && (f == c || SwingUtilities.isDescendingFrom(f, c));
    }

    /** Paints a rounded background (white, or light red in error state) under a field. */
    static void paintFieldBg(JComponent c, Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        boolean err = Boolean.TRUE.equals(c.getClientProperty("error"));
        boolean readOnly = !c.isEnabled() || (c instanceof javax.swing.text.JTextComponent tc && !tc.isEditable());
        g.setColor(err ? Theme.RED_BG : readOnly ? Theme.SUBTLE : Theme.FIELD_BG);
        g.fill(new RoundRectangle2D.Double(0, 0, c.getWidth() - 1, c.getHeight() - 1, 8, 8));
        g.dispose();
    }

    public static void setError(JComponent c, boolean on) {
        c.putClientProperty("error", on ? Boolean.TRUE : null);
        c.repaint();
    }

    public static class TextField extends JTextField {
        private String placeholder;
        public TextField(String placeholder) {
            this.placeholder = placeholder;
            setFont(Theme.BODY);
            setForeground(Theme.TEXT);
            setCaretColor(Theme.TEXT);
            setOpaque(false);
            setBorder(new FieldBorder(9, 12));
            addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { repaint(); }
                public void focusLost(FocusEvent e) { repaint(); }
            });
        }
        public void setPlaceholder(String p) { placeholder = p; repaint(); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override protected void paintComponent(Graphics g) {
            if (getBorder() instanceof FieldBorder) paintFieldBg(this, g);
            super.paintComponent(g);
            if (getText().isEmpty() && placeholder != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                aa(g2);
                g2.setColor(Theme.PLACEHOLDER);
                g2.setFont(getFont());
                Insets in = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(placeholder, in.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        }
    }

    public static class PasswordField extends JPasswordField {
        private String placeholder;
        public PasswordField(String placeholder) {
            this.placeholder = placeholder;
            setFont(Theme.BODY);
            setForeground(Theme.TEXT);
            setCaretColor(Theme.TEXT);
            setOpaque(false);
            setBorder(new FieldBorder(9, 12));
            addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { repaint(); }
                public void focusLost(FocusEvent e) { repaint(); }
            });
        }
        public void setPlaceholder(String p) { placeholder = p; repaint(); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override protected void paintComponent(Graphics g) {
            if (getBorder() instanceof FieldBorder) paintFieldBg(this, g);
            super.paintComponent(g);
            if (getPassword().length == 0 && placeholder != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                aa(g2);
                g2.setColor(Theme.PLACEHOLDER);
                g2.setFont(getFont());
                Insets in = getInsets();
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(placeholder, in.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        }
    }

    public static class TextArea extends JTextArea {
        private final String placeholder;
        public TextArea(String placeholder, int rows) {
            super(rows, 20);
            this.placeholder = placeholder;
            setFont(Theme.BODY);
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(false);
            setForeground(Theme.TEXT);
            setBorder(new FieldBorder(9, 12));
            addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { repaint(); }
                public void focusLost(FocusEvent e) { repaint(); }
            });
            // Tab moves focus instead of inserting a tab character
            setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
            setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        }
        @Override protected void paintComponent(Graphics g) {
            paintFieldBg(this, g);
            super.paintComponent(g);
            if (getText().isEmpty() && placeholder != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                aa(g2);
                g2.setColor(Theme.PLACEHOLDER);
                g2.setFont(getFont());
                Insets in = getInsets();
                g2.drawString(placeholder, in.left, in.top + g2.getFontMetrics().getAscent());
                g2.dispose();
            }
        }
    }

    /** Text field with a fixed prefix segment, e.g. the peso sign in amount fields. */
    public static class PrefixField extends JPanel {
        public final TextField field;
        private final JLabel prefix;
        public PrefixField(String prefixText, String placeholder) {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(new FieldBorder(0, 0));
            prefix = label(prefixText, Theme.BODY_B, Theme.MUTED);
            prefix.setBorder(new EmptyBorder(0, 12, 0, 10));
            field = new TextField(placeholder);
            field.setBorder(new EmptyBorder(9, 4, 9, 12));
            if ("\u20B1".equals(prefixText)) {
                InputFilters.installDecimalFilter(field);
            }
            field.addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { PrefixField.this.repaint(); }
                public void focusLost(FocusEvent e) { PrefixField.this.repaint(); }
            });
            add(prefix, BorderLayout.WEST);
            add(field, BorderLayout.CENTER);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override protected void paintComponent(Graphics g) {
            paintFieldBg(this, g);
            boolean err = Boolean.TRUE.equals(getClientProperty("error"));
            prefix.setForeground(err ? Theme.RED : Theme.MUTED);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(err ? Theme.ERR_DIVIDER : Theme.BORDER);
            int x = prefix.getWidth();
            g2.drawLine(x, 1, x, getHeight() - 2);
            g2.dispose();
            super.paintComponent(g);
        }
        public String getText() { return field.getText(); }
        public void setText(String t) { field.setText(t); }
        @Override public void setEnabled(boolean b) {
            super.setEnabled(b);
            field.setEnabled(b);
            field.setEditable(b);
            field.setFocusable(b);
            field.setDisabledTextColor(Theme.MUTED);
        }
    }

    /** Combo box styled like the design's dropdowns. */
    public static class Combo<T> extends JComboBox<T> {
        private static final int ARROW_WIDTH = 22;

        @SafeVarargs
        public Combo(T... items) {
            super(items);
            setFont(Theme.BODY);
            setForeground(Theme.TEXT);
            setBackground(Theme.FIELD_BG);
            setOpaque(false);
            putClientProperty("JComponent.roundRect", true);
            setBorder(new FieldBorder(4, 6));
            setUI(new BasicComboBoxUI() {
                @Override protected void installDefaults() {
                    super.installDefaults();
                    // Avoid stacking FlatLaf's default ComboBox.padding on top of FieldBorder + renderer padding.
                    padding = new Insets(0, 0, 0, 0);
                }
                @Override protected JButton createArrowButton() {
                    JButton b = new JButton(Icons.of(Icons.Type.CHEVRON_DOWN, 14, Theme.MUTED));
                    b.setBorder(new EmptyBorder(0, 2, 0, 4));
                    b.setContentAreaFilled(false);
                    b.setFocusPainted(false);
                    b.setOpaque(false);
                    return b;
                }
                @Override protected LayoutManager createLayoutManager() {
                    return new ComboBoxLayoutManager() {
                        @Override public void layoutContainer(Container parent) {
                            JComboBox<?> cb = (JComboBox<?>) parent;
                            int w = cb.getWidth();
                            int h = cb.getHeight();
                            Insets in = cb.getInsets();
                            int innerH = Math.max(0, h - (in.top + in.bottom));
                            int arrowW = Math.min(ARROW_WIDTH, Math.max(18, innerH));
                            if (arrowButton != null) {
                                arrowButton.setBounds(w - in.right - arrowW, in.top, arrowW, innerH);
                            }
                            if (editor != null) {
                                editor.setBounds(rectangleForCurrentValue());
                            }
                        }
                    };
                }
                @Override protected Rectangle rectangleForCurrentValue() {
                    int w = comboBox.getWidth();
                    int h = comboBox.getHeight();
                    Insets in = getInsets();
                    int arrowW = arrowButton != null ? arrowButton.getWidth() : ARROW_WIDTH;
                    return new Rectangle(
                            in.left,
                            in.top,
                            Math.max(0, w - in.left - in.right - arrowW),
                            Math.max(0, h - in.top - in.bottom));
                }
                @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) { }
                @Override public void paint(Graphics g, JComponent c) {
                    paintFieldBg(c, g);
                    super.paint(g, c);
                }
            });
            setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                    JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                    l.setFont(Theme.BODY);
                    if (index == -1) {
                        l.setBorder(new EmptyBorder(4, 6, 4, 2));
                        l.setOpaque(false);
                        l.setForeground(Combo.this.isEnabled() ? Theme.TEXT : Theme.MUTED);
                    } else {
                        l.setBorder(new EmptyBorder(6, 10, 6, 10));
                        l.setBackground(sel ? Theme.PRIMARY_LIGHT : Theme.ELEVATED);
                        l.setForeground(sel ? Theme.ACCENT : Theme.TEXT);
                    }
                    return l;
                }
            });
            addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { repaint(); }
                public void focusLost(FocusEvent e) { repaint(); }
            });
        }
        @SuppressWarnings("unchecked")
        public T selected() { return (T) getSelectedItem(); }

        /**
         * Ensures the combo box is at least {@code minWidth} wide (and wide enough for its
         * longest item) with a consistent minimum height so filter labels never truncate.
         */
        public Combo<T> withMinWidth(int minWidth) {
            Dimension natural = super.getPreferredSize();
            int w = Math.max(minWidth, natural.width + 6);
            int h = Math.max(36, natural.height);
            Dimension sized = new Dimension(w, h);
            setPreferredSize(sized);
            setMinimumSize(sized);
            return this;
        }

        @Override public Dimension getMinimumSize() {
            return isPreferredSizeSet() ? getPreferredSize() : super.getMinimumSize();
        }
        @Override public Dimension getMaximumSize() {
            return isPreferredSizeSet() ? getPreferredSize() : new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** iOS-style toggle switch (used for "Show Overdue Renewals"). */
    public static class Toggle extends JComponent {
        private boolean on;
        private final java.util.List<Runnable> listeners = new java.util.ArrayList<>();
        public Toggle(boolean on) {
            this.on = on;
            setPreferredSize(new Dimension(36, 20));
            setMaximumSize(getPreferredSize());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) { setOn(!Toggle.this.on); requestFocusInWindow(); }
            });
            addKeyListener(new KeyAdapter() {
                public void keyPressed(KeyEvent e) { if (e.getKeyCode() == KeyEvent.VK_SPACE) setOn(!Toggle.this.on); }
            });
        }
        public boolean isOn() { return on; }
        public void setOn(boolean v) { on = v; repaint(); listeners.forEach(Runnable::run); }
        public void onChange(Runnable r) { listeners.add(r); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            g.setColor(on ? Theme.PRIMARY : Theme.BORDER_STRONG);
            g.fillRoundRect(0, 0, w, h, h, h);
            g.setColor(Color.WHITE);
            int d = h - 4;
            g.fillOval(on ? w - d - 2 : 2, 2, d, d);
            g.dispose();
        }
    }

    /**
     * Compact Light/Dark switch for the header: a pill with a sun (Light) and a moon (Dark).
     * The highlighted knob sits behind the active mode; clicking (or Space/Enter) flips it.
     */
    public static class ThemeSwitch extends JComponent {
        private final boolean dark;
        private boolean hover;
        public ThemeSwitch(boolean dark, Consumer<Boolean> onChange) {
            this.dark = dark;
            setPreferredSize(new Dimension(64, 32));
            setMaximumSize(getPreferredSize());
            setMinimumSize(getPreferredSize());
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(dark ? "Dark mode is on \u2014 switch to Light mode" : "Light mode is on \u2014 switch to Dark mode");
            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) { SwingUtilities.invokeLater(() -> onChange.accept(!ThemeSwitch.this.dark)); }
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
            addKeyListener(new KeyAdapter() {
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER)
                        SwingUtilities.invokeLater(() -> onChange.accept(!ThemeSwitch.this.dark));
                }
            });
            addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { repaint(); }
                public void focusLost(FocusEvent e) { repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            int w = getWidth(), h = getHeight();
            g.setColor(hover ? Theme.TABLE_HEAD : Theme.SUBTLE);
            g.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            g.setColor(isFocusOwner() ? Theme.PRIMARY : Theme.BORDER_STRONG);
            g.drawRoundRect(0, 0, w - 1, h - 1, h, h);
            int d = h - 6, knobX = dark ? w - d - 3 : 3;
            g.setColor(Theme.PRIMARY);
            g.fillOval(knobX, 3, d, d);
            int is = 15;
            Icon sun = Icons.of(Icons.Type.SUN, is, dark ? Theme.MUTED : Theme.ON_PRIMARY);
            Icon moon = Icons.of(Icons.Type.MOON, is, dark ? Theme.ON_PRIMARY : Theme.MUTED);
            sun.paintIcon(this, g, 3 + (d - is) / 2, (h - is) / 2);
            moon.paintIcon(this, g, w - d - 3 + (d - is) / 2, (h - is) / 2);
            g.dispose();
        }
    }

    // ===================================================================
    // Tabs
    // ===================================================================
    /** Horizontal tab strip with a blue underline under the active tab. */
    public static class Tabs extends JPanel {
        private final java.util.List<JLabel> items = new java.util.ArrayList<>();
        private final java.util.List<Icons.Type> icons = new java.util.ArrayList<>();
        private final java.util.List<Pill> counts = new java.util.ArrayList<>();
        private int selected = 0;
        private Consumer<Integer> listener = i -> {};
        private final boolean bottomRule;

        public Tabs(boolean bottomRule) {
            super(null);
            this.bottomRule = bottomRule;
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setOpaque(false);
        }
        public Tabs add(String text, Icons.Type icon, String count) {
            int idx = items.size();
            JLabel l = label(text, Theme.BODY, Theme.MUTED);
            l.setBorder(new EmptyBorder(10, 2, 11, 2));
            l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            if (icon != null) { l.setIcon(Icons.of(icon, 15, Theme.MUTED)); l.setIconTextGap(6); }
            JPanel holder = clear(null);
            holder.setLayout(new BoxLayout(holder, BoxLayout.X_AXIS));
            holder.add(l);
            Pill p = null;
            if (count != null) {
                p = countBadge(count);
                holder.add(Box.createHorizontalStrut(8));
                holder.add(p);
            }
            MouseAdapter ma = new MouseAdapter() { public void mouseClicked(MouseEvent e) { select(idx, true); } };
            l.addMouseListener(ma);
            holder.addMouseListener(ma);
            if (!items.isEmpty()) add(Box.createHorizontalStrut(22));
            add(holder);
            items.add(l); icons.add(icon); counts.add(p);
            restyle();
            return this;
        }
        public void setCount(int idx, String c) { if (counts.get(idx) != null) { counts.get(idx).setText(c); } }
        public void onSelect(Consumer<Integer> l) { listener = l; }
        public int getSelected() { return selected; }
        public void select(int i, boolean fire) {
            selected = i; restyle(); repaint();
            if (fire) listener.accept(i);
        }
        private void restyle() {
            for (int i = 0; i < items.size(); i++) {
                boolean s = i == selected;
                JLabel l = items.get(i);
                l.setForeground(s ? Theme.ACCENT : Theme.MUTED);
                l.setFont(s ? Theme.BODY_B : Theme.BODY);
                if (icons.get(i) != null) l.setIcon(Icons.of(icons.get(i), 15, s ? Theme.ACCENT : Theme.MUTED));
            }
        }
        @Override protected void paintChildren(Graphics g0) {
            super.paintChildren(g0);
            Graphics2D g = (Graphics2D) g0.create();
            if (bottomRule) { g.setColor(Theme.BORDER); g.fillRect(0, getHeight() - 1, getWidth(), 1); }
            if (selected < items.size()) {
                Component holder = items.get(selected).getParent();
                g.setColor(Theme.PRIMARY);
                g.fillRect(holder.getX(), getHeight() - 2, holder.getWidth(), 2);
            }
            g.dispose();
        }
    }

    /** Two-option segmented control (Reports "Report Basis"). */
    public static class Segmented extends RoundPanel {
        private final JLabel[] opts;
        private int selected;
        private Consumer<Integer> listener = i -> {};
        public Segmented(String... labels) {
            super(new GridLayout(1, labels.length, 4, 0), 10, Theme.SUBTLE, null);
            setBorder(new EmptyBorder(3, 3, 3, 3));
            opts = new JLabel[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                JLabel l = new JLabel("<html><div style='text-align:center'>" + labels[i] + "</div></html>", SwingConstants.CENTER) {
                    @Override protected void paintComponent(Graphics g0) {
                        if (idx == selected) {
                            Graphics2D g = (Graphics2D) g0.create(); aa(g);
                            g.setColor(Theme.isDark() ? Theme.ELEVATED : Theme.SURFACE); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                            g.setColor(Theme.BORDER); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                            g.dispose();
                        }
                        super.paintComponent(g0);
                    }
                };
                l.setFont(Theme.SMALL);
                l.setBorder(new EmptyBorder(6, 14, 6, 14));
                l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                l.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { select(idx); listener.accept(idx); } });
                opts[i] = l;
                add(l);
            }
            select(0);
        }
        public void onSelect(Consumer<Integer> l) { listener = l; }
        public void select(int i) {
            selected = i;
            for (int k = 0; k < opts.length; k++) {
                opts[k].setForeground(k == i ? Theme.ACCENT : Theme.TEXT_2);
                opts[k].setFont(k == i ? Theme.SMALL_B : Theme.SMALL);
            }
            repaint();
        }
        public int getSelected() { return selected; }
    }

    // ===================================================================
    // Scroll panes
    // ===================================================================
    public static JScrollPane scroll(Component view) {
        JScrollPane sp = new JScrollPane(view);
        sp.setBorder(null);
        sp.setViewportBorder(null);
        sp.getViewport().setBackground(Theme.BG);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        sp.getVerticalScrollBar().setUI(new ThinScrollBarUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        sp.getHorizontalScrollBar().setUI(new ThinScrollBarUI());
        sp.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 10));
        return sp;
    }

    public static class ThinScrollBarUI extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() { thumbColor = Theme.SCROLL_THUMB; trackColor = new Color(0, 0, 0, 0); }
        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }
        private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }
        @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create(); aa(g);
            g.setColor(thumbColor);
            g.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6);
            g.dispose();
        }
    }

    /** Panel that tracks the viewport width so content reflows instead of scrolling sideways. */
    public static class ScrollablePage extends JPanel implements Scrollable {
        public ScrollablePage(LayoutManager lm) { super(lm); setBackground(Theme.BG); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 18; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 40; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
    }

    public static Border pad(int t, int l, int b, int r) { return new EmptyBorder(t, l, b, r); }
}
