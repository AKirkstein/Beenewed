package beenewed.dialogs;

import beenewed.model.BillingRules;
import beenewed.model.Models.*;
import beenewed.model.SplitAllocator;
import beenewed.model.SplitAllocator.DistributionRow;
import beenewed.model.SplitAllocator.DistributionSummary;
import beenewed.model.SplitAllocator.Participant;
import beenewed.model.SplitAllocator.SettlementStatus;
import beenewed.model.SplitAllocator.SplitConfig;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** D4 \u2014 Record or edit money received from a participant (member or Account Owner), linked to a recorded payment. */
public class ContributionDialog extends BaseDialog {
    private final Subscription sub;
    private final Contribution editing;
    private final UI.Combo<Object> member;
    private final UI.Combo<Object> payment;
    private final UI.PrefixField amount = new UI.PrefixField("\u20B1", "0.00");
    private final DateField date = new DateField("Select date received");
    private final Field fMember, fPayment, fAmount, fDate;

    public ContributionDialog(JFrame owner, Subscription sub, Contribution c) {
        this(owner, sub, c, null, null, null);
    }

    /** Pre-fills member, linked payment, and exact pending share (used by Group Head "Mark Paid"). */
    public ContributionDialog(
            JFrame owner,
            Subscription sub,
            Member presetMember,
            Payment presetPayment,
            BigDecimal presetAmount) {
        this(owner, sub, null, presetMember == null ? null : Participant.ofMember(presetMember), presetPayment, presetAmount);
    }

    /** Pre-fills any participant (shared member or Account Owner), linked payment, and pending share. */
    public ContributionDialog(
            JFrame owner,
            Subscription sub,
            Participant presetParticipant,
            Payment presetPayment,
            BigDecimal presetAmount) {
        this(owner, sub, null, presetParticipant, presetPayment, presetAmount);
    }

    private ContributionDialog(
            JFrame owner,
            Subscription sub,
            Contribution c,
            Participant presetParticipant,
            Payment presetPayment,
            BigDecimal presetAmount) {
        super(owner, c == null ? "Record Contribution" : "Edit Contribution",
                "Record money received from a participant for an existing subscription payment.",
                Icons.Type.CASH, "Save Contribution", Icons.Type.CHECK, 560);
        this.sub = sub; this.editing = c;
        Store st = Store.get();

        List<Member> ms = st.membersFor(sub);
        SplitConfig splitConfig = st.splitConfigFor(sub);
        List<Participant> participants = ms.isEmpty()
                ? List.of()
                : SplitAllocator.buildParticipants(ms, splitConfig.includeOwner());

        member = new UI.Combo<>();
        member.addItem("Select member");
        participants.forEach(member::addItem);
        List<Payment> ps = st.paymentsFor(sub);
        payment = new UI.Combo<>();
        payment.addItem("Select recorded payment");
        ps.forEach(payment::addItem);

        place(linkedContext(sub, false)); gap(14);
        fMember = new Field("Participant", true, null, member, ms.isEmpty()
                ? "No members yet \u2014 add one in the Shared Members section first."
                : "Select a shared member or the Account Owner.");
        place(fMember); gap(10);
        fPayment = new Field("Linked Payment", true, null, payment, ps.isEmpty()
                ? "No recorded payments yet for " + sub.name + "."
                : "Contributions must link to an existing recorded payment for " + sub.name + ".");
        place(fPayment); gap(10);
        fAmount = new Field("Amount Received", true, null, amount, "Cannot exceed the participant's remaining assigned share.");
        fDate = new Field("Date Received", true, null, date, "Date the funds were received.");
        place(pair(fAmount, fDate)); gap(12);
        place(infoNote("<b style='color:" + Theme.hex(Theme.TEXT) + "'>Note:</b> Contributions represent received participant reimbursements and cannot exceed each participant\u2019s assigned split share for the selected payment.", false));

        if (c != null) {
            selectParticipantByKey(SplitAllocator.participantKey(c.member));
            payment.setSelectedItem(c.payment);
            amount.setText(Theme.num(c.amount).replace(",", ""));
            date.setDate(c.date);
            addDelete("contribution", () -> st.deleteContribution(c));
        } else {
            if (presetParticipant != null) selectParticipantByKey(presetParticipant.key());
            if (presetPayment != null) payment.setSelectedItem(presetPayment);
            if (presetAmount != null && presetAmount.signum() > 0) amount.setText(presetAmount.toPlainString());
            date.setDate(Store.today());
        }

        member.addActionListener(e -> updateShareHint());
        payment.addActionListener(e -> updateShareHint());
        updateShareHint();
    }

