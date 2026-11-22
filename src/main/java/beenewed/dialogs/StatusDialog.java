package beenewed.dialogs;

import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.screens.Common;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * D7 / D7-B / D7-C — Change Subscription Status.
 * The "Review Status Change" panel follows the Figma frames:
 *   Paused    -> amber panel,  "Confirm Change"
 *   Active    -> blue panel with Renewal Date Reminder, "Confirm Reactivation"
 *   Cancelled -> red panel,    "Confirm Cancellation"
 */
public class StatusDialog extends BaseDialog {
    private static final Color AMBER_PANEL  = new Color(0xFFFAF0);
    private static final Color AMBER_TITLE  = new Color(0xB45309);
    private static final Color BLUE_PANEL   = new Color(0xF5F8FD);
    private static final Color BLUE_BORDER  = new Color(0xDCE5F2);
    private static final Color RED_PANEL    = new Color(0xFEF2F2);
    private static final Color RED_BORDER   = new Color(0xF8C4C4);

    private final Subscription sub;
    private final UI.Combo<Status> status;
    private final JPanel review = UI.clear(new BorderLayout());

    public StatusDialog(JFrame owner, Subscription sub, Status preset) {
        super(owner, "Change Subscription Status", "Update the operational status of this subscription.",
                Icons.Type.SWAP, "Confirm Change", Icons.Type.CHECK, 480, true);
        this.sub = sub;
        cancelBtn.setKind(UI.Kind.GHOST);
        cancelBtn.setFont(Theme.SMALL);
        primaryBtn.setFont(Theme.SMALL_B);

        // Subscription context card
        UI.RoundPanel ctx = new UI.RoundPanel(new BorderLayout(10, 0), 8, Theme.SUBTLE, null);
        ctx.setBorder(new EmptyBorder(10, 10, 10, 12));
        UI.RoundPanel box = new UI.RoundPanel(new GridBagLayout(), 6, Color.WHITE, Theme.BORDER);
        box.add(new JLabel(Icons.of(sub.icon, 15, Theme.TEXT)));
        box.setPreferredSize(new Dimension(26, 26));
        JPanel bw = UI.clear(new GridBagLayout()); bw.add(box);
        ctx.add(bw, BorderLayout.WEST);
        ctx.add(UI.vstack(2,
                UI.label("<html><span style='color:#64748B'>Subscription:</span> <b>" + sub.name + "</b></html>", Theme.SMALL, Theme.TEXT),
                UI.label("<html><b>" + sub.category + "</b> <span style='color:#64748B'>• " + Theme.peso(sub.price) + " " + sub.perLabel() + "</span></html>", Theme.TINY, Theme.TEXT)),
                BorderLayout.CENTER);
        JPanel sw = UI.clear(new GridBagLayout()); sw.add(Common.statusBadge(sub.status));
        ctx.add(sw, BorderLayout.EAST);
        ctx.setMaximumSize(new Dimension(Integer.MAX_VALUE, ctx.getPreferredSize().height));
        place(ctx); gap(16);

        // New status
        status = new UI.Combo<>(Status.values());
        Status initial = preset != null ? preset : sub.status == Status.ACTIVE ? Status.PAUSED : Status.ACTIVE;
        status.setSelectedItem(initial);
        place(new Field("New Status", true, null, status, "Select the new operational status for this subscription."));
        gap(14);

        place(review);
        status.addActionListener(e -> updateReview());
        updateReview();
    }

    private void updateReview() {
        Status s = status.selected();
        review.removeAll();

        if (s == sub.status) {
            review.add(panel(Theme.SUBTLE, null, Theme.MUTED, Icons.Type.INFO,
                    "This subscription is already " + s.label.toLowerCase() + ". Select a different status to continue.", null, null, null));
            primaryBtn.setEnabled(false);
            primaryBtn.setKind(UI.Kind.PRIMARY);
            primaryBtn.setText("Confirm Change");
            primaryBtn.setIcon(Icons.of(Icons.Type.CHECK, 15, Color.WHITE));
        } else {
            primaryBtn.setEnabled(true);
            switch (s) {
                case PAUSED -> {
                    review.add(panel(AMBER_PANEL, null, AMBER_TITLE, Icons.Type.INFO,
                            "Pausing this subscription excludes it from active recurring-cost estimates and upcoming renewal reminders. "
                                    + "Existing payment, usage, sharing, and price-history records will be preserved.",
                            null,
                            "This change is recorded locally and does not pause or cancel the subscription with the service provider.",
                            Theme.MUTED));
                    setPrimary("Confirm Change", Icons.Type.CHECK, UI.Kind.PRIMARY);
                }
                case CANCELLED -> {
                    review.add(panel(RED_PANEL, RED_BORDER, Theme.RED_DARK, Icons.Type.ALERT,
                            "Cancelling this subscription excludes it from active recurring-cost estimates and upcoming renewal reminders. "
                                    + "Existing payment, sharing, usage, and price-history records will be preserved.",
                            null,
                            "This change updates your local records only. You must cancel the subscription separately with the service provider.",
                            Theme.RED));
                    setPrimary("Confirm Cancellation", Icons.Type.X_CIRCLE, UI.Kind.DANGER);
                }
                default -> {
                    review.add(panel(BLUE_PANEL, BLUE_BORDER, Theme.PRIMARY, Icons.Type.INFO,
                            "Reactivating this subscription includes it in active recurring-cost estimates and eligible upcoming renewal reminders. "
                                    + "Existing payment, sharing, usage, and price-history records remain unchanged.",
                            renewalReminder(),
                            "This status change affects your local records only. It does not reactivate the subscription with the service provider.",
                            Theme.MUTED));
                    setPrimary("Confirm Reactivation", Icons.Type.CHECK, UI.Kind.PRIMARY);
                }
            }
        }
        review.revalidate(); review.repaint();
        if (isVisible()) refit();
    }

