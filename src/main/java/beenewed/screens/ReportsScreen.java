package beenewed.screens;

import beenewed.App;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/** S5 \u2014 Reports: Actual Paid (recorded payments) or Current Estimated Recurring Cost. */
public class ReportsScreen extends JPanel {
    private final UI.Segmented basis = new UI.Segmented("Actual Paid", "Current Estimated<br>Recurring Cost");
    private final UI.Combo<String> period = new UI.Combo<>("Monthly", "Yearly");
    private final UI.Combo<String> month;
    private final UI.Combo<Integer> year;
    private final UI.Combo<String> estimate = new UI.Combo<>("Monthly Equivalent", "Annual Equivalent");
    private final JPanel filterRow = UI.clear(new FlowLayout(FlowLayout.LEFT, 14, 0));
    private final JPanel results = UI.clear(new BorderLayout());

    // applied filter values (Apply Filters commits the combos)
    private int aBasis = 0; private String aPeriod = "Monthly"; private Month aMonth; private int aYear; private String aEstimate = "Monthly Equivalent";

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
        aMonth = today.getMonth(); aYear = today.getYear();

        period.addActionListener(e -> month.setEnabled("Monthly".equals(period.selected())));
        basis.onSelect(i -> { buildFilterRow(); apply(); });
        period.setPreferredSize(new Dimension(118, period.getPreferredSize().height));
        month.setPreferredSize(new Dimension(130, month.getPreferredSize().height));
        year.setPreferredSize(new Dimension(100, year.getPreferredSize().height));
        estimate.setPreferredSize(new Dimension(210, estimate.getPreferredSize().height));
        basis.setPreferredSize(new Dimension(300, 40));

        UI.Btn applyBtn = UI.ghost("Apply Filters", Icons.Type.FILTER).small();
        applyBtn.addActionListener(e -> apply());
        UI.RoundPanel filters = UI.card(new BorderLayout(0, 10), 14);
        filters.add(filterRow, BorderLayout.CENTER);
        JPanel ap = UI.clear(new FlowLayout(FlowLayout.LEFT, 0, 0)); ap.add(applyBtn);
        filters.add(ap, BorderLayout.SOUTH);
        buildFilterRow();

