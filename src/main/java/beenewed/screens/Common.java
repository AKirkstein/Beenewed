package beenewed.screens;

import beenewed.model.AnalyticsEngine;
import beenewed.model.CsvExporter;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;

/** Small pieces reused across screens. */
public final class Common {
    private Common() {}

    public static void showBackendError(Component parent, RuntimeException error) {
        Throwable cause = error instanceof Store.BackendAccessException && error.getCause() != null
                ? error.getCause()
                : error;
        JOptionPane.showMessageDialog(
                parent,
                cause.getMessage() == null ? "The operation could not be completed." : cause.getMessage(),
                "Unable to complete operation",
                JOptionPane.ERROR_MESSAGE);
    }

    public static UI.Pill statusBadge(Status s) {
        return switch (s) {
            case ACTIVE -> UI.badge("Active", Theme.GREEN, Theme.GREEN_BG);
            case PAUSED -> UI.badge("Paused", Theme.AMBER, Theme.AMBER_BG);
            case CANCELLED -> UI.badge("Cancelled", Theme.RED, Theme.RED_BG);
        };
    }

    /** "In 3 days" style badge: orange inside the reminder window, red when overdue (if enabled), neutral otherwise. */
    public static UI.Pill dueBadge(LocalDate d) {
        Store st = Store.get();
        return dueBadge(d, st.remindDays, st.showOverdue);
    }

    /** Evaluates due badge styling for explicit reminder lead days and overdue-highlight preference. */
    public static UI.Pill dueBadge(LocalDate d, int reminderWindowDays, boolean showOverdue) {
        long days = Store.daysUntil(d);
        if (days < 0) {
            if (!showOverdue) {
                String past = (-days) + (days == -1 ? " day ago" : " days ago");
                return new UI.Pill(past, Theme.MUTED, Theme.SUBTLE, Theme.PLACEHOLDER, 999);
            }
            return UI.badge("Overdue " + (-days) + (days == -1 ? " day" : " days"), Theme.RED, Theme.RED_BG);
        }
        if (days == 0) return UI.badge("Due today", Theme.ORANGE, Theme.ORANGE_BG);
        String t = "In " + days + (days == 1 ? " day" : " days");
        if (days <= reminderWindowDays) return UI.badge(t, Theme.ORANGE, Theme.ORANGE_BG);
        return new UI.Pill(t, Theme.MUTED, Theme.SUBTLE, Theme.PLACEHOLDER, 999);
    }

    /** Status-aware due badge: non-active subscriptions are never flagged as overdue. */
    public static UI.Pill dueBadge(Subscription s) {
        if (s.status == Status.PAUSED) {
            return new UI.Pill("On hold", Theme.MUTED, Theme.SUBTLE, Theme.PLACEHOLDER, 999);
        }
        if (s.status == Status.CANCELLED) {
            return new UI.Pill("Inactive", Theme.MUTED, Theme.SUBTLE, Theme.PLACEHOLDER, 999);
        }
        return dueBadge(s.nextRenewal);
    }

    /** Utilization pill: red for No Usage, amber for Low Usage, green for Active Use. */
    public static UI.Pill utilizationBadge(AnalyticsEngine.UtilizationLevel level) {
        return switch (level) {
            case UNUSED -> UI.badge(level.label, Theme.RED, Theme.RED_BG);
            case LOW_USAGE -> UI.badge(level.label, Theme.AMBER, Theme.AMBER_BG);
            case HEALTHY -> UI.badge(level.label, Theme.GREEN, Theme.GREEN_BG);
        };
    }

    /** FlatLaf-friendly rounded horizontal progress bar for category and forecast charts. */
    public static JComponent barTrack(double ratio, Color fillColor) {
        double clamped = Math.max(0.0, Math.min(1.0, ratio));
        JComponent bar = new JComponent() {
            { setPreferredSize(new Dimension(120, 8)); setMinimumSize(new Dimension(40, 8)); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                UI.aa(g);
                int w = getWidth(), h = getHeight();
                g.setColor(Theme.SUBTLE);
                g.fillRoundRect(0, 0, w, h, h, h);
                int fw = (int) Math.round(w * clamped);
                if (fw > 0) {
                    g.setColor(fillColor);
                    g.fillRoundRect(0, 0, Math.max(fw, h), h, h, h);
                }
                g.dispose();
            }
        };
        bar.setOpaque(false);
        return bar;
    }