    private void setPrimary(String text, Icons.Type icon, UI.Kind kind) {
        primaryBtn.setKind(kind);
        primaryBtn.setText(text);
        primaryBtn.setIcon(Icons.of(icon, 15, Color.WHITE));
    }

    /**
     * The "REVIEW STATUS CHANGE" panel: coloured caps title with icon, main message,
     * optional inner card, and a small footnote about the service provider.
     */
    private JComponent panel(Color bg, Color border, Color accent, Icons.Type icon, String message,
                             JComponent inner, String footnote, Color footColor) {
        UI.RoundPanel p = new UI.RoundPanel(null, 8, bg, border);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel title = UI.label("REVIEW STATUS CHANGE", Theme.CAPS, accent);
        title.setIcon(Icons.of(icon, 13, accent));
        title.setIconTextGap(5);
        add(p, title, 8);
        add(p, wrapText(message, Theme.SMALL.deriveFont(12.5f), Theme.TEXT), inner != null || footnote != null ? 10 : 0);
        if (inner != null) add(p, inner, footnote != null ? 10 : 0);
        if (footnote != null) {
            JPanel row = UI.clear(new BorderLayout(6, 0));
            JLabel fi = new JLabel(Icons.of(Icons.Type.INFO, 12, footColor));
            fi.setVerticalAlignment(SwingConstants.TOP);
            fi.setBorder(new EmptyBorder(1, 0, 0, 0));
            row.add(fi, BorderLayout.WEST);
            row.add(wrapText(footnote, Theme.TINY, footColor == Theme.RED ? Theme.TEXT_2 : Theme.MUTED), BorderLayout.CENTER);
            add(p, row, 0);
        }
        return p;
    }

    /** Inner white card shown when reactivating. */
    private JComponent renewalReminder() {
        UI.RoundPanel c = new UI.RoundPanel(new BorderLayout(8, 0), 6, Color.WHITE, Theme.BORDER);
        c.setBorder(new EmptyBorder(10, 10, 10, 10));
        JLabel ic = new JLabel(Icons.of(Icons.Type.CALENDAR, 14, Theme.PRIMARY));
        ic.setVerticalAlignment(SwingConstants.TOP);
        c.add(ic, BorderLayout.WEST);
        JTextPane text = htmlText("Review the next renewal date before confirming reactivation. Current renewal date: <b style='color:#0F172A'>"
                + Theme.date(sub.nextRenewal) + "</b>. (You can update the renewal date anytime via Edit Subscription.)", Theme.TINY, Theme.MUTED);
        JPanel col = UI.clear(new BorderLayout(0, 3));
        col.add(UI.label("Renewal Date Reminder", Theme.SMALL_B, Theme.TEXT), BorderLayout.NORTH);
        col.add(text, BorderLayout.CENTER);
        c.add(col, BorderLayout.CENTER);
        return c;
    }

    private static void add(JPanel p, JComponent c, int gapAfter) {
        c.setAlignmentX(0f);
        p.add(c);
        if (gapAfter > 0) p.add(Box.createVerticalStrut(gapAfter));
    }

    /** Plain text that wraps to the available width. */
    private static JTextArea wrapText(String text, Font f, Color c) {
        JTextArea t = new JTextArea(text);
        t.setFont(f);
        t.setForeground(c);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setBorder(null);
        return t;
    }

    /** Wrapping text that supports simple HTML (used for the bold renewal date). */
    private static JTextPane htmlText(String html, Font f, Color c) {
        JTextPane t = new JTextPane();
        t.setContentType("text/html");
        t.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        t.setFont(f);
        t.setForeground(c);
        t.setText("<html><body style='margin:0'>" + html + "</body></html>");
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setBorder(null);
        return t;
    }

    @Override protected boolean save() {
        Store.get().changeStatus(sub, status.selected());
        return true;
    }
}
