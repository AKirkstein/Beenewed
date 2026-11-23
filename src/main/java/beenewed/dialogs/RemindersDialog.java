package beenewed.dialogs;

import beenewed.App;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.screens.Common;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/** D8 \u2014 Renewal reminders: upcoming within the reminder window, plus overdue renewals. */
public class RemindersDialog extends BaseDialog {

    public RemindersDialog(JFrame owner) {
        super(owner, "Renewal Reminders", "Subscriptions approaching renewal or overdue.", Icons.Type.BELL,
                "Open Renewals & Payments", Icons.Type.ARROW_RIGHT, 720);
        cancelBtn.setText("Close");
        Store st = Store.get();

        UI.RoundPanel info = new UI.RoundPanel(new BorderLayout(), 8, Theme.SUBTLE, Theme.BORDER);
        info.setBorder(new EmptyBorder(8, 12, 8, 12));
        JLabel il = UI.label("<html>Showing renewals due within <b>" + st.remindDays + " days</b> \u2022 Reference date: <b>" + Theme.date(Store.today()) + "</b></html>", Theme.SMALL, Theme.TEXT_2);
        il.setIcon(Icons.of(Icons.Type.INFO, 14, Theme.MUTED));
        info.add(il);
        info.setMaximumSize(new Dimension(Integer.MAX_VALUE, info.getPreferredSize().height));
        place(info); gap(16);

        List<Subscription> up = st.upcomingReminders();
        place(section("Upcoming Renewals", up.size(), "Subscriptions approaching their next renewal date. Click View to inspect details and dismiss.")); gap(10);
        if (up.isEmpty()) place(empty("No unviewed renewals within the next " + st.remindDays + " days."));
        for (Subscription s : up) { place(item(s, Store.ReminderKind.UPCOMING)); gap(8); }

        if (st.showOverdue) {
            gap(12);
            List<Subscription> od = st.overdue();
            place(section("Overdue Renewals", od.size(), "Active subscriptions past their due date. Click View to inspect details and dismiss.")); gap(10);
            if (od.isEmpty()) place(empty("No unviewed overdue renewals. You're all caught up."));
            for (Subscription s : od) { place(item(s, Store.ReminderKind.OVERDUE)); gap(8); }
        }
    }

    private JComponent section(String title, int count, String sub) {
        JPanel p = UI.vstack(3, UI.hrow(8, UI.label(title, Theme.H3, Theme.TEXT), UI.countBadge(String.valueOf(count))),
                UI.label(sub, Theme.SMALL, Theme.MUTED));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JComponent item(Subscription s, Store.ReminderKind kind) {
        UI.RoundPanel p = new UI.RoundPanel(new BorderLayout(12, 0), 10, Theme.SURFACE, Theme.BORDER);
        p.setBorder(new EmptyBorder(12, 12, 12, 12));
        p.add(UI.iconBox(s.icon, 34, 17), BorderLayout.WEST);
        p.add(UI.vstack(3, UI.label(s.name, Theme.BODY_B, Theme.TEXT),
                UI.label("Renews " + Theme.date(s.nextRenewal) + "  \u2022  Expected " + Theme.peso(s.price) + "  \u2022  " + s.cycle.label, Theme.SMALL, Theme.MUTED)), BorderLayout.CENTER);
        UI.Btn view = UI.link("View");
        view.addActionListener(e -> {
            Store.get().markReminderViewed(s, kind);
            Store.get().changed();
            close();
            if (App.get() != null) {
                App.get().openDetails(s, 0);
            }
        });
        JPanel right = UI.clear(new GridBagLayout());
        right.add(UI.hrow(14, Common.dueBadge(s.nextRenewal), view));
        p.add(right, BorderLayout.EAST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private JComponent empty(String text) {
        UI.RoundPanel p = new UI.RoundPanel(new GridBagLayout(), 10, Theme.SUBTLE, null);
        p.setBorder(new EmptyBorder(22, 12, 22, 12));
        JLabel l = UI.label(text, Theme.SMALL, Theme.MUTED);
        l.setIcon(Icons.of(Icons.Type.CHECK_CIRCLE, 16, Theme.GREEN));
        l.setIconTextGap(8);
        p.add(l);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    @Override protected boolean save() {
        // "Open Renewals & Payments"
        SwingUtilities.invokeLater(() -> App.get().openRenewals(0));
        return true;
    }
}
