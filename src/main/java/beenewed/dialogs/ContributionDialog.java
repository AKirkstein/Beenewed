package beenewed.dialogs;

import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import java.time.LocalDate;
import java.util.List;

/** D4 \u2014 Record or edit money received from a member, linked to a recorded payment. */
public class ContributionDialog extends BaseDialog {
    private final Subscription sub;
    private final Contribution editing;
    private final UI.Combo<Object> member;
    private final UI.Combo<Object> payment;
    private final UI.PrefixField amount = new UI.PrefixField("\u20B1", "0.00");
    private final DateField date = new DateField("Select date received");
    private final Field fMember, fPayment, fAmount, fDate;

    public ContributionDialog(JFrame owner, Subscription sub, Contribution c) {
        super(owner, c == null ? "Record Contribution" : "Edit Contribution",
                "Record money received from a member for an existing subscription payment.",
                Icons.Type.CASH, "Save Contribution", Icons.Type.CHECK, 560);
        this.sub = sub; this.editing = c;
        Store st = Store.get();

        List<Member> ms = st.membersFor(sub);
        member = new UI.Combo<>();
        member.addItem("Select member");
        ms.forEach(member::addItem);
        List<Payment> ps = st.paymentsFor(sub);
        payment = new UI.Combo<>();
        payment.addItem("Select recorded payment");
        ps.forEach(payment::addItem);

        place(linkedContext(sub, false)); gap(14);
        fMember = new Field("Member", true, null, member, ms.isEmpty()
                ? "No members yet \u2014 add one in the Shared Members section first."
                : "Select a member currently associated with this subscription.");
        place(fMember); gap(10);
        fPayment = new Field("Linked Payment", true, null, payment, ps.isEmpty()
                ? "No recorded payments yet for " + sub.name + "."
                : "Contributions must link to an existing recorded payment for " + sub.name + ".");
        place(fPayment); gap(10);
        fAmount = new Field("Amount Received", true, null, amount, "Actual amount received from member.");
        fDate = new Field("Date Received", true, null, date, "Date the funds were received.");
        place(pair(fAmount, fDate)); gap(12);
        place(infoNote("<b style='color:#0F172A'>Note:</b> Contributions represent received member reimbursements and are recorded separately from subscription payments. They do not increase actual spending or alter subscription prices.", false));

        if (c != null) {
            member.setSelectedItem(c.member);
            payment.setSelectedItem(c.payment);
            amount.setText(Theme.num(c.amount).replace(",", ""));
            date.setDate(c.date);
            addDelete("contribution", () -> st.deleteContribution(c));
        }
    }

    @Override protected boolean save() {
        boolean ok = true;
        boolean hasMember = member.getSelectedItem() instanceof Member;
        fMember.error(hasMember ? null : "Select a member.");
        ok &= hasMember;
        boolean hasPayment = payment.getSelectedItem() instanceof Payment;
        fPayment.error(hasPayment ? null : "Select the recorded payment this contribution belongs to.");
        ok &= hasPayment;
        Double a = parseAmount(amount.getText());
        fAmount.error(a == null ? "Enter an amount greater than zero." : null);
        ok &= a != null;
        LocalDate d = date.getDate();
        fDate.error(date.isBlank() ? "Date received is required." : d == null ? "Enter a valid date (mm/dd/yyyy)." : null);
        ok &= d != null;
        if (!ok) return false;

        Member m = (Member) member.getSelectedItem();
        Payment p = (Payment) payment.getSelectedItem();
        Contribution updated = editing == null
                ? new Contribution(0, sub, m, p, a, d)
                : editing;
        updated.member = m;
        updated.payment = p;
        updated.amount = a;
        updated.date = d;
        Store st = Store.get();
        if (editing == null) st.recordContribution(updated);
        else st.updateContribution(updated);
        return true;
    }
}
