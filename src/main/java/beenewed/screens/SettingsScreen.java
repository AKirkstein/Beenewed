package beenewed.screens;

import beenewed.App;
import beenewed.model.Store;
import beenewed.ui.*;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** S6 \u2014 Settings & Preferences (synced with Supabase user preferences). */
public class SettingsScreen extends JPanel {
    private static final String[] WINDOWS = {"3 Days Before", "7 Days Before", "14 Days Before", "30 Days Before"};
    private static final int[] WINDOW_DAYS = {3, 7, 14, 30};

    private final UI.Combo<String> appearance = new UI.Combo<>("Light", "Dark");
    private final UI.Combo<String> remind = new UI.Combo<>(WINDOWS);
    private final UI.Toggle overdue = new UI.Toggle(true);
    private final JLabel toggleLabel = UI.label("Enabled", Theme.BODY_B, Theme.TEXT);
    private final JLabel reminderHint = UI.label("", Theme.SMALL, Theme.MUTED);
    private final UI.Btn viewReminders = UI.ghost("View Reminders", Icons.Type.BELL);
    private final JLabel savedMsg = UI.label("Preferences are synced to your account.", Theme.SMALL, Theme.MUTED);
    private final JLabel accountEmailLabel = UI.label("", Theme.SMALL, Theme.TEXT);

