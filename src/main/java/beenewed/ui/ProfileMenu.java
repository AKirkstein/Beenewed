package beenewed.ui;

import javax.accessibility.AccessibleContext;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Account popover opened from the avatar in the top-right header: shows the signed-in
 * user and session info, plus Account settings, theme toggle, data refresh, and Sign out.
 */
public final class ProfileMenu extends JPopupMenu {
    private static final int WIDTH = 280;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH);

    /** Actions the menu can trigger; implemented by the main window. */
    public record Actions(Runnable openSettings, Runnable toggleTheme, Runnable refreshData, Runnable signOut) {
    }

    public ProfileMenu(String email, Instant signedInAt, boolean dark, Actions actions) {
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                new EmptyBorder(4, 0, 4, 0)));
        setBackground(Theme.ELEVATED);

        add(buildHeader(email, signedInAt));
        addSeparator();
        add(item("Account settings", Icons.Type.GEAR, Theme.TEXT, actions.openSettings()));
        add(item(dark ? "Switch to light mode" : "Switch to dark mode",
                dark ? Icons.Type.SUN : Icons.Type.MOON, Theme.TEXT, actions.toggleTheme()));
        add(item("Refresh data", Icons.Type.REFRESH, Theme.TEXT, actions.refreshData()));
        addSeparator();
        add(item("Sign out", Icons.Type.EXTERNAL, Theme.RED, actions.signOut()));
    }

    /** Opens the menu below {@code anchor}, right-aligned with it. */
    public void showBelow(JComponent anchor) {
        Dimension size = getPreferredSize();
        int x = anchor.getWidth() - Math.max(size.width, WIDTH);
        show(anchor, x, anchor.getHeight() + 6);
        SwingUtilities.invokeLater(() -> {
            // Pre-select the first actionable item so keyboard users can arrow/Enter immediately.
            if (isVisible() && getComponentCount() > 2 && getComponent(2) instanceof MenuElement firstItem) {
                MenuElement[] path = {this, firstItem};
                MenuSelectionManager.defaultManager().setSelectedPath(path);
            }
        });
    }

    private JComponent buildHeader(String email, Instant signedInAt) {
        JComponent avatar = new JComponent() {
            { setPreferredSize(new Dimension(36, 36)); setMinimumSize(getPreferredSize()); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); UI.aa(g);
                g.setColor(Theme.PRIMARY_LIGHT);
                g.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                Icons.of(Icons.Type.USER, 20, Theme.ACCENT).paintIcon(this, g, (getWidth() - 20) / 2, (getHeight() - 20) / 2);
                g.dispose();
            }
        };

        String shownEmail = email == null || email.isBlank() ? "Signed-in account" : email;
        JLabel caption = UI.label("Signed in as", Theme.TINY, Theme.MUTED);
        JLabel emailLabel = new JLabel(shownEmail) {
            @Override public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(Math.min(d.width, WIDTH - 90), d.height);
            }
        };
        emailLabel.setFont(Theme.BODY_B);
        emailLabel.setForeground(Theme.TEXT);
        emailLabel.setToolTipText(shownEmail);

        JLabel session = UI.label(sessionText(signedInAt), Theme.TINY, Theme.GREEN);
        session.setIcon(dot(Theme.GREEN));
        session.setIconTextGap(5);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{caption, emailLabel, session}) {
            c.setAlignmentX(0f);
            text.add(c);
        }
        emailLabel.setBorder(new EmptyBorder(1, 0, 3, 0));

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(8, 12, 8, 12));
        header.add(avatar, BorderLayout.WEST);
        header.add(text, BorderLayout.CENTER);
        header.setPreferredSize(new Dimension(WIDTH, header.getPreferredSize().height));
        AccessibleContext ctx = header.getAccessibleContext();
        if (ctx != null) {
            ctx.setAccessibleName("Signed in as " + shownEmail);
        }
        return header;
    }

    static String sessionText(Instant signedInAt) {
        if (signedInAt == null) {
            return "Session active";
        }
        ZonedDateTime at = signedInAt.atZone(ZoneId.systemDefault());
        boolean today = at.toLocalDate().equals(LocalDate.now());
        return "Session active \u00B7 since " + (today ? TIME.format(at) : DAY_TIME.format(at));
    }

    private static JMenuItem item(String text, Icons.Type icon, Color color, Runnable action) {
        JMenuItem i = new JMenuItem(text, Icons.of(icon, 15, color == Theme.TEXT ? Theme.TEXT_2 : color));
        i.setFont(Theme.BODY);
        i.setForeground(color);
        i.setIconTextGap(10);
        i.setBorder(new EmptyBorder(7, 12, 7, 16));
        i.addActionListener(e -> action.run());
        return i;
    }

    private static Icon dot(Color c) {
        return new Icon() {
            public int getIconWidth() { return 7; }
            public int getIconHeight() { return 7; }
            public void paintIcon(Component comp, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create(); UI.aa(g);
                g.setColor(c);
                g.fillOval(x, y, 7, 7);
                g.dispose();
            }
        };
    }
}
