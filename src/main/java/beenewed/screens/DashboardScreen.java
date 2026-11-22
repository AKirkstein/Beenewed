package beenewed.screens;

import beenewed.App;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDate;
import java.util.List;

/** S1 \u2014 Dashboard: stat cards, upcoming renewals and recent payments. */
public class DashboardScreen extends JPanel {
    public DashboardScreen() { super(new BorderLayout()); setBackground(Theme.BG); }

    public void refresh() {
        removeAll();
        Store st = Store.get();
        LocalDate today = Store.today();
        var summary = st.dashboardSummary();

        UI.Btn add = UI.primary("Add Subscription", Icons.Type.PLUS);
        add.addActionListener(e -> App.get().addSubscription());
        JComponent header = Common.pageHeader("Dashboard", "A quick overview of your subscriptions, payments, and renewals.", add);

        // ---- Stat cards
        String month = today.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
                + " " + today.getYear();
        JPanel stats = UI.clear(new GridLayout(1, 4, 14, 0));
        stats.add(stat("Active Subscriptions", Icons.Type.CARD, String.valueOf(summary.activeSubscriptionCount()), "Currently active services"));
        stats.add(stat("Estimated Monthly Cost", Icons.Type.DASHBOARD, Theme.peso(summary.estimatedMonthlyCost().doubleValue()), "Current recurring-cost equivalent"));
        stats.add(stat("Estimated Annual Cost", Icons.Type.CALENDAR, Theme.peso(summary.estimatedAnnualCost().doubleValue()), "Current recurring-cost equivalent"));
        stats.add(stat("Actual Paid \u2014 " + month, Icons.Type.RECEIPT, Theme.peso(summary.currentMonthActualPaid().doubleValue()), "Recorded payments this month"));

        // ---- Upcoming renewals
        DataTable up = new DataTable(new String[]{"Subscription", "Renewal\nDate", "Expected\nAmount", "Due"},
                new float[]{3.2f, 2f, 1.9f, 1.6f}, new int[]{DataTable.L, DataTable.L, DataTable.R, DataTable.C}, false);
        List<Subscription> upcoming = st.byRenewal(st.upcomingReminders(summary));
        for (Subscription s : upcoming.subList(0, Math.min(4, upcoming.size()))) {
            up.addRow(() -> App.get().openDetails(s, 0), Common.subCell(s, true), Theme.date(s.nextRenewal), Common.money(s.price, true), Common.dueBadge(s.nextRenewal));
        }
        if (upcoming.isEmpty()) up.setEmpty("No active subscriptions yet.");
        UI.Btn viewAllUp = UI.link("View All");
        viewAllUp.addActionListener(e -> App.get().openRenewals(0));
        UI.Btn reminders = UI.soft("View Reminders", Icons.Type.BELL).small();
        reminders.addActionListener(e -> App.get().showReminders());
        JPanel remRow = UI.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        remRow.add(reminders);
        JComponent upCard = section("Upcoming Renewals", viewAllUp, up, remRow);

        // ---- Recent payments
        DataTable rp = new DataTable(new String[]{"Subscription", "Date", "Amount"},
                new float[]{3.2f, 2f, 1.6f}, new int[]{DataTable.L, DataTable.L, DataTable.R}, false);
        List<Payment> pays = st.paymentsNewestFirst();
        for (Payment p : pays.subList(0, Math.min(4, pays.size()))) {
            rp.addRow(() -> App.get().openDetails(p.sub, 1), Common.subCell(p.sub, true), Theme.date(p.date), Common.money(p.amount, true));
        }
        if (pays.isEmpty()) rp.setEmpty("No payments recorded yet.");
        double total = st.allTimeActualPaid().doubleValue();
        UI.Btn viewAllPay = UI.link("View All");
        viewAllPay.addActionListener(e -> App.get().openRenewals(1));
        JLabel totalLbl = UI.label("<html>Total of " + pays.size() + " recorded payments: <b style='color:#0F172A'>" + Theme.peso(total) + "</b></html>", Theme.SMALL, Theme.MUTED);
        JComponent payCard = section("Recent Payments", viewAllPay, rp, totalLbl);

        JPanel lower = UI.clear(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH; c.weighty = 1; c.gridy = 0;
        c.gridx = 0; c.weightx = 0.6; c.insets = new Insets(0, 0, 0, 8); lower.add(upCard, c);
        c.gridx = 1; c.weightx = 0.4; c.insets = new Insets(0, 8, 0, 0); lower.add(payCard, c);

        JPanel body = UI.vstack(16, header, stats, lower);
        add(Common.page(body), BorderLayout.CENTER);
        revalidate(); repaint();
    }

    private JComponent stat(String label, Icons.Type icon, String value, String sub) {
        UI.RoundPanel card = UI.card(new BorderLayout(), 16);
        JLabel ic = new JLabel(Icons.of(icon, 15, Theme.MUTED));
        card.add(UI.split(UI.label(label, Theme.SMALL, Theme.TEXT_2), ic), BorderLayout.NORTH);
        JPanel v = UI.vstack(4, UI.label(value, Theme.BIG_NUM.deriveFont(21f), Theme.TEXT), UI.wrap(sub, 130, Theme.TINY, Theme.MUTED));
        v.setBorder(new EmptyBorder(14, 0, 0, 0));
        card.add(v, BorderLayout.CENTER);
        return card;
    }

    private JComponent section(String title, JComponent action, JComponent table, JComponent footer) {
        UI.RoundPanel card = UI.card(new BorderLayout(0, 14), 16);
        card.add(UI.split(UI.h2(title), action), BorderLayout.NORTH);
        card.add(table, BorderLayout.CENTER);
        if (footer != null) {
            JPanel f = UI.clear(new BorderLayout());
            f.add(footer, BorderLayout.WEST);
            card.add(f, BorderLayout.SOUTH);
        }
        return card;
    }
}