    public SettingsScreen() {
        super(new BorderLayout());
        setBackground(Theme.BG);

        // Same global setting as the sun/moon switch in the header
        appearance.setSelectedIndex(Theme.isDark() ? 1 : 0);
        appearance.addActionListener(e -> {
            boolean dark = appearance.getSelectedIndex() == 1;
            if (dark != Theme.isDark()) SwingUtilities.invokeLater(() -> App.get().setDarkMode(dark));
        });
        overdue.onChange(() -> { toggleLabel.setText(overdue.isOn() ? "Enabled" : "Disabled"); dirty(); });
        remind.addActionListener(e -> dirty());
        viewReminders.addActionListener(e -> App.get().showReminders());

        // General settings
        UI.RoundPanel general = UI.card(new BorderLayout(), 16);
        general.add(sectionHead(Icons.Type.SLIDERS, "General Settings", "Configure basic application preferences."), BorderLayout.NORTH);
        UI.RoundPanel currency = new UI.RoundPanel(new BorderLayout(8, 0), 8, Theme.SUBTLE, null);
        currency.setBorder(new EmptyBorder(8, 10, 8, 10));
        JLabel php = UI.label("\u20B1 PHP", Theme.TINY.deriveFont(Font.BOLD), Theme.TEXT_2);
        php.setOpaque(true); php.setBackground(Theme.TABLE_HEAD); php.setBorder(new EmptyBorder(2, 5, 2, 5));
        currency.add(php, BorderLayout.WEST);
        currency.add(UI.label("Philippine Peso (PHP \u2014 \u20B1)", Theme.SMALL, Theme.TEXT), BorderLayout.CENTER);
        currency.add(new JLabel(Icons.of(Icons.Type.LOCK, 13, Theme.MUTED)), BorderLayout.EAST);
        JLabel fixed = UI.label("Fixed to regional runtime profile", Theme.TINY, Theme.MUTED);
        fixed.setIcon(Icons.of(Icons.Type.INFO, 12, Theme.MUTED));
        JLabel active = UI.label("Active theme: " + (Theme.isDark() ? "Dark" : "Light"), Theme.TINY, Theme.MUTED);
        active.setIcon(dot(Theme.GREEN));
        general.add(UI.vstack(0,
                setting("Default Currency", "Currency used to display subscription prices and financial summaries. (Only PHP is currently supported).", UI.vstack(6, currency, fixed), false),
                setting("Appearance", "Choose the application's display appearance. You can also switch anytime with the sun/moon toggle in the header.", UI.vstack(6, appearance, active), true)
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

        // Account
        UI.RoundPanel account = UI.card(new BorderLayout(), 16);
        account.add(sectionHead(Icons.Type.USER, "Account", "Manage your active Supabase session."), BorderLayout.NORTH);
        UI.RoundPanel emailBadge = new UI.RoundPanel(new BorderLayout(8, 0), 8, Theme.SUBTLE, null);
        emailBadge.setBorder(new EmptyBorder(8, 10, 8, 10));
        emailBadge.add(new JLabel(Icons.of(Icons.Type.USER, 14, Theme.ACCENT)), BorderLayout.WEST);
        emailBadge.add(accountEmailLabel, BorderLayout.CENTER);
        UI.Btn logOutBtn = new UI.Btn("Log Out", Icons.Type.EXTERNAL, UI.Kind.DANGER);
        logOutBtn.addActionListener(e -> App.get().logout());
        JPanel logOutRow = UI.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        logOutRow.add(logOutBtn);
        account.add(UI.vstack(0,
                setting("Signed-In Account", "Your subscriptions, payments, and preferences are synced to this Supabase account.", UI.vstack(8, emailBadge, logOutRow), false)
        ), BorderLayout.CENTER);

        // Footer bar
        UI.Btn discard = UI.ghost("Discard Changes", null);
        discard.addActionListener(e -> refresh());
        UI.Btn save = UI.primary("Save Preferences", Icons.Type.SAVE);
        save.addActionListener(e -> {
            int days = WINDOW_DAYS[remind.getSelectedIndex()];
            boolean showOverdueVal = overdue.isOn();
            save.setEnabled(false);
            discard.setEnabled(false);
            save.setText("Saving...");
            Window win = SwingUtilities.getWindowAncestor(this);
            if (win != null) win.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    Store.get().savePrefs(days, showOverdueVal);
                    return null;
                }

                @Override
                protected void done() {
                    if (win != null) win.setCursor(Cursor.getDefaultCursor());
                    save.setEnabled(true);
                    discard.setEnabled(true);
                    save.setText("Save Preferences");
                    try {
                        get();
                        Store.get().changed();
                        savedMsg.setText("Preferences saved.");
                        savedMsg.setForeground(Theme.GREEN);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } catch (java.util.concurrent.ExecutionException error) {
                        Throwable cause = error.getCause();
                        if (cause instanceof ValidationException || cause instanceof NotFoundException
                                || cause instanceof Store.BackendAccessException) {
                            Common.showBackendError(SettingsScreen.this, (RuntimeException) cause);
                        } else {
                            JOptionPane.showMessageDialog(
                                    SettingsScreen.this,
                                    cause == null ? "Unable to save preferences." : cause.getMessage(),
                                    "Save failed",
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }.execute();
        });
        savedMsg.setIcon(Icons.of(Icons.Type.SAVE, 13, Theme.MUTED));
        UI.RoundPanel bar = UI.card(new BorderLayout(), 14);
        bar.add(savedMsg, BorderLayout.WEST);
        bar.add(UI.hrow(10, discard, save), BorderLayout.EAST);

        JComponent header = Common.pageHeader("Settings & Preferences", "Manage application settings and renewal reminder preferences.", null);
        JPanel body = UI.vstack(16, header, general, reminders, bar, account);
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
        t.setIcon(Icons.of(icon, 16, Theme.ACCENT));
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
        int n = st.upcomingReminders().size() + (st.showOverdue ? st.overdue().size() : 0);
        viewReminders.setText("View Reminders (" + n + ")");
        reminderHint.setText("<html>Opens reminder dialog: " + st.upcomingReminders().size() + " upcoming renewal" + (st.upcomingReminders().size() == 1 ? "" : "s") + " within " + st.remindDays + " days.</html>");
        savedMsg.setText("Preferences are synced to your account.");
        savedMsg.setForeground(Theme.MUTED);
        if (App.get() != null) {
            accountEmailLabel.setText(App.get().getCurrentUserEmail());
        }
    }
}
