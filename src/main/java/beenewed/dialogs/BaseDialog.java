package beenewed.dialogs;

import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.screens.Common;
import beenewed.ui.*;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Base for all modal dialogs: rounded card with header (title, subtitle, close),
 * a scrollable body and a footer with Cancel + primary action. The main window is dimmed while open.
 */
public abstract class BaseDialog extends JDialog {
    protected final JFrame owner;
    protected final JPanel body;
    protected final UI.Btn primaryBtn;
    protected final UI.Btn cancelBtn = UI.secondary("Cancel", null);
    private final JPanel footerLeft = UI.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private Point dragFrom;

    protected BaseDialog(JFrame owner, String title, String subtitle, Icons.Type titleIcon, String primaryText, Icons.Type primaryIcon, int width) {
        this(owner, title, subtitle, titleIcon, primaryText, primaryIcon, width, false);
    }

    /** @param iconTile show the title icon in a soft blue tile left of the title (D7 style) instead of inline. */
    protected BaseDialog(JFrame owner, String title, String subtitle, Icons.Type titleIcon, String primaryText, Icons.Type primaryIcon, int width, boolean iconTile) {
        super(owner, title, true);
        this.owner = owner;
        setUndecorated(true);
        // Rounded corners need per-pixel translucency; fall back to a square window when unavailable
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            try { setBackground(new Color(0, 0, 0, 0)); } catch (Exception ignored) { }
        }

        UI.RoundPanel card = new UI.RoundPanel(new BorderLayout(), 14, Color.WHITE, Theme.BORDER_STRONG);

