package beenewed.screens;

import beenewed.App;
import beenewed.model.AnalyticsEngine;
import beenewed.model.AnalyticsEngine.CategoryMetric;
import beenewed.model.AnalyticsEngine.EfficiencyReport;
import beenewed.model.AnalyticsEngine.UsageEfficiency;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/** S1 \u2014 Dashboard: stat cards, upcoming renewals and recent payments. */
public class DashboardScreen extends JPanel {
    public DashboardScreen() { super(new BorderLayout()); setBackground(Theme.BG); }

    public void refresh() {
        removeAll();
        Store st = Store.get();
        LocalDate today = Store.today();

        UI.Btn refreshBtn = UI.secondary("Refresh", Icons.Type.REFRESH);
        refreshBtn.setToolTipText("Re-sync data from Supabase");
        refreshBtn.addActionListener(e -> {
            refreshBtn.setEnabled(false);
            refreshBtn.setText("Refreshing...");
            Window win = SwingUtilities.getWindowAncestor(this);
            if (win != null) win.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            new SwingWorker<Boolean, Void>() {
                @Override
                protected Boolean doInBackground() {
                    return Store.get().forceRefresh();
                }

                @Override
                protected void done() {
                    if (win != null) win.setCursor(Cursor.getDefaultCursor());
                    refreshBtn.setEnabled(true);
                    refreshBtn.setText("Refresh");
                    try {
                        get();
                        Store.get().changed();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } catch (java.util.concurrent.ExecutionException error) {
                        Throwable cause = error.getCause();
                        if (cause instanceof RuntimeException runtime) {
                            Common.showBackendError(DashboardScreen.this, runtime);
                        } else {
                            JOptionPane.showMessageDialog(
                                    DashboardScreen.this,
                                    cause == null ? "Unable to refresh data." : cause.getMessage(),
                                    "Refresh failed",
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }.execute();
        });
        UI.Btn add = UI.primary("Add Subscription", Icons.Type.PLUS);
        add.addActionListener(e -> App.get().addSubscription());
        JComponent header = Common.pageHeader("Dashboard", "A quick overview of your subscriptions, payments, and renewals.", UI.hrow(8, refreshBtn, add));

        // ---- Stat cards
        double paidThisMonth = st.payments.stream()
                .filter(p -> p.date.getYear() == today.getYear() && p.date.getMonth() == today.getMonth())
                .mapToDouble(p -> p.amount).sum();
        String month = today.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + today.getYear();
        JPanel stats = UI.clear(new GridLayout(1, 4, 14, 0));
        stats.add(stat("Active Subscriptions", Icons.Type.CARD, String.valueOf(st.active().size()), "Currently active services"));
        stats.add(stat("Estimated Monthly Cost", Icons.Type.DASHBOARD, Theme.peso(st.monthlyTotal()), "Current recurring-cost equivalent"));
        stats.add(stat("Estimated Annual Cost", Icons.Type.CALENDAR, Theme.peso(st.annualTotal()), "Current recurring-cost equivalent"));
        stats.add(stat("Actual Paid \u2014 " + month, Icons.Type.RECEIPT, Theme.peso(paidThisMonth), "Recorded payments this month"));

        // ---- Upcoming renewals
        DataTable up = new DataTable(new String[]{"Subscription", "Renewal\nDate", "Expected\nAmount", "Due"},
                new float[]{3.2f, 2f, 1.9f, 1.6f}, new int[]{DataTable.L, DataTable.L, DataTable.R, DataTable.C}, false);
        List<Subscription> upcoming = st.byRenewal(st.active());
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
        double total = pays.stream().mapToDouble(p -> p.amount).sum();
        UI.Btn viewAllPay = UI.link("View All");
        viewAllPay.addActionListener(e -> App.get().openRenewals(1));
        JLabel totalLbl = UI.label("<html>Total of " + pays.size() + " recorded payments: <b style='color:" + Theme.hex(Theme.TEXT) + "'>" + Theme.peso(total) + "</b></html>", Theme.SMALL, Theme.MUTED);
        JComponent payCard = section("Recent Payments", viewAllPay, rp, totalLbl);

        JPanel lower = UI.clear(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH; c.weighty = 1; c.gridy = 0;
        c.gridx = 0; c.weightx = 0.6; c.insets = new Insets(0, 0, 0, 8); lower.add(upCard, c);
        c.gridx = 1; c.weightx = 0.4; c.insets = new Insets(0, 8, 0, 0); lower.add(payCard, c);

        // ---- Analytics & Usage Optimization row
        JComponent categoryCard = buildCategoryCard(st);
        JComponent optimizationCard = buildOptimizationCard(st);
        JPanel insights = UI.clear(new GridBagLayout());
        GridBagConstraints ic = new GridBagConstraints();
        ic.fill = GridBagConstraints.BOTH; ic.weighty = 1; ic.gridy = 0;
        ic.gridx = 0; ic.weightx = 0.55; ic.insets = new Insets(0, 0, 0, 8); insights.add(categoryCard, ic);
        ic.gridx = 1; ic.weightx = 0.45; ic.insets = new Insets(0, 8, 0, 0); insights.add(optimizationCard, ic);

        JPanel body = UI.vstack(16, header, stats, lower, insights);
        add(Common.page(body), BorderLayout.CENTER);
        revalidate(); repaint();
    }

    private JComponent buildCategoryCard(Store st) {
        List<CategoryMetric> categories = st.categoryDistribution();
        UI.Btn openReports = UI.link("Open Reports");
        openReports.addActionListener(e -> App.get().openReports(1));

        JComponent content;
        if (categories.isEmpty()) {
            content = UI.label("Add an active subscription to view category spending distribution.", Theme.SMALL, Theme.MUTED);
        } else {
            Color[] palette = {Theme.PRIMARY, Theme.ACCENT, Theme.GREEN, Theme.ORANGE, Theme.AMBER, Theme.RED};
            JPanel bars = UI.vstack(10);
            int limit = Math.min(5, categories.size());
            for (int i = 0; i < limit; i++) {
                CategoryMetric cm = categories.get(i);
                JComponent top = UI.split(
                        UI.label(cm.category() + " (" + cm.activeCount() + ")", Theme.SMALL_B, Theme.TEXT),
                        UI.label(cm.sharePercent().toPlainString() + "%  \u2022  " + Theme.peso(cm.monthlyTotal().doubleValue()) + "/mo", Theme.SMALL, Theme.TEXT_2));
                bars.add(UI.vstack(4, top, Common.barTrack(cm.sharePercent().doubleValue() / 100.0, palette[i % palette.length])));
            }
            content = bars;
        }
        JLabel foot = UI.label("Normalized monthly spend across " + categories.size()
                + (categories.size() == 1 ? " category." : " categories."), Theme.SMALL, Theme.MUTED);
        return section("Spending by Category", openReports, content, foot);
    }

    private JComponent buildOptimizationCard(Store st) {
        EfficiencyReport eff = st.usageEfficiencyReport();
        List<UsageEfficiency> flagged = eff.rows().stream()
                .filter(r -> r.level().isUnderutilized())
                .toList();
        UI.Btn openUsage = UI.link("View Efficiency");
        openUsage.addActionListener(e -> App.get().openReports(2));

        JComponent content;
        if (eff.totalActiveCount() == 0) {
            content = UI.label("No active subscriptions to analyze yet.", Theme.SMALL, Theme.MUTED);
        } else if (flagged.isEmpty()) {
            UI.RoundPanel ok = new UI.RoundPanel(new FlowLayout(FlowLayout.LEFT, 8, 8), 8, Theme.GREEN_BG, null);
            JLabel lbl = UI.label("All active subscriptions have healthy usage in the last 30 days.", Theme.SMALL_B, Theme.GREEN);
            lbl.setIcon(Icons.of(Icons.Type.CHECK_CIRCLE, 15, Theme.GREEN));
            ok.add(lbl);
            content = ok;
        } else {
            DataTable t = new DataTable(new String[]{"Subscription", "Uses (30d)", "Monthly", "Status"},
                    new float[]{2.5f, 1.1f, 1.3f, 1.4f},
                    new int[]{DataTable.L, DataTable.C, DataTable.R, DataTable.L}, false);
            for (UsageEfficiency row : flagged.subList(0, Math.min(4, flagged.size()))) {
                JLabel uses = UI.label(String.valueOf(row.usesInWindow()), Theme.SMALL, Theme.MUTED);
                uses.setHorizontalAlignment(SwingConstants.CENTER);
                t.addRow(() -> App.get().openDetails(row.subscription(), 3),
                        Common.subCell(row.subscription(), true),
                        uses,
                        Common.money(row.monthlyCost().doubleValue(), false),
                        Common.utilizationBadge(row.level()));
            }
            content = t;
        }
        JLabel foot = UI.label(flagged.isEmpty()
                ? "Log usage entries inside any subscription to track cost-per-use."
                : "<html>" + flagged.size() + " underutilized subscription" + (flagged.size() == 1 ? "" : "s")
                        + ": <b style='color:" + Theme.hex(Theme.TEXT) + "'>"
                        + Theme.peso(eff.underutilizedMonthlySpend().doubleValue()) + "/mo</b> optimization opportunity</html>",
                Theme.SMALL, Theme.MUTED);
        return section("Optimization Opportunities", openUsage, content, foot);
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
