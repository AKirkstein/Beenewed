package beenewed.screens;

import beenewed.App;
import beenewed.model.AnalyticsEngine;
import beenewed.model.AnalyticsEngine.CategoryMetric;
import beenewed.model.AnalyticsEngine.EfficiencyReport;
import beenewed.model.AnalyticsEngine.ForecastMonth;
import beenewed.model.AnalyticsEngine.ForecastReport;
import beenewed.model.AnalyticsEngine.UsageEfficiency;
import beenewed.model.CsvExporter;
import beenewed.model.Models.*;
import beenewed.model.SplitAllocator.DistributionSummary;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * S5 — Reports & Analytics: Actual Paid, Recurring Spend & 1–6 Month Forecast,
 * Usage & Cost Efficiency Telemetry, and CSV Summary Exports.
 */
public class ReportsScreen extends JPanel {
    private static final String[] HORIZON_LABELS = {"1 Month", "2 Months", "3 Months", "4 Months", "5 Months", "6 Months"};

    private final UI.Segmented basis = new UI.Segmented(
            "Actual Paid",
            "Recurring &<br>Forecast",
            "Usage &<br>Efficiency");
    private final UI.Combo<String> period = new UI.Combo<>("Monthly", "Yearly");
    private final UI.Combo<String> month;
    private final UI.Combo<Integer> year;
    private final UI.Combo<String> estimate = new UI.Combo<>("Monthly Equivalent", "Annual Equivalent");
    private final UI.Combo<String> forecastHorizon = new UI.Combo<>(HORIZON_LABELS);
    private final JPanel filterRow = UI.clear(new FlowLayout(FlowLayout.LEFT, 14, 0));
    private final JPanel applyBar = UI.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JPanel results = UI.clear(new BorderLayout());

    // applied filter values
    private int aBasis = 0;
    private String aPeriod = "Monthly";
    private Month aMonth;
    private int aYear;
    private String aEstimate = "Monthly Equivalent";
    private int aHorizonMonths = 6;