        JComponent header = Common.pageHeader("Reports", "Review your subscription spending and recurring costs.", null);
        add(Common.page(UI.vstack(16, header, filters, results)), BorderLayout.CENTER);
    }

    private static JComponent field(String label, JComponent c) { return UI.vstack(6, UI.caps(label), c); }

    private void buildFilterRow() {
        filterRow.removeAll();
        filterRow.add(field("Report Basis", basis));
        if (basis.getSelected() == 0) {
            filterRow.add(field("Period", period));
            filterRow.add(field("Month", month));
            filterRow.add(field("Year", year));
        } else {
            filterRow.add(field("Estimate Period", estimate));
        }
        filterRow.revalidate(); filterRow.repaint();
    }

    private void apply() {
        aBasis = basis.getSelected();
        aPeriod = period.selected();
        aMonth = Month.of(month.getSelectedIndex() + 1);
        aYear = year.selected();
        aEstimate = estimate.selected();
        refresh();
    }

    public void refresh() {
        results.removeAll();
        results.add(aBasis == 0 ? actualReport() : estimateReport(), BorderLayout.NORTH);
        results.revalidate(); results.repaint();
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
                (amountNote == null ? "" : " &nbsp;<span style='color:#64748B'>" + amountNote + "</span>") + "</html>", Theme.BODY, Theme.TEXT);
        card.add(UI.vstack(8, Common.cardHeading(icon, title, null), amt, UI.label(desc, Theme.SMALL, Theme.MUTED)), BorderLayout.CENTER);
        JPanel cp = UI.clear(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        cp.setPreferredSize(new Dimension(440, 76));
        for (JComponent c : chips) cp.add(c);
        card.add(cp, BorderLayout.EAST);
        return card;
    }

    private UI.RoundPanel tableCard(String title, String count, String sub, DataTable t, JComponent footer) {
        UI.RoundPanel card = UI.card(new BorderLayout(0, 14), 18);
        JPanel head = UI.vstack(4, UI.split(UI.h2(title), UI.label(count, Theme.SMALL, Theme.MUTED)), UI.wrap(sub, 330, Theme.SMALL, Theme.MUTED));
        card.add(head, BorderLayout.NORTH);
        card.add(t, BorderLayout.CENTER);
        if (footer != null) card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private static JLabel dotLabel(String text) {
        JLabel l = UI.label(text, Theme.BODY_B, Theme.TEXT);
        l.setIcon(new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) { UI.aa((Graphics2D) g); g.setColor(Theme.PRIMARY); g.fillOval(x, y + 1, 6, 6); }
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
        List<Payment> ps = Store.get().payments.stream().filter(p -> !p.date.isBefore(from) && !p.date.isAfter(to)).collect(Collectors.toList());
        double total = ps.stream().mapToDouble(p -> p.amount).sum();
        String label = monthly ? aMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + aYear : String.valueOf(aYear);
        String range = monthly ? aMonth.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " 1\u2013" + to.getDayOfMonth() + ", " + aYear : "Jan 1\u2013Dec 31, " + aYear;

        JComponent sum = summary(Icons.Type.RECEIPT, "Actual Spending \u2014 " + label, Theme.peso(total), "Actual Paid",
                "Total recorded payments during the selected period.",
                chip(Icons.Type.CASH, "Recorded Payments: <b>" + ps.size() + "</b>"),
                chip(Icons.Type.CALENDAR, "Reporting Period: <b>" + range + "</b>"),
                chip(Icons.Type.CHECK_CIRCLE, "Recorded payments"));

        // by category
        Map<String, List<Payment>> byCat = new TreeMap<>();
        for (Payment p : ps) byCat.computeIfAbsent(p.sub.category, k -> new ArrayList<>()).add(p);
        DataTable ct = new DataTable(new String[]{"Category", "Recorded\nPayments", "Actual Paid"}, new float[]{2.2f, 1.3f, 1.5f},
                new int[]{DataTable.L, DataTable.C, DataTable.R}, false);
        byCat.entrySet().stream().sorted((a, b) -> Double.compare(sum(b.getValue()), sum(a.getValue())))
                .forEach(e -> ct.addRow(null, dotLabel(e.getKey()), center(String.valueOf(e.getValue().size())), Common.money(sum(e.getValue()), true)));
        if (byCat.isEmpty()) ct.setEmpty("No payments recorded in this period.");
        else ct.addTotalRow("Total", ps.size(), Theme.peso(total));

        // by subscription
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

        JComponent note = UI.note(Icons.Type.INFO, Theme.INFO_BG, Theme.PRIMARY, "<html><span style='color:#0B4FCF'>About Actual Spending</span></html>",
                "Actual paid is calculated directly from subscription payment entries recorded within the selected period. Upcoming renewals and pending member contributions are not counted.", 520);
        JPanel nw = UI.clear(new FlowLayout(FlowLayout.LEFT, 0, 0)); nw.add(note);
        return UI.vstack(16, sum, two, nw);
    }

    // ---------------------------------------------------------------- Estimate
    private JComponent estimateReport() {
        Store s = Store.get();
        boolean annual = "Annual Equivalent".equals(aEstimate);
        List<Subscription> act = s.active();
        double total = annual ? s.annualTotal() : s.monthlyTotal();
        long monthlyPlans = act.stream().filter(x -> x.cycle == Cycle.MONTHLY).count();
        String eq = annual ? "Annual Equivalent" : "Monthly Equivalent";

        JComponent sum = summary(Icons.Type.CLOCK, "Current Estimated " + (annual ? "Annual" : "Monthly") + " Cost", Theme.peso(total), null,
                "Based on current subscription prices and billing cycles.",
                chip(Icons.Type.LIST, "Active Subscriptions: <b>" + act.size() + "</b>"),
                chip(Icons.Type.CALENDAR, "Monthly Plans: <b>" + monthlyPlans + "</b>"),
                chip(Icons.Type.CALENDAR, "Yearly Plans: <b>" + (act.size() - monthlyPlans) + "</b>"),
                chip(Icons.Type.CASH, (annual ? "Monthly Equivalent: <b>" + Theme.peso(s.monthlyTotal()) : "Annual Equivalent: <b>" + Theme.peso(s.annualTotal())) + "</b>"));

        Map<String, List<Subscription>> byCat = new LinkedHashMap<>();
        for (Subscription x : act) byCat.computeIfAbsent(x.category, k -> new ArrayList<>()).add(x);
        DataTable ct = new DataTable(new String[]{"Category", "Active\nSubscriptions", eq}, new float[]{5f, 1.4f, 1.4f},
                new int[]{DataTable.L, DataTable.C, DataTable.R}, false);
        byCat.forEach((cat, l) -> ct.addRow(null, dotLabel(cat), center(String.valueOf(l.size())), Common.money(eqSum(l, annual), true)));
        if (byCat.isEmpty()) ct.setEmpty("No active subscriptions.");
        else ct.addTotalRow("Total", act.size(), Theme.peso(total));

        DataTable st = new DataTable(new String[]{"Subscription", "Category", "Current Price", "Billing Cycle", eq},
                new float[]{2.2f, 1.6f, 1.4f, 1.4f, 1.8f}, new int[]{DataTable.L, DataTable.L, DataTable.R, DataTable.C, DataTable.R}, false);
        for (Subscription x : act) {
            JLabel price = UI.label(Theme.peso(x.price), Theme.SMALL, Theme.TEXT_2);
            price.setHorizontalAlignment(SwingConstants.RIGHT);
            JLabel cyc = UI.label(x.cycle.label, Theme.SMALL, Theme.MUTED);
            cyc.setHorizontalAlignment(SwingConstants.CENTER);
            st.addRow(() -> App.get().openDetails(x, 0), Common.subCell(x, true), UI.label(x.category, Theme.SMALL, Theme.MUTED), price, cyc,
                    Common.money(annual ? x.annualEquivalent() : x.monthlyEquivalent(), true));
        }
        if (act.isEmpty()) st.setEmpty("No active subscriptions.");
        else st.addTotalRow("Total " + eq, "", "", "", Theme.peso(total));

        JComponent note = UI.note(Icons.Type.INFO, Theme.INFO_BG, Theme.PRIMARY, "<html><span style='color:#0B4FCF'>About Recurring Cost Estimates</span></html>",
                "Estimates are based on current active subscription prices. Yearly plans are divided by 12 for monthly equivalents. These figures are not actual recorded payments.", 760);
        return UI.vstack(16, sum,
                tableCard("Estimated Cost by Category", byCat.size() + (byCat.size() == 1 ? " Category" : " Categories"),
                        (annual ? "Annual" : "Monthly") + " equivalent of active subscription costs grouped by category.", ct, null),
                tableCard("Estimated Cost by Subscription", act.size() + " Subscriptions",
                        "Current subscription prices converted to a common " + (annual ? "annual" : "monthly") + " equivalent.", st,
                        footerHint("Click any subscription to view subscription details.")),
                note);
    }

    private static double sum(List<Payment> l) { return l.stream().mapToDouble(p -> p.amount).sum(); }
    private static double eqSum(List<Subscription> l, boolean annual) {
        return l.stream().mapToDouble(x -> annual ? x.annualEquivalent() : x.monthlyEquivalent()).sum();
    }
    private static JLabel center(String t) {
        JLabel l = UI.label(t, Theme.BODY, Theme.MUTED);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        return l;
    }
}