        // Header
        JPanel header = UI.clear(new BorderLayout(10, 0));
        header.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), new EmptyBorder(18, 22, 14, 16)));
        JLabel t = UI.label(title, Theme.H2.deriveFont(18f), Theme.TEXT);
        if (titleIcon != null && !iconTile) { t.setIcon(Icons.of(titleIcon, 18, Theme.PRIMARY)); t.setIconTextGap(8); }
        JComponent titleBlock = subtitle == null ? t : UI.vstack(4, t, UI.label(subtitle, iconTile ? Theme.SMALL : Theme.BODY, Theme.MUTED));
        if (titleIcon != null && iconTile) {
            UI.RoundPanel tile = new UI.RoundPanel(new GridBagLayout(), 10, Theme.PRIMARY_LIGHT, null);
            tile.add(new JLabel(Icons.of(titleIcon, 18, Theme.PRIMARY)));
            tile.setPreferredSize(new Dimension(36, 36));
            JPanel tw = UI.clear(new GridBagLayout()); tw.add(tile);
            JPanel row = UI.clear(new BorderLayout(12, 0));
            row.add(tw, BorderLayout.WEST);
            row.add(titleBlock, BorderLayout.CENTER);
            titleBlock = row;
        }
        header.add(titleBlock, BorderLayout.CENTER);
        UI.Btn close = UI.iconButton(Icons.Type.CLOSE, this::cancel);
        JPanel cw = UI.clear(new BorderLayout()); cw.add(close, BorderLayout.NORTH);
        header.add(cw, BorderLayout.EAST);
        MouseAdapter drag = new MouseAdapter() {
            public void mousePressed(MouseEvent e) { dragFrom = e.getLocationOnScreen(); }
            public void mouseDragged(MouseEvent e) {
                Point p = e.getLocationOnScreen();
                setLocation(getX() + p.x - dragFrom.x, getY() + p.y - dragFrom.y);
                dragFrom = p;
            }
        };
        header.addMouseListener(drag); header.addMouseMotionListener(drag);
        card.add(header, BorderLayout.NORTH);

        // Body
        body = new UI.ScrollablePage(null);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Color.WHITE);
        body.setBorder(new EmptyBorder(16, 22, 18, 22));
        JScrollPane sp = UI.scroll(body);
        sp.getViewport().setBackground(Color.WHITE);
        sp.setOpaque(false);
        card.add(sp, BorderLayout.CENTER);

        // Footer
        primaryBtn = UI.primary(primaryText, primaryIcon);
        primaryBtn.addActionListener(e -> {
            try {
                if (save()) {
                    Store.get().changed();
                    close();
                } else {
                    refit();
                }
            } catch (ValidationException | NotFoundException | Store.BackendAccessException error) {
                Common.showBackendError(this, error);
                refit();
            }
        });
        cancelBtn.addActionListener(e -> cancel());
        JPanel footer = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); UI.aa(g);
                g.setColor(new Color(0xF8FAFC));
                g.clip(new RoundRectangle2D.Double(1, -20, getWidth() - 2, getHeight() + 19, 14, 14));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.dispose();
                g0.setColor(Theme.BORDER);
                g0.fillRect(0, 0, getWidth(), 1);
            }
        };
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(14, 22, 14, 22));
        footer.add(footerLeft, BorderLayout.WEST);
        footer.add(UI.hrow(10, cancelBtn, primaryBtn), BorderLayout.EAST);
        card.add(footer, BorderLayout.SOUTH);

        setContentPane(card);
        getRootPane().setDefaultButton(primaryBtn);
        getRootPane().registerKeyboardAction(e -> cancel(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setPreferredWidth(width);
    }

    private int width;
    private void setPreferredWidth(int w) { width = w; }

    /** Validate and persist. Return false to keep the dialog open. */
    protected abstract boolean save();

    protected void cancel() { close(); }

    protected void close() {
        owner.getGlassPane().setVisible(false);
        dispose();
    }

    /** Adds a red "Delete" link to the footer (used in edit mode). */
    protected void addDelete(String what, Runnable action) {
        UI.Btn del = UI.ghost("Delete", Icons.Type.TRASH);
        del.setForeground(Theme.RED);
        del.setIcon(Icons.of(Icons.Type.TRASH, 15, Theme.RED));
        del.addActionListener(e -> {
            int ok = JOptionPane.showConfirmDialog(this, "Delete this " + what + "? This cannot be undone.", "Delete " + what,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok == JOptionPane.OK_OPTION) {
                try {
                    action.run();
                    Store.get().changed();
                    close();
                } catch (ValidationException | NotFoundException | Store.BackendAccessException error) {
                    Common.showBackendError(this, error);
                }
            }
        });
        footerLeft.add(del);
    }

    /** Re-fits the dialog height to its content (e.g. after error messages appear). */
    protected void refit() {
        int max = owner.getHeight() - 60;
        for (int i = 0; i < 5; i++) {
            validate();
            int h = Math.min(getContentPane().getPreferredSize().height + 2, max);
            if (h == getHeight()) break;
            int cy = getY() + getHeight() / 2;
            setBounds(getX(), Math.max(owner.getY() + 20, cy - h / 2), width, h);
        }
    }

    public void open() {
        pack();
        // Lay out at the final width first so wrapped text reports its real height, then size to fit.
        int max = owner.getHeight() - 60;
        setSize(width, Math.min(getPreferredSize().height, max));
        for (int i = 0; i < 5; i++) {
            validate();
            int h = Math.min(getContentPane().getPreferredSize().height + 2, max);
            if (h == getHeight()) break;
            setSize(width, h);
        }
        setLocationRelativeTo(owner);
        owner.getGlassPane().setVisible(true);
        setVisible(true);
    }

    // ------------------------------------------------------------------ form helpers
    protected void place(Component c) {
        if (c instanceof JComponent jc) jc.setAlignmentX(0f);
        body.add(c);
    }
    protected void gap(int h) { body.add(Box.createVerticalStrut(h)); }

    /** Label row: "Name *" or "Name (Optional)", with optional right-aligned hint. */
    protected static JComponent fieldLabel(String text, boolean required, String optionalNote, String rightHint) {
        String html = "<html><b>" + text + "</b>" + (required ? " <span style='color:#DC2626'>*</span>" : "")
                + (optionalNote != null ? " <span style='color:#64748B'>" + optionalNote + "</span>" : "") + "</html>";
        JLabel l = UI.label(html, Theme.BODY, Theme.TEXT);
        if (rightHint == null) return l;
        return UI.split(l, UI.label(rightHint, Theme.SMALL, Theme.MUTED));
    }

    /** A labelled form field with (wrapping) helper text that can switch to an error message. */
    protected static class Field extends JPanel {
        public final JComponent input;
        private final JTextArea helper = new JTextArea();
        private final JLabel errIcon = new JLabel(Icons.of(Icons.Type.ALERT, 13, Theme.RED));
        private final JPanel helperRow = UI.clear(new BorderLayout(5, 0));
        private final String helperText;
        public Field(String label, boolean required, String optional, JComponent input, String helperText) {
            this(fieldLabel(label, required, optional, null), input, helperText);
        }
        public Field(JComponent labelRow, JComponent input, String helperText) {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            this.input = input; this.helperText = helperText;
            helper.setFont(Theme.SMALL);
            helper.setForeground(Theme.MUTED);
            helper.setLineWrap(true);
            helper.setWrapStyleWord(true);
            helper.setEditable(false);
            helper.setFocusable(false);
            helper.setOpaque(false);
            helper.setBorder(null);
            helper.setText(helperText == null ? "" : helperText);
            errIcon.setVerticalAlignment(SwingConstants.TOP);
            errIcon.setVisible(false);
            helperRow.add(errIcon, BorderLayout.WEST);
            helperRow.add(helper, BorderLayout.CENTER);
            for (JComponent c : new JComponent[]{labelRow, input, helperRow}) c.setAlignmentX(0f);
            add(labelRow);
            add(Box.createVerticalStrut(7));
            add(input);
            add(Box.createVerticalStrut(5));
            add(helperRow);
            helperRow.setVisible(helperText != null && !helperText.isBlank());
        }
        public void error(String msg) {
            UI.setError(input, msg != null);
            if (msg != null) {
                helper.setText(msg);
                helper.setForeground(Theme.RED);
                errIcon.setVisible(true);
                helperRow.setVisible(true);
            } else {
                helper.setText(helperText == null ? "" : helperText);
                helper.setForeground(Theme.MUTED);
                errIcon.setVisible(false);
                helperRow.setVisible(helperText != null && !helperText.isBlank());
            }
            revalidate();
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    /** Two fields side by side. */
    protected static JComponent pair(JComponent a, JComponent b) {
        JPanel p = UI.clear(new GridLayout(1, 2, 14, 0));
        p.add(a); p.add(b);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /** Read-only "linked subscription" strip shown at the top of the sharing/usage dialogs. */
    protected static JComponent linkedContext(Subscription s, boolean showStatus) {
        UI.RoundPanel p = new UI.RoundPanel(new BorderLayout(10, 0), 10, Theme.SUBTLE, null);
        p.setBorder(new EmptyBorder(10, 12, 10, 12));
        p.add(UI.iconBox(s.icon, 28, 15), BorderLayout.WEST);
        JComponent nameRow = showStatus ? UI.hrow(6, UI.label(s.name, Theme.BODY_B, Theme.TEXT), Common.statusBadge(s.status))
                : UI.label("<html><span style='color:#64748B'>Subscription:</span> <b>" + s.name + "</b></html>", Theme.BODY, Theme.TEXT);
        p.add(UI.vstack(2, nameRow, UI.label(showStatus ? "Category: " + s.category : s.category, Theme.SMALL, Theme.MUTED)), BorderLayout.CENTER);
        UI.Pill linked = new UI.Pill("Linked", Theme.TEXT_2, Color.WHITE, null, 6);
        linked.setIcon(Icons.of(Icons.Type.LOCK, 11, Theme.MUTED));
        linked.setBorder(new EmptyBorder(3, 6, 3, 8));
        JPanel lw = UI.clear(new GridBagLayout()); lw.add(linked);
        p.add(lw, BorderLayout.EAST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    protected static JComponent infoNote(String html, boolean blue) {
        JComponent n = UI.note(Icons.Type.INFO, blue ? Theme.INFO_BG : Theme.SUBTLE, blue ? Theme.PRIMARY : Theme.MUTED, null, html, 320);
        n.setMaximumSize(new Dimension(Integer.MAX_VALUE, n.getPreferredSize().height));
        return n;
    }

    /** Parses a positive money amount; returns null if invalid. */
    protected static Double parseAmount(String s) {
        try {
            double v = Double.parseDouble(s.replace(",", "").replace("\u20B1", "").trim());
            return v > 0 ? Math.round(v * 100) / 100.0 : null;
        } catch (NumberFormatException e) { return null; }
    }
}