    public ReportsScreen() {
        super(new BorderLayout());
        setBackground(Theme.BG);
        String[] months = new String[12];
        for (int i = 0; i < 12; i++) months[i] = Month.of(i + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        month = new UI.Combo<>(months);
        LocalDate today = Store.today();
        year = new UI.Combo<>(today.getYear() - 2, today.getYear() - 1, today.getYear(), today.getYear() + 1);
        month.setSelectedIndex(today.getMonthValue() - 1);
        year.setSelectedItem(today.getYear());
        aMonth = today.getMonth();
        aYear = today.getYear();
        forecastHorizon.setSelectedIndex(5); // default: 6 Months

        period.addActionListener(e -> month.setEnabled("Monthly".equals(period.selected())));
        basis.onSelect(i -> { buildFilterRow(); apply(); });
        estimate.addActionListener(e -> { if (aBasis == 1) apply(); });
        forecastHorizon.addActionListener(e -> { if (aBasis == 1) apply(); });

        period.setPreferredSize(new Dimension(118, period.getPreferredSize().height));
        month.setPreferredSize(new Dimension(130, month.getPreferredSize().height));
        year.setPreferredSize(new Dimension(100, year.getPreferredSize().height));
        estimate.setPreferredSize(new Dimension(190, estimate.getPreferredSize().height));
        forecastHorizon.setPreferredSize(new Dimension(130, forecastHorizon.getPreferredSize().height));
        basis.setPreferredSize(new Dimension(420, 40));

        UI.Btn applyBtn = UI.ghost("Apply Filters", Icons.Type.FILTER).small();
        applyBtn.addActionListener(e -> apply());
        applyBar.add(applyBtn);

        UI.RoundPanel filters = UI.card(new BorderLayout(0, 10), 14);
        filters.add(filterRow, BorderLayout.CENTER);
        filters.add(applyBar, BorderLayout.SOUTH);
        buildFilterRow();

        UI.Btn exportBtn = UI.secondary("Export CSV", Icons.Type.SAVE);
        exportBtn.addActionListener(e -> buildExportMenu().show(exportBtn, 0, exportBtn.getHeight() + 4));

        JComponent header = Common.pageHeader(
                "Reports & Analytics",
                "Review actual spending, recurring commitments, 1\u20136 month forecasts, and usage efficiency.",
                exportBtn);
        add(Common.page(UI.vstack(16, header, filters, results)), BorderLayout.CENTER);
    }

    public void selectBasis(int index) {
        int clamped = Math.max(0, Math.min(2, index));
        basis.select(clamped);
        aBasis = clamped;
        buildFilterRow();
        refresh();
    }

    private JPopupMenu buildExportMenu() {
        Store st = Store.get();
        String stamp = Store.today().toString();
        JPopupMenu menu = new JPopupMenu();

        JMenuItem ledger = menuItem("Export Payment Ledger (.csv)", Icons.Type.RECEIPT, () -> {
            String csv = CsvExporter.exportPaymentLedger(st.paymentsNewestFirst(), st::payerNameFor);
            Common.exportCsvWithDialog(this, "beenewed-payment-ledger-" + stamp + ".csv", csv);
        });
        JMenuItem subs = menuItem("Export Active Subscriptions (.csv)", Icons.Type.CARD, () -> {
            String csv = CsvExporter.exportActiveSubscriptions(st.usageEfficiencyReport().rows());
            Common.exportCsvWithDialog(this, "beenewed-active-subscriptions-" + stamp + ".csv", csv);
        });
        JMenuItem splits = menuItem("Export Family Split Distributions (.csv)", Icons.Type.USERS, () -> {
            List<DistributionSummary> dists = st.allSharedPaymentDistributions();
            String csv = CsvExporter.exportFamilySplits(dists);
            Common.exportCsvWithDialog(this, "beenewed-family-splits-" + stamp + ".csv", csv);
        });

        menu.add(ledger);
        menu.add(subs);
        menu.add(splits);
        return menu;
    }

    private static JMenuItem menuItem(String label, Icons.Type icon, Runnable action) {
        JMenuItem item = new JMenuItem(label, Icons.of(icon, 14, Theme.TEXT_2));
        item.setFont(Theme.BODY);
        item.setBorder(new EmptyBorder(7, 10, 7, 16));
        item.addActionListener(e -> action.run());
        return item;
    }

    private static JComponent field(String label, JComponent c) { return UI.vstack(6, UI.caps(label), c); }

    private void buildFilterRow() {
        filterRow.removeAll();
        filterRow.add(field("Report View", basis));
        if (basis.getSelected() == 0) {
            filterRow.add(field("Period", period));
            filterRow.add(field("Month", month));
            filterRow.add(field("Year", year));
            applyBar.setVisible(true);
        } else if (basis.getSelected() == 1) {
            filterRow.add(field("Estimate Basis", estimate));
            filterRow.add(field("Forecast Horizon", forecastHorizon));
            applyBar.setVisible(true);
        } else {
            JLabel note = UI.label("30-day rolling window \u2022 0 uses = No Usage, 1\u20132 = Low Usage, 3+ = Active Use",
                    Theme.SMALL, Theme.MUTED);
            note.setBorder(new EmptyBorder(18, 4, 0, 0));
            filterRow.add(note);
            applyBar.setVisible(false);
        }
        filterRow.revalidate();
        filterRow.repaint();
    }

    private void apply() {
        aBasis = basis.getSelected();
        aPeriod = period.selected();
        aMonth = Month.of(month.getSelectedIndex() + 1);
        aYear = year.selected();
        aEstimate = estimate.selected();
        aHorizonMonths = Math.max(1, forecastHorizon.getSelectedIndex() + 1);
        refresh();
    }

    public void refresh() {
        results.removeAll();
        JComponent view = switch (aBasis) {
            case 1 -> estimateAndForecastReport();
            case 2 -> usageEfficiencyReport();
            default -> actualReport();
        };
        results.add(view, BorderLayout.NORTH);
        results.revalidate();
        results.repaint();
    }

    private JComponent chip(Icons.Type icon, String html) {
        UI.RoundPanel p = new UI.RoundPanel(new BorderLayout(), 8, Theme.SUBTLE, null);
        p.setBorder(new EmptyBorder(5, 9, 5, 9));
        JLabel l = UI.label("<html>" + html + "</html>", Theme.TINY, Theme.TEXT_2);
        l.setIcon(Icons.of(icon, 13, Theme.MUTED));
        p.add(l);
        return p;
    }

    private JComponent summary(Icons.Type icon, String title, String amount, String amountNote, String desc, JComponent... chips) {
        UI.RoundPanel card = UI.card(new BorderLayout(20, 0), 18);
        JLabel amt = UI.label("<html><span style='font-size:20pt'><b>" + amount + "</b></span>" +
                (amountNote == null ? "" : " &nbsp;<span style='color:" + Theme.hex(Theme.MUTED) + "'>" + amountNote + "</span>") + "</html>", Theme.BODY, Theme.TEXT);
        card.add(UI.vstack(8, Common.cardHeading(icon, title, null), amt, UI.label(desc, Theme.SMALL, Theme.MUTED)), BorderLayout.CENTER);
        JPanel cp = UI.clear(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        cp.setPreferredSize(new Dimension(440, 76));
        for (JComponent c : chips) cp.add(c);
        card.add(cp, BorderLayout.EAST);
        return card;
    }

    private UI.RoundPanel tableCard(String title, String count, String sub, JComponent center, JComponent footer) {
        UI.RoundPanel card = UI.card(new BorderLayout(0, 14), 18);
        JPanel head = UI.vstack(4, UI.split(UI.h2(title), UI.label(count, Theme.SMALL, Theme.MUTED)), UI.wrap(sub, 520, Theme.SMALL, Theme.MUTED));
        card.add(head, BorderLayout.NORTH);
        card.add(center, BorderLayout.CENTER);
        if (footer != null) card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private static JLabel dotLabel(String text, Color color) {
        JLabel l = UI.label(text, Theme.BODY_B, Theme.TEXT);
        l.setIcon(new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) { UI.aa((Graphics2D) g); g.setColor(color); g.fillOval(x, y + 1, 6, 6); }
            public int getIconWidth() { return 6; }
            public int getIconHeight() { return 8; }
        });
        l.setIconTextGap(6);
        return l;
    }

    private JComponent footerHint(String text) {
        JPanel p = UI.clear(new BorderLayout());
        p.add(UI.label(text, Theme.SMALL, Theme.MUTED), BorderLayout.WEST);
        p.add(new JLabel(Icons.of(Icons.Type.EXTERNAL, 13, Theme.MUTED)), BorderLayout.EAST);
        return p;
    }

    // ---------------------------------------------------------------- Actual paid
    private JComponent actualReport() {
        boolean monthly = "Monthly".equals(aPeriod);
        LocalDate from = monthly ? LocalDate.of(aYear, aMonth, 1) : LocalDate.of(aYear, 1, 1);
        LocalDate to = monthly ? YearMonth.of(aYear, aMonth).atEndOfMonth() : LocalDate.of(aYear, 12, 31);
        List<Payment> ps = Store.get().payments.stream()
                .filter(p -> !p.date.isBefore(from) && !p.date.isAfter(to))
                .collect(Collectors.toList());
        double total = ps.stream().mapToDouble(p -> p.amount).sum();
        String label = monthly ? aMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + aYear : String.valueOf(aYear);
        String range = monthly ? aMonth.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " 1\u2013" + to.getDayOfMonth() + ", " + aYear : "Jan 1\u2013Dec 31, " + aYear;

        JComponent sum = summary(Icons.Type.RECEIPT, "Actual Spending \u2014 " + label, Theme.peso(total), "Actual Paid",
                "Total recorded payments during the selected period.",
                chip(Icons.Type.CASH, "Recorded Payments: <b>" + ps.size() + "</b>"),
                chip(Icons.Type.CALENDAR, "Reporting Period: <b>" + range + "</b>"),
                chip(Icons.Type.CHECK_CIRCLE, "Immutable ledger"));

        Map<String, List<Payment>> byCat = new TreeMap<>();
        for (Payment p : ps) byCat.computeIfAbsent(p.sub.category, k -> new ArrayList<>()).add(p);
        DataTable ct = new DataTable(new String[]{"Category", "Recorded\nPayments", "Actual Paid"}, new float[]{2.2f, 1.3f, 1.5f},
                new int[]{DataTable.L, DataTable.C, DataTable.R}, false);
        byCat.entrySet().stream().sorted((a, b) -> Double.compare(sum(b.getValue()), sum(a.getValue())))
                .forEach(e -> ct.addRow(null, dotLabel(e.getKey(), Theme.PRIMARY), center(String.valueOf(e.getValue().size())), Common.money(sum(e.getValue()), true)));
        if (byCat.isEmpty()) ct.setEmpty("No payments recorded in this period.");
        else ct.addTotalRow("Total", ps.size(), Theme.peso(total));

        Map<Subscription, List<Payment>> bySub = new LinkedHashMap<>();
        ps.stream().sorted(Comparator.comparing((Payment p) -> -p.amount)).forEach(p -> bySub.computeIfAbsent(p.sub, k -> new ArrayList<>()).add(p));
        DataTable st = new DataTable(new String[]{"Subscription", "Category", "Payments", "Amount Paid"}, new float[]{2.1f, 1.5f, 1.1f, 1.5f},
                new int[]{DataTable.L, DataTable.L, DataTable.C, DataTable.R}, false);
        bySub.forEach((s, l) -> st.addRow(() -> App.get().openDetails(s, 1), Common.subCell(s, true), UI.label(s.category, Theme.SMALL, Theme.MUTED),
                center(String.valueOf(l.size())), Common.money(sum(l), true)));
        if (bySub.isEmpty()) st.setEmpty("No payments recorded in this period.");
        else st.addTotalRow("Total", "\u2014", ps.size(), Theme.peso(total));

        JPanel two = UI.clear(new GridLayout(1, 2, 16, 0));
        two.add(tableCard("Spending by Category", byCat.size() + (byCat.size() == 1 ? " Category" : " Categories"),
                "Breakdown of recorded subscription payments by category.", ct, null));
        two.add(tableCard("Spending by Subscription", bySub.size() + (bySub.size() == 1 ? " Subscription" : " Subscriptions"),
                "Recorded spending for each subscription during the selected period.", st, footerHint("Click any subscription to inspect historical ledger records.")));

        JComponent note = UI.note(Icons.Type.INFO, Theme.INFO_BG, Theme.ACCENT, "<html><span style='color:" + Theme.hex(Theme.ACCENT) + "'>About Actual Spending</span></html>",
                "Actual paid is calculated directly from subscription payment entries recorded within the selected period. Upcoming renewals and pending member contributions are not counted.", 680);
        JPanel nw = UI.clear(new FlowLayout(FlowLayout.LEFT, 0, 0)); nw.add(note);
        return UI.vstack(16, sum, two, nw);
    }

    // ---------------------------------------------------------------- Recurring & Forecast
    private JComponent estimateAndForecastReport() {
        Store s = Store.get();
        boolean annual = "Annual Equivalent".equals(aEstimate);
        List<Subscription> act = s.active();
        double total = annual ? s.annualTotal() : s.monthlyTotal();
        long monthlyPlans = act.stream().filter(x -> x.cycle == Cycle.MONTHLY).count();
        String eq = annual ? "Annual Equivalent" : "Monthly Equivalent";

        JComponent sum = summary(Icons.Type.CLOCK, "Current Estimated " + (annual ? "Annual" : "Monthly") + " Cost", Theme.peso(total), null,
                "Standardized recurring commitment based on active subscription prices and billing cycles.",
                chip(Icons.Type.LIST, "Active Subscriptions: <b>" + act.size() + "</b>"),
                chip(Icons.Type.CALENDAR, "Monthly Plans: <b>" + monthlyPlans + "</b>"),
                chip(Icons.Type.CALENDAR, "Yearly Plans: <b>" + (act.size() - monthlyPlans) + "</b>"),
                chip(Icons.Type.CASH, (annual ? "Monthly Equivalent: <b>" + Theme.peso(s.monthlyTotal()) : "Annual Equivalent: <b>" + Theme.peso(s.annualTotal())) + "</b>"));

        // Category Distribution with visual bars
        List<CategoryMetric> categories = s.categoryDistribution();
        JComponent categoryBarsCard = buildCategoryDistributionCard(categories, annual);

        // 1-6 Month Projected Expense Forecast
        ForecastReport forecast = s.forecastExpenses(aHorizonMonths);
        JComponent forecastCard = buildForecastCard(forecast);

        // Estimated cost by subscription table
        DataTable st = new DataTable(new String[]{"Subscription", "Category", "Current Price", "Billing Cycle", eq},
                new float[]{2.2f, 1.6f, 1.4f, 1.4f, 1.8f}, new int[]{DataTable.L, DataTable.L, DataTable.R, DataTable.C, DataTable.R}, false);
        for (Subscription x : act) {
            JLabel price = UI.label(Theme.peso(x.price), Theme.SMALL, Theme.TEXT_2);
            price.setHorizontalAlignment(SwingConstants.RIGHT);
            JLabel cyc = UI.label(x.cycle.label, Theme.SMALL, Theme.MUTED);
            cyc.setHorizontalAlignment(SwingConstants.CENTER);
            BigDecimal eqVal = annual ? AnalyticsEngine.annualEquivalent(x) : AnalyticsEngine.monthlyEquivalent(x);
            st.addRow(() -> App.get().openDetails(x, 0), Common.subCell(x, true), UI.label(x.category, Theme.SMALL, Theme.MUTED), price, cyc,
                    Common.money(eqVal.doubleValue(), true));
        }
        if (act.isEmpty()) st.setEmpty("No active subscriptions.");
        else st.addTotalRow("Total " + eq, "", "", "", Theme.peso(total));

        JComponent note = UI.note(Icons.Type.INFO, Theme.INFO_BG, Theme.ACCENT,
                "<html><span style='color:" + Theme.hex(Theme.ACCENT) + "'>About Recurring Estimates &amp; Forecasts</span></html>",
                "Recurring equivalents normalize Weekly (\u00D752/12), Monthly, and Yearly (\u00F712) plans to a common basis. The 1\u20136 month forecast projects scheduled cash flow using each active subscription\u2019s actual next renewal date. These figures are separate from Actual Paid records.", 760);

        return UI.vstack(16, sum, categoryBarsCard, forecastCard,
                tableCard("Estimated Cost by Subscription", act.size() + (act.size() == 1 ? " Subscription" : " Subscriptions"),
                        "Active subscription prices normalized to a common " + (annual ? "annual" : "monthly") + " equivalent.", st,
                        footerHint("Click any subscription to view subscription details.")),
                note);
    }

    private JComponent buildCategoryDistributionCard(List<CategoryMetric> categories, boolean annual) {
        if (categories.isEmpty()) {
            return tableCard("Category Distribution", "0 Categories",
                    "Share of recurring spend across active subscription categories.",
                    UI.label("No active subscriptions to analyze.", Theme.SMALL, Theme.MUTED), null);
        }
        Color[] palette = {Theme.PRIMARY, Theme.ACCENT, Theme.GREEN, Theme.ORANGE, Theme.AMBER, Theme.RED};
        JPanel rows = UI.vstack(10);
        for (int i = 0; i < categories.size(); i++) {
            CategoryMetric cm = categories.get(i);
            Color barColor = palette[i % palette.length];
            BigDecimal amount = annual ? cm.annualTotal() : cm.monthlyTotal();
            String countText = cm.activeCount() + (cm.activeCount() == 1 ? " active" : " active");
            JComponent top = UI.split(
                    UI.hrow(8, dotLabel(cm.category(), barColor), UI.label(countText, Theme.TINY, Theme.MUTED)),
                    UI.hrow(10,
                            UI.label(cm.sharePercent().toPlainString() + "%", Theme.SMALL_B, Theme.TEXT_2),
                            UI.label(Theme.peso(amount.doubleValue()) + (annual ? "/yr" : "/mo"), Theme.BODY_B, Theme.TEXT)));
            JComponent bar = Common.barTrack(cm.sharePercent().doubleValue() / 100.0, barColor);
            rows.add(UI.vstack(5, top, bar));
        }
        return tableCard("Category Distribution",
                categories.size() + (categories.size() == 1 ? " Category" : " Categories"),
                "Proportional breakdown of active recurring spend by category (" + (annual ? "annual" : "monthly") + " equivalent).",
                rows, null);
    }

    private JComponent buildForecastCard(ForecastReport forecast) {
        BigDecimal maxMonth = forecast.months().stream()
                .map(ForecastMonth::projectedAmount)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);

        JPanel chips = UI.hrow(10,
                chip(Icons.Type.CASH, "Projected Total: <b>" + Theme.peso(forecast.totalProjected().doubleValue()) + "</b>"),
                chip(Icons.Type.CALENDAR, "Scheduled Renewals: <b>" + forecast.totalRenewals() + "</b>"),
                chip(Icons.Type.CLOCK, "Horizon: <b>" + forecast.horizonMonths() + (forecast.horizonMonths() == 1 ? " Month" : " Months") + "</b>"));

        DataTable ft = new DataTable(
                new String[]{"Forecast Window", "Scheduled\nRenewals", "Subscriptions Due", "Cash-Flow Bar", "Projected\nExpense"},
                new float[]{1.9f, 1.0f, 2.2f, 1.7f, 1.3f},
                new int[]{DataTable.L, DataTable.C, DataTable.L, DataTable.L, DataTable.R}, false);

        for (ForecastMonth fm : forecast.months()) {
            double ratio = maxMonth.signum() == 0 ? 0.0 : fm.projectedAmount().doubleValue() / maxMonth.doubleValue();
            String dueNames = fm.renewalNames().isEmpty()
                    ? "\u2014"
                    : fm.renewalNames().size() <= 3
                            ? String.join(", ", fm.renewalNames())
                            : String.join(", ", fm.renewalNames().subList(0, 3)) + " +" + (fm.renewalNames().size() - 3) + " more";
            JPanel barWrap = UI.clear(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;
            barWrap.add(Common.barTrack(ratio, fm.projectedAmount().signum() > 0 ? Theme.PRIMARY : Theme.BORDER), gbc);

            ft.addRow(null,
                    UI.label("Month " + fm.monthNumber() + " \u2022 " + fm.label(), Theme.BODY_B, Theme.TEXT),
                    center(String.valueOf(fm.renewalCount())),
                    UI.label(dueNames, Theme.SMALL, Theme.MUTED),
                    barWrap,
                    Common.money(fm.projectedAmount().doubleValue(), fm.projectedAmount().signum() > 0));
        }
        ft.addTotalRow("Total (" + forecast.horizonMonths() + (forecast.horizonMonths() == 1 ? " Month)" : " Months)"),
                forecast.totalRenewals(), "\u2014", "", Theme.peso(forecast.totalProjected().doubleValue()));

        return tableCard("Projected Expense Forecast (Next " + forecast.horizonMonths() + (forecast.horizonMonths() == 1 ? " Month)" : " Months)"),
                Theme.peso(forecast.totalProjected().doubleValue()),
                "Scheduled cash-flow projection from active renewal dates (yearly plans appear in their exact renewal month).",
                UI.vstack(12, chips, ft), null);
    }

    // ---------------------------------------------------------------- Usage & Efficiency
    private JComponent usageEfficiencyReport() {
        EfficiencyReport report = Store.get().usageEfficiencyReport();

        String heroAmt = Theme.peso(report.underutilizedMonthlySpend().doubleValue());
        String desc = report.underutilizedCount() == 0
                ? "All active subscriptions have 3+ logged uses in the last " + report.windowDays() + " days."
                : "Monthly recurring spend on active subscriptions flagged as No Usage or Low Usage in the last " + report.windowDays() + " days.";

        JComponent sum = summary(Icons.Type.CHART, "Underutilized Monthly Spend", heroAmt, "/ month", desc,
                chip(Icons.Type.LIST, "Active Subscriptions: <b>" + report.totalActiveCount() + "</b>"),
                chip(Icons.Type.ALERT, "No Usage (0): <b>" + report.unusedCount() + "</b>"),
                chip(Icons.Type.CLOCK, "Low Usage (1\u20132): <b>" + report.lowUsageCount() + "</b>"),
                chip(Icons.Type.CASH, "Annualized Opportunity: <b>" + Theme.peso(report.underutilizedAnnualSpend().doubleValue()) + "</b>"));

        DataTable ut = new DataTable(
                new String[]{"Subscription", "Monthly\nCost", "Cost / Day", "Uses\n(30d)", "Cost / Use", "Last Used", "Utilization"},
                new float[]{2.3f, 1.3f, 1.2f, 1.0f, 1.3f, 1.4f, 1.3f},
                new int[]{DataTable.L, DataTable.R, DataTable.R, DataTable.C, DataTable.R, DataTable.L, DataTable.L}, false);

        for (UsageEfficiency row : report.rows()) {
            String usesText = row.usesInWindow() == 0 ? "0" : String.valueOf(row.usesInWindow());
            JComponent costPerUseCell = row.costPerUse() == null
                    ? UI.label("No usage", Theme.SMALL, Theme.RED)
                    : Common.money(row.costPerUse().doubleValue(), true);
            if (costPerUseCell instanceof JLabel lbl) lbl.setHorizontalAlignment(SwingConstants.RIGHT);

            ut.addRow(
                    () -> App.get().openDetails(row.subscription(), 3),
                    Common.subCell(row.subscription(), true),
                    Common.money(row.monthlyCost().doubleValue(), false),
                    Common.money(row.costPerDay().doubleValue(), false),
                    center(usesText),
                    costPerUseCell,
                    UI.label(row.lastUsedDate() == null ? "Never" : Theme.date(row.lastUsedDate()), Theme.SMALL, Theme.MUTED),
                    Common.utilizationBadge(row.level()));
        }
        if (report.rows().isEmpty()) {
            ut.setEmpty("No active subscriptions to evaluate.");
        }

        JComponent note = UI.note(Icons.Type.INFO, Theme.INFO_BG, Theme.ACCENT,
                "<html><span style='color:" + Theme.hex(Theme.ACCENT) + "'>How Usage Efficiency Is Calculated</span></html>",
                "Cost / Day = Annual Equivalent \u00F7 365. Cost / Use = Monthly Cost \u00F7 logged uses in the last 30 days. Subscriptions with 0 uses are flagged <b>No Usage</b> and 1\u20132 uses are flagged <b>Low Usage</b> to highlight optimization opportunities.", 760);

        return UI.vstack(16, sum,
                tableCard("Subscription Usage & Cost Efficiency",
                        report.underutilizedCount() + " underutilized of " + report.totalActiveCount(),
                        "Review cost-per-use and cost-per-day telemetry for every active subscription.",
                        ut, footerHint("Click any subscription to open its Usage History and log activity.")),
                note);
    }

    private static double sum(List<Payment> l) { return l.stream().mapToDouble(p -> p.amount).sum(); }

    private static JLabel center(String t) {
        JLabel l = UI.label(t, Theme.BODY, Theme.MUTED);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        return l;
    }
}
