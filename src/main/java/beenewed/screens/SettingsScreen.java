package beenewed.screens;

import beenewed.App;
import beenewed.model.Store;
import beenewed.ui.*;
import beenewed.screens.Common;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** S6 \u2014 Settings & Preferences backed by the local SQLite database. */
public class SettingsScreen extends JPanel {
    private static final String[] WINDOWS = {"3 Days Before", "7 Days Before", "14 Days Before", "30 Days Before"};
    private static final int[] WINDOW_DAYS = {3, 7, 14, 30};

    private final UI.Combo<String> appearance = new UI.Combo<>("Light", "Dark (unavailable)");
    private final UI.Combo<String> remind = new UI.Combo<>(WINDOWS);
    private final UI.Toggle overdue = new UI.Toggle(true);
    private final JLabel toggleLabel = UI.label("Enabled", Theme.BODY_B, Theme.TEXT);
    private final JLabel reminderHint = UI.label("", Theme.SMALL, Theme.MUTED);
    private final UI.Btn viewReminders = UI.ghost("View Reminders", Icons.Type.BELL);
    private final JLabel savedMsg = UI.label("Preferences are saved locally.", Theme.SMALL, Theme.MUTED);

    public SettingsScreen() {
        super(new BorderLayout());
        setBackground(Theme.BG);

        appearance.addActionListener(e -> { if (appearance.getSelectedIndex() == 1) appearance.setSelectedIndex(0); });
        overdue.onChange(() -> { toggleLabel.setText(overdue.isOn() ? "Enabled" : "Disabled"); dirty(); });
        remind.addActionListener(e -> dirty());
        viewReminders.addActionListener(e -> App.get().showReminders());

        // General settings
        UI.RoundPanel general = UI.card(new BorderLayout(), 16);
        general.add(sectionHead(Icons.Type.SLIDERS, "General Settings", "Configure basic application preferences."), BorderLayout.NORTH);
        UI.RoundPanel currency = new UI.RoundPanel(new BorderLayout(8, 0), 8, Theme.SUBTLE, null);
        currency.setBorder(new EmptyBorder(8, 10, 8, 10));
        JLabel php = UI.label("\u20B1 PHP", Theme.TINY.deriveFont(Font.BOLD), Theme.TEXT_2);
        php.setOpaque(true); php.setBackground(new Color(0xE6EAF1)); php.setBorder(new EmptyBorder(2, 5, 2, 5));
        currency.add(php, BorderLayout.WEST);
        currency.add(UI.label("Philippine Peso (PHP \u2014 \u20B1)", Theme.SMALL, Theme.TEXT), BorderLayout.CENTER);
        currency.add(new JLabel(Icons.of(Icons.Type.LOCK, 13, Theme.MUTED)), BorderLayout.EAST);
        JLabel fixed = UI.label("Fixed to regional runtime profile", Theme.TINY, Theme.MUTED);
        fixed.setIcon(Icons.of(Icons.Type.INFO, 12, Theme.MUTED));
        JLabel active = UI.label("Active theme: Light", Theme.TINY, Theme.MUTED);
        active.setIcon(dot(Theme.GREEN));
        general.add(UI.vstack(0,
                setting("Default Currency", "Currency used to display subscription prices and financial summaries. (Only PHP is currently supported).", UI.vstack(6, currency, fixed), false),
                setting("Appearance", "Choose the application's display appearance. Dark mode is currently unavailable.", UI.vstack(6, appearance, active), true)
        ), BorderLayout.CENTER);

        // Reminders
        UI.RoundPanel reminders = UI.card(new BorderLayout(), 16);
        reminders.add(sectionHead(Icons.Type.BELL, "Renewal Reminders", "Configure how upcoming and overdue subscription renewals are highlighted."), BorderLayout.NORTH);
        JLabel windowHint = UI.wrap("Subscriptions within this window appear in renewal reminders.", 270, Theme.SMALL, Theme.MUTED);
        reminders.add(UI.vstack(0,
                setting("Remind Before Renewal", "Choose how early an upcoming renewal should appear in your reminder list.", UI.vstack(6, remind, windowHint), false),
                setting("Show Overdue Renewals", "Continue highlighting subscriptions whose saved renewal dates have passed. These indicate scheduled dates, not recorded payment status.", UI.hrow(10, overdue, toggleLabel), true),
                setting("Renewal Reminders", "Open the reminders dialog to review approaching or overdue items.", UI.vstack(6, viewReminders, reminderHint), true)
        ), BorderLayout.CENTER);

        // Footer bar
        UI.Btn discard = UI.ghost("Discard Changes", null);
        discard.addActionListener(e -> refresh());
        UI.Btn save = UI.primary("Save Preferences", Icons.Type.SAVE);
        save.addActionListener(e -> {
            try {
                Store.get().savePrefs(WINDOW_DAYS[remind.getSelectedIndex()], overdue.isOn());
                Store.get().changed();
                savedMsg.setText("Preferences saved.");
                savedMsg.setForeground(Theme.GREEN);
            } catch (ValidationException | NotFoundException | Store.BackendAccessException error) {
                Common.showBackendError(this, error);
            }
        });
        savedMsg.setIcon(Icons.of(Icons.Type.SAVE, 13, Theme.MUTED));
        UI.RoundPanel bar = UI.card(new BorderLayout(), 14);
        bar.add(savedMsg, BorderLayout.WEST);
        bar.add(UI.hrow(10, discard, save), BorderLayout.EAST);

        JComponent header = Common.pageHeader("Settings & Preferences", "Manage application settings and renewal reminder preferences.", null);
        JPanel body = UI.vstack(16, header, general, reminders, bar);
        body.setMaximumSize(new Dimension(760, Integer.MAX_VALUE));
        JPanel center = new JPanel(null) {
            { setOpaque(false); }
            @Override public Dimension getPreferredSize() {
                Dimension d = body.getPreferredSize();
                return new Dimension(Math.min(760, getParent() == null ? 760 : getParent().getWidth()), d.height);
            }
            @Override public void doLayout() {
                int w = Math.min(760, getWidth());
                body.setBounds((getWidth() - w) / 2, 0, w, body.getPreferredSize().height);
            }
        };
        center.add(body);
        add(Common.page(center), BorderLayout.CENTER);
    }

