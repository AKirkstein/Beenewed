package beenewed.screens;

import beenewed.App;
import beenewed.dialogs.*;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/** S3 \u2014 Subscription Details with Overview, Payments, Sharing, Usage and Price History tabs. */
public class DetailsScreen extends JPanel {
    private Subscription sub;
    private int tab = 0;

    public DetailsScreen() { super(new BorderLayout()); setBackground(Theme.BG); }

    public void setSubscription(Subscription s, int tab) { this.sub = s; this.tab = tab; }
    public Subscription getSubscription() { return sub; }
    public int getTab() { return tab; }

    public void refresh() {
        removeAll();
        if (sub == null || !Store.get().subscriptions.contains(sub)) {
            // subscription was deleted -> go back to list
            SwingUtilities.invokeLater(() -> App.get().show(App.Screen.SUBSCRIPTIONS));
            return;
        }
        // Breadcrumb
        UI.Btn crumb = UI.link("Subscriptions");
        crumb.setFont(Theme.SMALL);
        crumb.addActionListener(e -> App.get().show(App.Screen.SUBSCRIPTIONS));
        JPanel breadcrumb = UI.hrow(5, crumb, UI.label("/", Theme.SMALL, Theme.MUTED), UI.label(sub.name, Theme.SMALL_B, Theme.TEXT));

        // Header card
        UI.RoundPanel head = UI.card(new BorderLayout(14, 0), 18);
        head.add(UI.iconBox(sub.icon, 46, 22), BorderLayout.WEST);
        JLabel name = UI.label(sub.name, Theme.H1, Theme.TEXT);
        JPanel nameRow = UI.hrow(10, name, UI.chip(sub.category), Common.statusBadge(sub.status));
        JPanel nameWrap = UI.clear(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints(); g.anchor = GridBagConstraints.WEST; g.weightx = 1;
        nameWrap.add(nameRow, g);
        head.add(nameWrap, BorderLayout.CENTER);
        UI.Btn edit = UI.secondary("Edit Subscription", Icons.Type.PENCIL);
        edit.addActionListener(e -> App.get().editSubscription(sub));
        UI.Btn status = UI.secondary("Change Status", Icons.Type.SWAP);
        status.addActionListener(e -> App.get().changeStatus(sub));
        JPanel actions = UI.clear(new GridBagLayout());
        actions.add(UI.hrow(8, edit, status));
        head.add(actions, BorderLayout.EAST);

        // Tabs
        UI.Tabs tabs = new UI.Tabs(true);
        tabs.add("Overview", null, null).add("Payments", null, null).add("Sharing", null, null).add("Usage", null, null).add("Price History", null, null);
        tabs.select(tab, false);
        tabs.onSelect(i -> { tab = i; refresh(); });
        UI.RoundPanel tabBar = new UI.RoundPanel(new BorderLayout(), 12, Theme.SURFACE, Theme.BORDER);
        tabBar.setBorder(new EmptyBorder(0, 18, 0, 18));
        tabBar.add(tabs, BorderLayout.WEST);

        JComponent content = switch (tab) {
            case 1 -> paymentsTab();
            case 2 -> sharingTab();
            case 3 -> usageTab();
            case 4 -> priceTab();
            default -> overviewTab();
        };

        JPanel body = UI.vstack(14, breadcrumb, head, UI.vstack(0, tabBar, Box.createVerticalStrut(6), content));
        add(Common.page(body), BorderLayout.CENTER);
        revalidate(); repaint();
    }

    // ---------------------------------------------------------------- Overview
    private JComponent overviewTab() {
        UI.RoundPanel info = UI.card(new BorderLayout(0, 16), 20);
        info.add(UI.split(Common.cardHeading(Icons.Type.RECEIPT, "Subscription Information", null), UI.caps("Properties")), BorderLayout.NORTH);
        JPanel grid = UI.clear(new GridLayout(3, 2, 24, 16));
        grid.add(prop("Subscription Name", UI.label(sub.name, Theme.BODY_B, Theme.TEXT)));
        grid.add(prop("Category", UI.label(sub.category, Theme.BODY, Theme.TEXT)));
        grid.add(prop("Current Price", UI.label("<html><b>" + Theme.peso(sub.price) + "</b> <span style='color:" + Theme.hex(Theme.MUTED) + ";font-size:90%'>" + sub.perLabel() + "</span></html>", Theme.BODY, Theme.TEXT)));
        String sched = sub.cycle == Cycle.MONTHLY ? "(Fixed recurring schedule)" : "(Billed once a year)";
        grid.add(prop("Billing Cycle", UI.label("<html>" + sub.cycle.label + " <span style='color:" + Theme.hex(Theme.MUTED) + ";font-size:90%'>" + sched + "</span></html>", Theme.BODY, Theme.TEXT)));
        JLabel date = UI.label(Theme.date(sub.nextRenewal), Theme.BODY_B, Theme.TEXT);
        date.setIcon(Icons.of(Icons.Type.CALENDAR, 14, Theme.ACCENT));
        grid.add(prop("Next Renewal Date", date));
        grid.add(prop("Current Status", Common.statusBadge(sub.status)));
        info.add(grid, BorderLayout.CENTER);

        UI.Btn go = UI.link("Go to Price History \u2192");
        go.addActionListener(e -> { tab = 4; refresh(); });
        UI.RoundPanel note = new UI.RoundPanel(new BorderLayout(10, 6), 10, Theme.SUBTLE, null);
        note.setBorder(new EmptyBorder(12, 14, 12, 14));
        JLabel ic = new JLabel(Icons.of(Icons.Type.INFO, 16, Theme.MUTED));
        ic.setVerticalAlignment(SwingConstants.TOP);
        note.add(ic, BorderLayout.WEST);
        note.add(UI.vstack(6, UI.wrap("To update the recurring subscription price while preserving historical records, use <b style='color:" + Theme.hex(Theme.TEXT) + "'>Record Price Change</b> under the Price History tab. Direct edits alter the label only.", 420, Theme.SMALL, Theme.TEXT_2), go), BorderLayout.CENTER);
        info.add(note, BorderLayout.SOUTH);

        // Latest payment card
        UI.RoundPanel latest = UI.card(new BorderLayout(0, 14), 18);
        JLabel dot = new JLabel(new Icon() {
            public void paintIcon(Component c, Graphics gg, int x, int y) { UI.aa((Graphics2D) gg); gg.setColor(Theme.GREEN); gg.fillOval(x, y, 7, 7); }
            public int getIconWidth() { return 7; }
            public int getIconHeight() { return 7; }
        });
        latest.add(UI.split(Common.cardHeading(Icons.Type.RECEIPT, "Latest Recorded Payment", null), dot), BorderLayout.NORTH);
        List<Payment> pays = Store.get().paymentsFor(sub);
        UI.Btn history = UI.soft("View Payment History \u2192", null);
        history.setForeground(Theme.TEXT);
        history.addActionListener(e -> { tab = 1; refresh(); });
        if (pays.isEmpty()) {
            latest.add(UI.wrap("No payments have been recorded for this subscription yet.", 200, Theme.SMALL, Theme.MUTED), BorderLayout.CENTER);
            UI.Btn rec = UI.primary("Record Payment", Icons.Type.PLUS);
            rec.addActionListener(e -> App.get().recordPayment(sub));
            latest.add(stretch(rec), BorderLayout.SOUTH);
        } else {
            Payment p = pays.get(0);
            UI.RoundPanel amt = new UI.RoundPanel(new BorderLayout(), 8, Theme.SUBTLE, null);
            amt.setBorder(new EmptyBorder(14, 14, 14, 14));
            amt.add(UI.vstack(6, UI.caps("Amount Paid"),
                    UI.label("<html><span style='font-size:20pt'><b>" + Theme.peso(p.amount) + "</b></span> <span style='color:" + Theme.hex(Theme.MUTED) + ";font-size:9pt'>PHP</span></html>", Theme.BODY, Theme.TEXT)));
            JPanel rows = UI.vstack(10, UI.split(UI.muted("Payment Date"), UI.label(Theme.date(p.date), Theme.BODY_B, Theme.TEXT)),
                    UI.split(UI.muted("Subscription"), UI.label(sub.name, Theme.BODY, Theme.TEXT)));
            latest.add(UI.vstack(14, amt, rows), BorderLayout.CENTER);
            latest.add(stretch(history), BorderLayout.SOUTH);
        }

        JPanel row = UI.clear(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH; c.anchor = GridBagConstraints.NORTH; c.weighty = 0;
        c.gridx = 0; c.weightx = 0.68; c.insets = new Insets(0, 0, 0, 7); row.add(info, c);
        c.gridx = 1; c.weightx = 0.32; c.insets = new Insets(0, 7, 0, 0);
        JPanel lw = UI.clear(new BorderLayout()); lw.add(latest, BorderLayout.NORTH);
        row.add(lw, c);
        return row;
    }

    private static JComponent stretch(JComponent c) {
        JPanel p = UI.clear(new BorderLayout()); p.add(c); return p;
    }

    private JComponent prop(String label, JComponent value) {
        JPanel v = UI.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        v.add(value);
        return UI.vstack(6, UI.label(label, Theme.SMALL, Theme.MUTED), v);
    }

    /** Standard tab card: heading + subtitle, primary action on the right. */
    private UI.RoundPanel tabCard(Icons.Type icon, String title, String subtitle, UI.Btn action) {
        UI.RoundPanel card = UI.card(new BorderLayout(0, 16), 20);
        card.add(UI.split(Common.cardHeading(icon, title, subtitle), action), BorderLayout.NORTH);
        return card;
    }

    // ---------------------------------------------------------------- Payments
    private JComponent paymentsTab() {
        UI.Btn rec = UI.primary("Record Payment", Icons.Type.PLUS);
        rec.addActionListener(e -> App.get().recordPayment(sub));
        UI.RoundPanel card = tabCard(Icons.Type.RECEIPT, "Payment History", "View and manage manually recorded payments for this subscription.", rec);
        DataTable t = new DataTable(new String[]{"Payment Date", "Amount Paid", "Renewal Period", "Payment Method", "Actions"},
                new float[]{2f, 1.6f, 1.8f, 2f, 1f}, new int[]{DataTable.L, DataTable.R, DataTable.L, DataTable.L, DataTable.R}, true);
        List<Payment> pays = Store.get().paymentsFor(sub);
        double total = 0;
        for (Payment p : pays) {
            UI.Btn edit = UI.link("Edit");
            edit.addActionListener(e -> App.get().editPayment(p));
            t.addRow(null, UI.label(Theme.date(p.date), Theme.BODY_B, Theme.TEXT), Common.money(p.amount, true),
                    blank(p.period), p.method == null ? "Not specified" : p.method, edit);
            total += p.amount;
        }
        if (pays.isEmpty()) t.setEmpty("No payments recorded yet. Use Record Payment to add one.");
        card.add(t, BorderLayout.CENTER);
        card.add(footer(pays.size() + (pays.size() == 1 ? " payment recorded" : " payments recorded"), "Total Recorded Payments:", Theme.peso(total), Theme.TEXT), BorderLayout.SOUTH);
        return card;
    }

    // ---------------------------------------------------------------- Sharing
    private JComponent sharingTab() {
        Store st = Store.get();
        // Members
        UI.Btn addMember = UI.primary("Add Member", Icons.Type.PLUS);
        addMember.addActionListener(e -> new MemberDialog(App.get(), sub, null).open());
        UI.RoundPanel members = UI.card(new BorderLayout(0, 14), 0);
        JComponent mh = UI.split(Common.tileHeading(Icons.Type.USERS, "Shared Members", "Manage people sharing this subscription."), addMember);
        ((JComponent) mh).setBorder(new EmptyBorder(16, 16, 0, 16));
        members.add(mh, BorderLayout.NORTH);
        DataTable mt = new DataTable(new String[]{"Member Name", "Role / Notes", "Actions"}, new float[]{2.5f, 3.5f, 1f},
                new int[]{DataTable.L, DataTable.L, DataTable.R}, false);
        List<Member> ms = st.membersFor(sub);
        for (Member m : ms) {
            UI.Btn edit = UI.link("Edit");
            edit.addActionListener(e -> new MemberDialog(App.get(), sub, m).open());
            mt.addRow(null, UI.label(m.name, Theme.BODY_B, Theme.TEXT), blank(m.notes), edit);
        }
        if (ms.isEmpty()) mt.setEmpty("No one is sharing this subscription yet.");
        mt.setFooter(Common.footerText(ms.size() + (ms.size() == 1 ? " member sharing" : " members sharing")), null);
        members.add(clipped(mt), BorderLayout.CENTER);

        // Contributions
        UI.Btn addContrib = UI.primary("Record Contribution", Icons.Type.PLUS);
        addContrib.addActionListener(e -> new ContributionDialog(App.get(), sub, null).open());
        UI.RoundPanel contrib = UI.card(new BorderLayout(0, 14), 0);
        JComponent ch = UI.split(Common.tileHeading(Icons.Type.CASH, "Member Contributions", "Track money received from members for recorded subscription payments."), addContrib);
        ch.setBorder(new EmptyBorder(16, 16, 0, 16));
        contrib.add(ch, BorderLayout.NORTH);
        DataTable ct = new DataTable(new String[]{"Date Received", "Member", "Linked Payment", "Amount Received", "Actions"},
                new float[]{1.6f, 1.6f, 2.8f, 1.8f, 0.9f}, new int[]{DataTable.L, DataTable.L, DataTable.L, DataTable.R, DataTable.R}, false);
        List<Contribution> cs = st.contributionsFor(sub);
        double total = 0;
        for (Contribution c : cs) {
            UI.Btn edit = UI.link("Edit");
            edit.addActionListener(e -> new ContributionDialog(App.get(), sub, c).open());
            ct.addRow(null, Theme.date(c.date), UI.label(c.member.name, Theme.BODY_B, Theme.TEXT),
                    c.payment == null ? "\u2014" : c.payment.toString(), Common.money(c.amount, true), edit);
            total += c.amount;
        }
        if (cs.isEmpty()) ct.setEmpty("No contributions recorded yet.");
        ct.setFooter(Common.footerText(cs.size() + (cs.size() == 1 ? " contribution recorded" : " contributions recorded")),
                Common.footerTotal("Total Contributions Received:", Theme.peso(total), Theme.ACCENT));
        contrib.add(clipped(ct), BorderLayout.CENTER);

        return UI.vstack(16, members, contrib);
    }

    /** Puts a table flush inside a card, keeping the card's rounded bottom corners. */
    private JComponent clipped(DataTable t) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override protected void paintChildren(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                UI.aa(g);
                java.awt.geom.Area clip = new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Double(0, -20, getWidth(), getHeight() + 20, 12, 12));
                g.clip(clip);
                super.paintChildren(g);
                g.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, 1, 1, 1));
        p.add(t);
        return p;
    }

    // ---------------------------------------------------------------- Usage
    private JComponent usageTab() {
        UI.Btn rec = UI.primary("Record Usage", Icons.Type.PLUS);
        rec.addActionListener(e -> new UsageDialog(App.get(), sub, null).open());
        UI.RoundPanel card = tabCard(Icons.Type.HISTORY, "Usage History", "Record and review how you use this subscription.", rec);
        DataTable t = new DataTable(new String[]{"Usage Date", "Quantity", "Unit", "Notes", "Actions"},
                new float[]{1.8f, 1.2f, 1.3f, 3.4f, 0.9f}, new int[]{DataTable.L, DataTable.R, DataTable.L, DataTable.L, DataTable.R}, false);
        List<Usage> us = Store.get().usagesFor(sub);
        for (Usage u : us) {
            UI.Btn edit = UI.link("Edit");
            edit.addActionListener(e -> new UsageDialog(App.get(), sub, u).open());
            String qty = u.quantity == null ? null : (u.quantity == Math.floor(u.quantity) ? String.valueOf(u.quantity.longValue()) : String.valueOf(u.quantity));
            t.addRow(null, UI.label(Theme.date(u.date), Theme.BODY, Theme.TEXT), blank(qty), blank(u.unit), blank(u.notes), edit);
        }
        if (us.isEmpty()) t.setEmpty("No usage recorded yet.");
        t.setFooter(Common.footerText(us.size() + (us.size() == 1 ? " usage entry recorded" : " usage entries recorded")), null);
        card.add(t, BorderLayout.CENTER);
        card.add(UI.note(Icons.Type.INFO, Theme.INFO_BG, Theme.ACCENT, "About Usage Tracking",
                "Usage entries are recorded manually. Review your history to help decide whether this subscription is still useful to you.", 640), BorderLayout.SOUTH);
        return card;
    }

    // ---------------------------------------------------------------- Price history
    private JComponent priceTab() {
        UI.Btn rec = UI.primary("Record Price Change", Icons.Type.PLUS);
        rec.addActionListener(e -> new PriceChangeDialog(App.get(), sub).open());
        UI.RoundPanel card = tabCard(Icons.Type.RECEIPT, "Price History", "Review previous subscription prices and record changes to the current price.", rec);
        List<PriceChange> pcs = Store.get().priceChangesFor(sub);

        UI.RoundPanel strip = new UI.RoundPanel(new GridLayout(1, 3, 0, 0), 8, Theme.SUBTLE, null);
        strip.setBorder(new EmptyBorder(14, 14, 14, 14));
        strip.add(UI.vstack(4, UI.muted("Current Price"), UI.label(Theme.peso(sub.price), Theme.BIG_NUM.deriveFont(21f), Theme.TEXT)));
        strip.add(UI.vstack(4, UI.muted("Billing Cycle"), UI.label(sub.cycle.label, Theme.BODY_B, Theme.TEXT)));
        strip.add(UI.vstack(4, UI.muted("Last Price Change"), UI.label(pcs.isEmpty() ? "\u2014" : Theme.date(pcs.get(0).date), Theme.BODY_B, Theme.TEXT)));

        DataTable t = new DataTable(new String[]{"Effective Date", "Previous Price", "New Price", "Notes / Reason"},
                new float[]{1.6f, 1.5f, 1.5f, 3f}, new int[]{DataTable.L, DataTable.R, DataTable.R, DataTable.L}, true);
        for (PriceChange p : pcs) {
            JLabel np = UI.label(Theme.peso(p.newPrice), Theme.BODY_B, Theme.ACCENT);
            np.setHorizontalAlignment(SwingConstants.RIGHT);
            t.addRow(null, UI.label(Theme.date(p.date), Theme.BODY_B, Theme.TEXT), Common.money(p.oldPrice, false), np, blank(p.notes));
        }
        if (pcs.isEmpty()) t.setEmpty("No price changes recorded yet.");

        JPanel center = UI.vstack(12, UI.caps("Current Subscription Price"), strip, t,
                footer(pcs.size() + (pcs.size() == 1 ? " price change recorded" : " price changes recorded"), null, null, null));
        card.add(center, BorderLayout.CENTER);

        JComponent note = UI.note(Icons.Type.INFO, Theme.INFO_BG, Theme.ACCENT, "About Price Changes",
                "Recording a price change updates the subscription\u2019s current price without modifying previously recorded payments.", 640);
        return UI.vstack(14, card, note);
    }

    // ---------------------------------------------------------------- helpers
    private static Object blank(String s) { return s == null || s.isBlank() ? "\u2014" : s; }

    private static JComponent footer(String left, String totalLabel, String total, Color color) {
        JPanel p = UI.clear(new BorderLayout());
        p.setBorder(new EmptyBorder(2, 2, 0, 2));
        p.add(Common.footerText(left), BorderLayout.WEST);
        if (totalLabel != null) p.add(Common.footerTotal(totalLabel, total, color), BorderLayout.EAST);
        return p;
    }
}
