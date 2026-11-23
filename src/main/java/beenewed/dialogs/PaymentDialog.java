package beenewed.dialogs;

import beenewed.model.BillingRules;
import beenewed.model.Models;
import beenewed.model.Models.*;
import beenewed.model.SplitAllocator;
import beenewed.model.Store;
import beenewed.ui.*;
import com.beenewed.backend.exception.ValidationException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D2 — Record a new payment (immutable ledger) or inspect a recorded payment receipt read-only.
 */
public class PaymentDialog extends BaseDialog {
    private final boolean readOnly;
    private final UI.Combo<Subscription> sub;
    private final DateField date = new DateField("Select payment date");
    private final UI.PrefixField amount = new UI.PrefixField("\u20B1", "0.00");
    private final UI.Combo<String> period = new UI.Combo<>();
    private final UI.Combo<String> method = new UI.Combo<>(Models.PAYMENT_METHODS);
    private final UI.Combo<ScopeOption> scopeCombo = new UI.Combo<>();
    private final UI.Combo<SplitAllocator.Participant> payerCombo = new UI.Combo<>();
    private final UI.TextArea notes = new UI.TextArea("Add an optional note about this payment (e.g. Paid via GCash, receipt ref)...", 3);
    private final Field fSub, fDate, fAmount, fPeriod;
    private final JPanel sharedRowWrap = new JPanel(new BorderLayout()) {
        { setOpaque(false); }
        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    };
    private boolean updatingControls;

    private record ScopeOption(String label, BigDecimal amount, boolean splitShareOnly) {
        @Override public String toString() { return label; }
    }

    /** Opens the dialog to record a new payment (immutable once saved). */
    public PaymentDialog(JFrame owner, Subscription preset) {
        this(owner, null, preset);
    }

    /** Opens a read-only receipt view for an already recorded payment. */
    public static PaymentDialog viewReceipt(JFrame owner, Payment payment) {
        return new PaymentDialog(owner, payment, payment.sub);
    }