    private static Icon dot(Color c) {
        return new Icon() {
            public void paintIcon(Component comp, Graphics g, int x, int y) { UI.aa((Graphics2D) g); g.setColor(c); g.fillOval(x, y + 2, 7, 7); }
            public int getIconWidth() { return 7; }
            public int getIconHeight() { return 11; }
        };
    }

    private void dirty() {
        savedMsg.setText("You have unsaved changes.");
        savedMsg.setForeground(Theme.AMBER);
    }

    private JComponent sectionHead(Icons.Type icon, String title, String sub) {
        JLabel t = UI.h2(title);
        t.setIcon(Icons.of(icon, 16, Theme.PRIMARY));
        t.setIconTextGap(8);
        JLabel s = UI.label(sub, Theme.SMALL, Theme.MUTED);
        s.setBorder(new EmptyBorder(0, 24, 0, 0));
        JPanel p = UI.vstack(4, t, s);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), new EmptyBorder(0, 0, 14, 0)));
        return p;
    }

    private JComponent setting(String title, String desc, JComponent control, boolean topRule) {
        JPanel row = UI.clear(new BorderLayout(30, 0));
        row.setBorder(BorderFactory.createCompoundBorder(
                topRule ? BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER) : BorderFactory.createEmptyBorder(),
                new EmptyBorder(16, 0, 16, 0)));
        row.add(UI.vstack(4, UI.label(title, Theme.BODY_B, Theme.TEXT), UI.wrap(desc, 300, Theme.SMALL, Theme.MUTED)), BorderLayout.CENTER);
        JPanel c = UI.clear(new BorderLayout());
        c.setPreferredSize(new Dimension(290, control.getPreferredSize().height));
        c.add(control, BorderLayout.NORTH);
        row.add(c, BorderLayout.EAST);
        return row;
    }

    public void refresh() {
        Store st = Store.get();
        int idx = 1;
        for (int i = 0; i < WINDOW_DAYS.length; i++) if (WINDOW_DAYS[i] == st.remindDays) idx = i;
        remind.setSelectedIndex(idx);
        overdue.setOn(st.showOverdue);
        var summary = st.dashboardSummary();
        int upcomingCount = summary.upcomingRenewals().size();
        int n = upcomingCount + (st.showOverdue ? summary.overdueRenewals().size() : 0);
        viewReminders.setText("View Reminders (" + n + ")");
        reminderHint.setText("<html>Opens reminder dialog: " + upcomingCount + " upcoming renewal" + (upcomingCount == 1 ? "" : "s") + " within " + st.remindDays + " days.</html>");
        savedMsg.setText("Preferences are saved locally.");
        savedMsg.setForeground(Theme.MUTED);
    }
}