    /** Opens a Save dialog and writes UTF-8 CSV content to the selected file. */
    public static void exportCsvWithDialog(Component parent, String defaultFileName, String csvContent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export CSV");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        chooser.setSelectedFile(new File(defaultFileName));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            target = new File(target.getParentFile(), target.getName() + ".csv");
        }
        try {
            CsvExporter.writeUtf8(target.toPath(), csvContent);
            JOptionPane.showMessageDialog(
                    parent,
                    "Exported CSV to:\n" + target.getAbsolutePath(),
                    "Export Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException error) {
            JOptionPane.showMessageDialog(
                    parent,
                    "Unable to write CSV file: " + error.getMessage(),
                    "Export Failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Icon box + bold subscription name, used in table cells. */
    public static JComponent subCell(Subscription s, boolean bold) {
        JLabel name = UI.label(s.name, bold ? Theme.BODY_B : Theme.BODY, Theme.TEXT);
        return UI.hrow(10, UI.iconBox(s.icon, 26, 15), name);
    }

    /** Page title + subtitle on the left, optional action on the right. */
    public static JComponent pageHeader(String title, String subtitle, JComponent action) {
        JPanel left = UI.vstack(4, UI.title(title), UI.label(subtitle, Theme.BODY, Theme.MUTED));
        JPanel p = UI.split(left, action);
        p.setBorder(new EmptyBorder(0, 0, 18, 0));
        return p;
    }

    /** Card heading: optional icon + bold title, with a muted subtitle below. */
    public static JComponent cardHeading(Icons.Type icon, String title, String subtitle) {
        JLabel t = UI.h2(title);
        if (icon != null) { t.setIcon(Icons.of(icon, 17, Theme.ACCENT)); t.setIconTextGap(8); }
        if (subtitle == null) return t;
        return UI.vstack(4, t, UI.label(subtitle, Theme.SMALL, Theme.MUTED));
    }

    /** Heading with a soft icon tile at the left (Sharing tab style). */
    public static JComponent tileHeading(Icons.Type icon, String title, String subtitle) {
        UI.RoundPanel tile = new UI.RoundPanel(new GridBagLayout(), 8, Theme.SUBTLE, null);
        tile.add(new JLabel(Icons.of(icon, 16, Theme.TEXT_2)));
        tile.setPreferredSize(new Dimension(30, 30));
        tile.setMaximumSize(new Dimension(30, 30));
        JPanel text = UI.vstack(2, UI.h2(title), UI.label(subtitle, Theme.SMALL, Theme.MUTED));
        JPanel row = UI.clear(new BorderLayout(10, 0));
        JPanel tw = UI.clear(new BorderLayout());
        tw.add(tile, BorderLayout.NORTH);
        row.add(tw, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        return row;
    }

    public static JLabel money(double v, boolean bold) {
        JLabel l = UI.label(Theme.peso(v), bold ? Theme.BODY_B : Theme.BODY, Theme.TEXT);
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        return l;
    }

    public static JLabel footerText(String t) { return UI.label(t, Theme.SMALL, Theme.MUTED); }

    /** "Label:  \u20B1123.00" footer total. */
    public static JComponent footerTotal(String label, String value, Color valueColor) {
        return UI.hrow(8, UI.label(label, Theme.SMALL, Theme.TEXT_2), UI.label(value, Theme.BODY_B, valueColor));
    }

    /** Wraps a page's content in padding and a scroll pane. */
    public static JScrollPane page(JComponent content) {
        UI.ScrollablePage p = new UI.ScrollablePage(new BorderLayout());
        p.setBorder(new EmptyBorder(24, 26, 30, 26));
        p.add(content, BorderLayout.NORTH);
        return UI.scroll(p);
    }
}
