package beenewed.screens;

import beenewed.App;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/** S4 \u2014 Renewals & Payments: Upcoming Renewals tab and Payment History tab. */
public class RenewalsScreen extends JPanel {
    private final UI.Tabs tabs = new UI.Tabs(true);
    private final JPanel content = UI.clear(new BorderLayout());

    private final UI.Combo<String> dueFilter = new UI.Combo<>("All Dates", "Next 7 Days", "Next 30 Days", "Overdue");
    private final UI.Combo<String> upStatus = new UI.Combo<>("Active", "Paused", "All Statuses");
    private final UI.Combo<String> payDate = new UI.Combo<>("All Dates", "This Month", "Last 30 Days", "This Year");
    private final UI.Combo<String> payStatus = new UI.Combo<>("All Statuses", "Active", "Paused", "Cancelled");
    private final UI.TextField paySearch = new UI.TextField("Search subscriptions...");

    public RenewalsScreen() {
        super(new BorderLayout());
        setBackground(Theme.BG);
        tabs.add("Upcoming Renewals", Icons.Type.CALENDAR, "0").add("Payment History", Icons.Type.HISTORY, null);
        tabs.onSelect(i -> refresh());
        for (UI.Combo<String> c : List.of(dueFilter, upStatus, payDate, payStatus)) {
            c.setPreferredSize(new Dimension(130, c.getPreferredSize().height));
            c.addActionListener(e -> refresh());
        }
        paySearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refreshTable(); }
            public void removeUpdate(DocumentEvent e) { refreshTable(); }
            public void changedUpdate(DocumentEvent e) { refreshTable(); }
        });
        JComponent header = Common.pageHeader("Renewals & Payments", "Monitor upcoming subscription renewals and manage recorded payments.", null);
        header.setBorder(new EmptyBorder(0, 0, 6, 0));
        add(Common.page(UI.vstack(16, header, tabs, content)), BorderLayout.CENTER);
    }

    public void selectTab(int i) { tabs.select(i, false); }

    private JComponent payTableHolder;

    public void refresh() {
        tabs.setCount(0, String.valueOf(Store.get().active().size()));
        content.removeAll();
        content.add(tabs.getSelected() == 0 ? upcomingCard() : historyCard(), BorderLayout.NORTH);
        content.revalidate(); content.repaint();
        tabs.repaint();
    }

    private void refreshTable() {
        if (payTableHolder == null) return;
        payTableHolder.removeAll();
        payTableHolder.add(historyTable());
        payTableHolder.revalidate(); payTableHolder.repaint();
    }

    private static JComponent labeled(String label, JComponent c) {
        return UI.hrow(8, UI.label(label, Theme.SMALL, Theme.TEXT_2), c);
    }

    // ---------------------------------------------------------------- Upcoming
    private JComponent upcomingCard() {
        Store st = Store.get();
        UI.RoundPanel card = UI.card(new BorderLayout(0, 16), 18);
        UI.Btn rem = UI.secondary("View Reminders", Icons.Type.BELL);
        rem.addActionListener(e -> App.get().showReminders());
        JPanel top = UI.vstack(14,
                UI.split(Common.cardHeading(null, "Upcoming Renewals", "Review scheduled renewal dates and expected subscription charges."), rem),
                UI.hrow(18, labeled("Due Date:", dueFilter), labeled("Status:", upStatus), UI.hglue()));
        card.add(top, BorderLayout.NORTH);

        String status = upStatus.selected();
        String due = dueFilter.selected();
        List<Subscription> list = st.byRenewal(st.subscriptions).stream()
                .filter(s -> status == null || status.startsWith("All") ? s.status != Status.CANCELLED : s.status.label.equals(status))
                .filter(s -> {
                    long d = Store.daysUntil(s.nextRenewal);
                    return switch (due == null ? "" : due) {
                        case "Next 7 Days" -> d >= 0 && d <= 7;
                        case "Next 30 Days" -> d >= 0 && d <= 30;
                        case "Overdue" -> d < 0;
                        default -> true;
                    };
                }).collect(Collectors.toList());

        DataTable t = new DataTable(new String[]{"Subscription", "Billing Cycle", "Next Renewal", "Expected\nAmount", "Due", "Actions"},
                new float[]{2.4f, 1.2f, 1.5f, 1.3f, 1.4f, 2.6f},
                new int[]{DataTable.L, DataTable.L, DataTable.L, DataTable.R, DataTable.L, DataTable.R}, true);
        for (Subscription s : list) {
            UI.Btn view = UI.link("View Details");
            view.setForeground(Theme.TEXT_2);
            view.setFont(Theme.SMALL);
            view.addActionListener(e -> App.get().openDetails(s, 0));
            UI.Btn pay = UI.primary("Record Payment", null).small();
            pay.addActionListener(e -> App.get().recordPayment(s));
            t.addRow(null, Common.subCell(s, false), UI.label(s.cycle.label, Theme.BODY, Theme.MUTED), UI.label(Theme.date(s.nextRenewal), Theme.BODY, Theme.TEXT),
                    Common.money(s.price, false), Common.dueBadge(s.nextRenewal), UI.hrow(12, view, pay));
        }
        if (list.isEmpty()) t.setEmpty("No renewals match the selected filters.");
        JLabel left = Common.footerText(list.size() + " scheduled renewal" + (list.size() == 1 ? "" : "s") + " found \u2022 Reference date: " + Theme.date(Store.today()));
        left.setIcon(Icons.of(Icons.Type.INFO, 13, Theme.MUTED));
        t.setFooter(left, Common.footerText("Recording a payment adds a completed transaction in Payment History."));
        card.add(t, BorderLayout.CENTER);
        payTableHolder = null;
        return card;
    }

    // ---------------------------------------------------------------- History
    private JComponent historyCard() {
        UI.RoundPanel card = UI.card(new BorderLayout(0, 16), 18);
        UI.Btn rec = UI.primary("Record Payment", Icons.Type.PLUS);
        rec.addActionListener(e -> App.get().recordPayment(null));

        paySearch.setBorder(new UI.FieldBorder(8, 34));
        JLayeredPane searchWrap = new JLayeredPane() {
            final JLabel icon = new JLabel(Icons.of(Icons.Type.SEARCH, 15, Theme.MUTED));
            { add(paySearch, JLayeredPane.DEFAULT_LAYER); add(icon, JLayeredPane.PALETTE_LAYER); }
            @Override public Dimension getPreferredSize() { return new Dimension(300, paySearch.getPreferredSize().height); }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            @Override public void doLayout() { paySearch.setBounds(0, 0, getWidth(), getHeight()); icon.setBounds(11, 0, 18, getHeight()); }
        };
        JPanel filters = UI.hrow(18, labeled("Payment Date:", payDate), labeled("Subscription Status:", payStatus), searchWrap);
        card.add(UI.vstack(14, UI.split(Common.cardHeading(null, "Payment History", "View and manage manually recorded payments across your subscriptions."), rec), filters), BorderLayout.NORTH);
        payTableHolder = UI.clear(new BorderLayout());
        payTableHolder.add(historyTable());
        card.add(payTableHolder, BorderLayout.CENTER);
        return card;
    }

    private JComponent historyTable() {
        LocalDate today = Store.today();
        String range = payDate.selected(), status = payStatus.selected();
        String q = paySearch.getText().trim().toLowerCase();
        List<Payment> list = Store.get().paymentsNewestFirst().stream()
                .filter(p -> switch (range == null ? "" : range) {
                    case "This Month" -> p.date.getYear() == today.getYear() && p.date.getMonth() == today.getMonth();
                    case "Last 30 Days" -> !p.date.isBefore(today.minusDays(30)) && !p.date.isAfter(today);
                    case "This Year" -> p.date.getYear() == today.getYear();
                    default -> true;
                })
                .filter(p -> status == null || status.startsWith("All") || p.sub.status.label.equals(status))
                .filter(p -> q.isEmpty() || p.sub.name.toLowerCase().contains(q))
                .collect(Collectors.toList());
        DataTable t = new DataTable(new String[]{"Subscription", "Payment Date", "Amount Paid", "Renewal Period", "Payment Method", "Actions"},
                new float[]{2.4f, 1.7f, 1.4f, 1.4f, 1.7f, 0.8f},
                new int[]{DataTable.L, DataTable.L, DataTable.R, DataTable.L, DataTable.L, DataTable.R}, true);
        double total = 0;
        for (Payment p : list) {
            UI.Btn edit = UI.link("Edit");
            edit.setForeground(Theme.TEXT_2);
            edit.addActionListener(e -> App.get().editPayment(p));
            t.addRow(null, Common.subCell(p.sub, false), UI.label(Theme.date(p.date), Theme.BODY, Theme.TEXT), Common.money(p.amount, false),
                    p.period == null || p.period.isBlank() ? "\u2014" : p.period, p.method == null ? "Not specified" : p.method, edit);
            total += p.amount;
        }
        if (list.isEmpty()) t.setEmpty("No payments match the selected filters.");
        JLabel left = Common.footerText(list.size() + " recorded payment" + (list.size() == 1 ? "" : "s") + " found");
        left.setIcon(Icons.of(Icons.Type.RECEIPT, 13, Theme.MUTED));
        t.setFooter(left, Common.footerTotal("Total Recorded Payments:", Theme.peso(total), Theme.TEXT));
        return t;
    }
}