    private void selectParticipantByKey(String key) {
        if (key == null) return;
        for (int i = 0; i < member.getItemCount(); i++) {
            if (member.getItemAt(i) instanceof Participant p && p.key().equals(key)) {
                member.setSelectedIndex(i);
                return;
            }
        }
    }

    private void updateShareHint() {
        if (!(member.getSelectedItem() instanceof Participant p)
                || !(payment.getSelectedItem() instanceof Payment pay)) {
            fAmount.error(null);
            return;
        }
        ShareLimit limit = computeShareLimit(p, pay);
        if (limit == null) {
            fAmount.error("Selected participant is not included in this payment's split.");
        } else if (limit.isPayer()) {
            fAmount.error(p.name() + " is the designated payer for this payment.");
        } else if (limit.remaining().signum() <= 0) {
            fAmount.error(p.name() + " has already settled their full share (" + Theme.peso(limit.assigned().doubleValue()) + ").");
        } else {
            fAmount.error(null);
        }
    }

    private record ShareLimit(boolean isPayer, BigDecimal assigned, BigDecimal remaining) {}

    private ShareLimit computeShareLimit(Participant p, Payment pay) {
        Store st = Store.get();
        DistributionSummary summary = st.distributionFor(pay);
        DistributionRow targetRow = null;
        for (DistributionRow row : summary.rows()) {
            if (row.participant().key().equals(p.key())) {
                targetRow = row;
                break;
            }
        }
        if (targetRow == null) {
            return null;
        }
        if (targetRow.status() == SettlementStatus.PAYER || p.key().equals(summary.payerKey())) {
            return new ShareLimit(true, targetRow.expectedShare(), BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal alreadyPaid = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (Contribution existing : st.contributionsForPayment(pay.id)) {
            if (editing != null && existing.id == editing.id) continue;
            if (existing.member != null && SplitAllocator.participantKey(existing.member).equals(p.key())) {
                alreadyPaid = alreadyPaid.add(BillingRules.money(existing.amount));
            }
        }
        BigDecimal remaining = targetRow.expectedShare().subtract(alreadyPaid);
        if (remaining.signum() < 0) {
            remaining = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return new ShareLimit(false, targetRow.expectedShare(), remaining);
    }

    @Override protected Runnable prepareSave() {
        boolean ok = true;
        boolean hasMember = member.getSelectedItem() instanceof Participant;
        fMember.error(hasMember ? null : "Select a participant.");
        ok &= hasMember;
        boolean hasPayment = payment.getSelectedItem() instanceof Payment;
        fPayment.error(hasPayment ? null : "Select the recorded payment this contribution belongs to.");
        ok &= hasPayment;
        BigDecimal a = parsePositiveAmount(amount.getText());
        if (a == null) {
            fAmount.error("Enter a valid positive amount (e.g., 183.00).");
            ok = false;
        } else if (hasMember && hasPayment) {
            Participant p = (Participant) member.getSelectedItem();
            Payment pay = (Payment) payment.getSelectedItem();
            ShareLimit limit = computeShareLimit(p, pay);
            if (limit == null) {
                fAmount.error("Selected participant is not included in this payment's split.");
                ok = false;
            } else if (limit.isPayer()) {
                fAmount.error(p.name() + " is the designated payer for this payment and does not need a reimbursement contribution.");
                ok = false;
            } else if (limit.remaining().signum() <= 0) {
                fAmount.error(p.name() + " has already paid their full assigned share (" + Theme.peso(limit.assigned().doubleValue()) + ").");
                ok = false;
            } else if (a.compareTo(limit.remaining()) > 0) {
                fAmount.error("Amount exceeds " + p.name() + "'s remaining share ("
                        + Theme.peso(limit.remaining().doubleValue()) + " of " + Theme.peso(limit.assigned().doubleValue()) + ").");
                ok = false;
            } else {
                fAmount.error(null);
            }
        } else {
            fAmount.error(null);
        }
        LocalDate d = date.getDate();
        fDate.error(date.isBlank() ? "Date received is required." : d == null ? "Enter a valid date (MM/dd/yyyy or yyyy-MM-dd)." : null);
        ok &= d != null;
        if (!ok) { refit(); return null; }

        Participant p = (Participant) member.getSelectedItem();
        Payment pay = (Payment) payment.getSelectedItem();
        double amt = a.doubleValue();
        return () -> {
            Store st = Store.get();
            if (editing == null) {
                st.recordParticipantContribution(sub, p, pay, amt, d);
            } else {
                st.updateParticipantContribution(editing.id, sub, p, pay, amt, d);
            }
        };
    }
}