    public PaymentDialog(JFrame owner, Payment existing, Subscription preset) {
        super(owner,
                existing == null ? "Record Payment" : "Payment Receipt",
                existing == null
                        ? "Record a completed payment. Recorded payments are immutable and advance the next due date."
                        : "Read-only ledger record. Recorded payments cannot be edited after creation.",
                existing == null ? Icons.Type.RECEIPT : Icons.Type.LOCK,
                existing == null ? "Save Payment" : "Close",
                existing == null ? Icons.Type.CHECK : null,
                600);
        this.readOnly = existing != null;
        Store st = Store.get();

        List<Subscription> subChoices = readOnly
                ? st.subscriptions
                : st.subscriptions.stream()
                        .filter(s -> s.status != Status.CANCELLED || (preset != null && s.id == preset.id))
                        .toList();
        sub = new UI.Combo<>(subChoices.toArray(new Subscription[0]));
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
        payerCombo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object v, int idx, boolean sel, boolean f) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, v, idx, sel, f);
                l.setBorder(new EmptyBorder(5, 8, 5, 8));
                if (v instanceof SplitAllocator.Participant p) {
                    l.setText(p.name());
                }
                l.setFont(Theme.BODY);
                if (idx == -1) l.setOpaque(false);
                else { l.setBackground(sel ? Theme.PRIMARY_LIGHT : Theme.ELEVATED); l.setForeground(Theme.TEXT); }
                return l;
            }
        });

        fSub = new Field("Subscription", true, null, sub, null);
        place(fSub); gap(10);

        Field fScope = new Field("Payment Amount Basis", false, null, scopeCombo, "Match full price or a designated split share.");
        Field fPayer = new Field("Paid By (Group Head / Payer)", false, null, payerCombo, "Who paid this renewal bill.");
        JPanel sharedInner = UI.vstack(0, pair(fScope, fPayer), Box.createVerticalStrut(10));
        sharedRowWrap.add(sharedInner, BorderLayout.CENTER);
        sharedRowWrap.setVisible(false);
        place(sharedRowWrap);

        fDate = new Field("Payment Date", true, null, date, "The date the payment was completed (cannot be in the future).");
        fAmount = new Field("Amount Paid", true, null, amount, "Must match the subscription price or designated split share.");
        place(pair(fDate, fAmount)); gap(10);
        period.setEditable(false);
        period.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object v, int idx, boolean sel, boolean f) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, v, idx, sel, f);
                l.setFont(Theme.BODY);
                String text = v == null ? "" : v.toString();
                if (idx == -1) {
                    l.setBorder(new EmptyBorder(4, 6, 4, 2));
                    l.setOpaque(false);
                    l.setForeground(period.isEnabled() ? Theme.TEXT : Theme.MUTED);
                    l.setText(text);
                } else if (readOnly || idx == 0) {
                    l.setBorder(new EmptyBorder(6, 10, 6, 12));
                    l.setBackground(sel ? Theme.PRIMARY_LIGHT : Theme.ELEVATED);
                    l.setForeground(sel ? Theme.ACCENT : Theme.TEXT);
                    if (!readOnly && !text.isEmpty()) {
                        l.setText("<html>" + escapeHtml(text)
                                + " &nbsp;<span style='color:" + Theme.hex(Theme.ACCENT) + "'>(Next due)</span></html>");
                    } else {
                        l.setText(text);
                    }
                } else {
                    l.setBorder(new EmptyBorder(6, 10, 6, 12));
                    l.setBackground(sel ? Theme.SUBTLE : Theme.ELEVATED);
                    l.setForeground(Theme.MUTED);
                    l.setText("<html>" + escapeHtml(text)
                            + " &nbsp;<span style='color:" + Theme.hex(Theme.MUTED) + "'>(Upcoming)</span></html>");
                }
                return l;
            }
        });
        period.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                Object child = period.getUI().getAccessibleChild(period, 0);
                if (child instanceof JPopupMenu popup && popup.getComponentCount() > 0
                        && popup.getComponent(0) instanceof JScrollPane sp
                        && sp.getViewport().getView() instanceof JList<?> list) {
                    int maxItemWidth = period.getWidth();
                    ListCellRenderer<? super String> r = period.getRenderer();
                    for (int i = 0; i < period.getItemCount(); i++) {
                        @SuppressWarnings("unchecked")
                        Component c = r.getListCellRendererComponent(
                                (JList<String>) list, period.getItemAt(i), i, false, false);
                        maxItemWidth = Math.max(maxItemWidth, c.getPreferredSize().width + 24);
                    }
                    Dimension pref = sp.getPreferredSize();
                    sp.setPreferredSize(new Dimension(maxItemWidth, pref.height));
                    sp.setMaximumSize(new Dimension(maxItemWidth, pref.height));
                }
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        });
        fPeriod = new Field("Renewal Period", true, null, period,
                readOnly
                        ? "Recorded renewal period for this receipt (read-only)."
                        : "Sequential unpaid periods (pay ahead one cycle at a time).");
        Field fMethod = new Field("Payment Method", false, "(Optional)", method, " ");
        place(pair(fPeriod, fMethod)); gap(10);
        place(new Field("Notes / Reference", false, "(Optional)", notes, null)); gap(12);

        if (readOnly) {
            place(infoNote("<b style='color:" + Theme.hex(Theme.TEXT)
                    + "'>Read-Only Ledger:</b> Recorded payment entries are immutable to preserve financial history and audit integrity.", true));
        } else {
            place(infoNote("<b style='color:" + Theme.hex(Theme.TEXT)
                    + "'>Ledger &amp; Due Dates:</b> Recording a payment creates an immutable ledger entry, advances the subscription\u2019s next due date to the cycle after the paid period, and unlocks the next sequential period for paying ahead.", false));
        }

        sub.addActionListener(e -> {
            if (!readOnly && !updatingControls) {
                syncFromSelectedSubscription();
            }
        });
        period.addActionListener(e -> {
            if (!readOnly && !updatingControls) {
                validateSelectedPeriodInline();
            }
        });
        scopeCombo.addActionListener(e -> {
            if (!readOnly && !updatingControls && scopeCombo.selected() != null) {
                ScopeOption opt = scopeCombo.selected();
                amount.setText(opt.amount().toPlainString());
                fAmount.error(null);
            }
        });

        if (existing != null) {
            sub.setSelectedItem(existing.sub);
            populateSharedControls(existing.sub, existing);
            date.setDate(existing.date);
            amount.setText(Theme.num(existing.amount).replace(",", ""));
            String recordedPeriod = existing.period == null ? "" : existing.period;
            period.removeAllItems();
            period.addItem(recordedPeriod);
            period.setSelectedItem(recordedPeriod);
            method.setSelectedItem(existing.method == null ? "Not specified" : existing.method);
            String clean = SplitAllocator.cleanNotes(existing.notes);
            notes.setText(clean == null ? "" : clean);

            // Enforce strict read-only ledger inspection (no edits, no active caret, no calendar popup)
            sub.setEnabled(false);
            sub.setFocusable(false);
            date.setEnabled(false);
            amount.setEnabled(false);
            amount.field.setFocusable(false);
            period.setEditable(false);
            period.setEnabled(false);
            period.setFocusable(false);
            method.setEnabled(false);
            method.setFocusable(false);
            scopeCombo.setEnabled(false);
            scopeCombo.setFocusable(false);
            payerCombo.setEnabled(false);
            payerCombo.setFocusable(false);
            notes.setEditable(false);
            notes.setEnabled(false);
            notes.setFocusable(false);
            notes.setDisabledTextColor(Theme.MUTED);
            primaryBtn.setVisible(false);
            cancelBtn.setText("Close");
            getRootPane().setDefaultButton(cancelBtn);
            SwingUtilities.invokeLater(cancelBtn::requestFocusInWindow);
        } else {
            if (preset != null) sub.setSelectedItem(preset);
            else if (sub.getItemCount() > 0) sub.setSelectedIndex(0);
            date.setMaxDate(Store.today());
            date.setDate(Store.today());
            syncFromSelectedSubscription();
        }
    }

    private void syncFromSelectedSubscription() {
        Subscription s = sub.selected();
        if (s == null) {
            updatingControls = true;
            try {
                period.removeAllItems();
            } finally {
                updatingControls = false;
            }
            sharedRowWrap.setVisible(false);
            return;
        }
        updatingControls = true;
        try {
            amount.setText(BillingRules.money(s.price).toPlainString());
            List<BillingRules.PeriodOption> upcoming = Store.get().upcomingUnpaidPeriods(s);
            period.removeAllItems();
            for (BillingRules.PeriodOption opt : upcoming) {
                period.addItem(opt.label());
            }
            if (period.getItemCount() > 0) {
                period.setSelectedIndex(0);
                fPeriod.error(null);
            } else {
                fPeriod.error("No upcoming unpaid renewal periods available for " + s.name + ".");
            }
            populateSharedControls(s, null);
            fSub.error(null);
            fAmount.error(null);
        } finally {
            updatingControls = false;
        }
        if (isVisible()) {
            refit();
        }
    }

    private void validateSelectedPeriodInline() {
        Subscription selectedSub = sub.selected();
        String chosen = period.selected();
        if (selectedSub == null || chosen == null) {
            return;
        }
        try {
            BillingRules.validateSequentialPeriod(selectedSub, chosen, Store.get().payments);
            fPeriod.error(null);
        } catch (ValidationException ex) {
            fPeriod.error(ex.getMessage());
        }
        if (isVisible()) {
            refit();
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void populateSharedControls(Subscription s, Payment existing) {
        Store st = Store.get();
        List<Member> members = st.membersFor(s);
        scopeCombo.removeAllItems();
        payerCombo.removeAllItems();
        if (members.isEmpty()) {
            sharedRowWrap.setVisible(false);
            return;
        }

        SplitAllocator.SplitConfig config = st.splitConfigFor(s);
        BigDecimal fullPrice = BillingRules.money(s.price);
        scopeCombo.addItem(new ScopeOption("Full Price (" + Theme.peso(s.price) + ")", fullPrice, false));

        Set<BigDecimal> shares = SplitAllocator.allowedSplitShares(s, members);
        BigDecimal ownerShare = SplitAllocator.ownerSplitShare(s, members);
        if (ownerShare != null && ownerShare.compareTo(fullPrice) != 0) {
            scopeCombo.addItem(new ScopeOption("My Split Share (" + Theme.peso(ownerShare.doubleValue()) + ")", ownerShare, true));
        }
        for (BigDecimal share : shares) {
            if (share.compareTo(fullPrice) == 0) continue;
            if (ownerShare != null && share.compareTo(ownerShare) == 0) continue;
            scopeCombo.addItem(new ScopeOption("Designated Split Share (" + Theme.peso(share.doubleValue()) + ")", share, true));
        }

        String targetPayerKey = config.payerKey();
        if (existing != null) {
            SplitAllocator.PaymentSplitMeta meta = SplitAllocator.parsePaymentMeta(existing.notes);
            if (meta.payerKey() != null) {
                targetPayerKey = meta.payerKey();
            }
            BigDecimal existingAmt = BillingRules.money(existing.amount);
            for (int i = 0; i < scopeCombo.getItemCount(); i++) {
                ScopeOption item = scopeCombo.getItemAt(i);
                if (item.amount().compareTo(existingAmt) == 0) {
                    scopeCombo.setSelectedIndex(i);
                    break;
                }
            }
        }

        List<SplitAllocator.Participant> participants = SplitAllocator.buildParticipants(members, config.includeOwner());
        int payerIndex = 0;
        for (int i = 0; i < participants.size(); i++) {
            SplitAllocator.Participant p = participants.get(i);
            payerCombo.addItem(p);
            if (p.key().equals(targetPayerKey)) {
                payerIndex = i;
            }
        }
        if (payerCombo.getItemCount() > 0) {
            payerCombo.setSelectedIndex(payerIndex);
        }
        sharedRowWrap.setVisible(true);
        sharedRowWrap.revalidate();
    }

    @Override protected Runnable prepareSave() {
        if (readOnly) {
            close();
            return null;
        }
        Store st = Store.get();
        boolean ok = true;
        Subscription selectedSub = sub.selected();
        if (selectedSub == null) {
            fSub.error("Select a subscription.");
            ok = false;
        } else if (selectedSub.status == Status.CANCELLED) {
            fSub.error("Cannot record a payment for a cancelled subscription.");
            ok = false;
        } else {
            fSub.error(null);
        }

        LocalDate d = date.getDate();
        if (date.isBlank()) {
            fDate.error("Payment date is required.");
            ok = false;
        } else if (d == null) {
            fDate.error("Enter a valid date (MM/dd/yyyy or yyyy-MM-dd).");
            ok = false;
        } else {
            try {
                BillingRules.validatePaymentDate(d, Store.today());
                fDate.error(null);
            } catch (ValidationException ex) {
                fDate.error(ex.getMessage());
                ok = false;
            }
        }

        BigDecimal a = parsePositiveAmount(amount.getText());
        if (a == null) {
            fAmount.error("Enter a valid positive amount (e.g., 899.00).");
            ok = false;
        } else if (selectedSub != null) {
            try {
                List<Member> members = st.membersFor(selectedSub);
                BillingRules.validatePaymentAmount(
                        a,
                        BillingRules.money(selectedSub.price),
                        SplitAllocator.allowedSplitShares(selectedSub, members));
                fAmount.error(null);
            } catch (ValidationException ex) {
                fAmount.error(ex.getMessage());
                ok = false;
            }
        } else {
            fAmount.error(null);
        }

        String per = period.selected() == null ? "" : period.selected().trim();
        if (per.isEmpty()) {
            fPeriod.error("Renewal period is required.");
            ok = false;
        } else if (selectedSub != null) {
            try {
                BillingRules.PeriodOption validated =
                        BillingRules.validateSequentialPeriod(selectedSub, per, st.payments);
                per = validated.label();
                fPeriod.error(null);
            } catch (ValidationException ex) {
                fPeriod.error(ex.getMessage());
                ok = false;
            }
        } else {
            fPeriod.error(null);
        }

        if (!ok) {
            refit();
            return null;
        }

        String userNote = notes.getText().trim();
        String finalNotes = userNote.isEmpty() ? null : userNote;

        List<Member> members = st.membersFor(selectedSub);
        if (!members.isEmpty()) {
            SplitAllocator.SplitConfig config = st.splitConfigFor(selectedSub);
            SplitAllocator.Participant chosenPayer = payerCombo.selected();
            String payerKey = chosenPayer != null ? chosenPayer.key() : config.payerKey();
            BigDecimal payAmt = a;
            BigDecimal subPrice = BillingRules.money(selectedSub.price);
            boolean splitOnly = payAmt.compareTo(subPrice) != 0;
            Map<String, BigDecimal> snapshot = new LinkedHashMap<>();
            if (!splitOnly) {
                for (SplitAllocator.ParticipantShare ps : config.shares()) {
                    snapshot.put(ps.participant().key(), ps.share());
                }
            }
            SplitAllocator.PaymentSplitMeta meta = new SplitAllocator.PaymentSplitMeta(payerKey, splitOnly, snapshot);
            finalNotes = SplitAllocator.encodePaymentNotes(finalNotes, meta);
        }

        Payment payload = new Payment(
                0,
                selectedSub,
                d,
                a.doubleValue(),
                per,
                method.selected(),
                finalNotes);
        return () -> Store.get().recordPayment(payload);
    }
}
