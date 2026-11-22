package beenewed.dialogs;

import beenewed.model.Models;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDate;

/** D2 / D2-B \u2014 Record or edit a payment. */
public class PaymentDialog extends BaseDialog {
    private final Payment editing;
    private final UI.Combo<Subscription> sub;
    private final DateField date = new DateField("Select payment date");
    private final UI.PrefixField amount = new UI.PrefixField("\u20B1", "0.00");
    private final UI.TextField period = new UI.TextField("e.g. November 2026");
    private final UI.Combo<String> method = new UI.Combo<>(Models.PAYMENT_METHODS);
    private final UI.TextArea notes = new UI.TextArea("Add an optional note about this payment (e.g. Paid via GCash, receipt ref)...", 3);
    private final Field fSub, fDate, fAmount, fPeriod;

    public PaymentDialog(JFrame owner, Payment p, Subscription preset) {
        super(owner, p == null ? "Record Payment" : "Edit Payment",
                p == null ? "Record a completed payment for one of your subscriptions." : "Update the details of this recorded payment.",
                null, "Save Payment", null, 580);
        editing = p;
        Store st = Store.get();
        sub = new UI.Combo<>(st.subscriptions.toArray(new Subscription[0]));
        sub.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object v, int idx, boolean sel, boolean f) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, v, idx, sel, f);
                l.setBorder(new EmptyBorder(6, 8, 6, 8));
                if (v instanceof Subscription s) {
                    l.setText("<html>" + s.name + " &nbsp;<span style='color:" + Theme.hex(Theme.MUTED) + "'>(" + s.category + " \u2014 " + Theme.peso(s.price)
                            + (s.cycle == Cycle.MONTHLY ? "/mo" : "/yr") + ")</span></html>");
                    l.setIcon(Icons.of(s.icon, 15, Theme.TEXT_2));
                    l.setIconTextGap(8);
                }
                l.setFont(Theme.BODY);
                if (idx == -1) l.setOpaque(false);
                else { l.setBackground(sel ? Theme.PRIMARY_LIGHT : Theme.ELEVATED); l.setForeground(Theme.TEXT); }
                return l;
            }
        });

        fSub = new Field("Subscription", true, null, sub, null);
        place(fSub); gap(10);
        fDate = new Field("Payment Date", true, null, date, "The date the payment was actually completed.");
        fAmount = new Field("Amount Paid", true, null, amount, "Enter the actual amount paid.");
        place(pair(fDate, fAmount)); gap(10);
        fPeriod = new Field("Renewal Period", true, null, period, "Billing period associated with this payment.");
        Field fMethod = new Field("Payment Method", false, "(Optional)", method, " ");
        place(pair(fPeriod, fMethod)); gap(10);
        place(new Field("Notes / Reference", false, "(Optional)", notes, null)); gap(12);
        place(infoNote("<b style='color:" + Theme.hex(Theme.TEXT) + "'>Note:</b> Recording a payment logs an actual financial transaction in Payment History. It does not automatically advance the next renewal date.", false));

        // Prefill the amount with the subscription price for new payments
        sub.addActionListener(e -> {
            if (editing == null && sub.selected() != null) amount.setText(Theme.num(sub.selected().price).replace(",", ""));
        });

        if (p != null) {
            sub.setSelectedItem(p.sub);
            date.setDate(p.date);
            amount.setText(Theme.num(p.amount).replace(",", ""));
            period.setText(p.period == null ? "" : p.period);
            method.setSelectedItem(p.method == null ? "Not specified" : p.method);
            notes.setText(p.notes == null ? "" : p.notes);
            addDelete("payment", () -> { st.payments.remove(p); st.contributions.removeIf(c -> c.payment == p); });
        } else {
            if (preset != null) sub.setSelectedItem(preset);
            else if (!st.subscriptions.isEmpty()) sub.setSelectedIndex(0);
            if (sub.selected() != null) amount.setText(Theme.num(sub.selected().price).replace(",", ""));
            date.setDate(Store.today());
            if (sub.selected() != null) {
                LocalDate r = sub.selected().nextRenewal;
                period.setText(r.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + r.getYear());
            }
        }
    }

    @Override protected boolean save() {
        boolean ok = true;
        fSub.error(sub.selected() == null ? "Select a subscription." : null);
        ok &= sub.selected() != null;
        LocalDate d = date.getDate();
        fDate.error(date.isBlank() ? "Payment date is required." : d == null ? "Enter a valid date (mm/dd/yyyy)." : null);
        ok &= d != null;
        Double a = parseAmount(amount.getText());
        fAmount.error(a == null ? "Enter an amount greater than zero." : null);
        ok &= a != null;
        String per = period.getText().trim();
        fPeriod.error(per.isEmpty() ? "Renewal period is required." : null);
        ok &= !per.isEmpty();
        if (!ok) return false;

        String n = notes.getText().trim();
        if (editing == null) {
            Store st = Store.get();
            st.payments.add(new Payment(st.newId(), sub.selected(), d, a, per, method.selected(), n.isEmpty() ? null : n));
        } else {
            editing.sub = sub.selected(); editing.date = d; editing.amount = a;
            editing.period = per; editing.method = method.selected(); editing.notes = n.isEmpty() ? null : n;
        }
        return true;
    }
}
