package beenewed.dialogs;

import beenewed.model.BillingRules;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** D6 \u2014 Record a price change (updates the current price and adds a Price History entry). */
public class PriceChangeDialog extends BaseDialog {
    private final Subscription sub;
    private final UI.PrefixField previous = new UI.PrefixField("\u20B1", "");
    private final UI.PrefixField newPrice = new UI.PrefixField("\u20B1", "0.00");
    private final DateField date = new DateField("mm/dd/yyyy");
    private final UI.TextArea notes = new UI.TextArea("Reason for price change (optional)...", 3);
    private final Field fNew, fDate;

    public PriceChangeDialog(JFrame owner, Subscription sub) {
        super(owner, "Record Price Change", "Record a change to the subscription's current price.",
                null, "Save Price Change", Icons.Type.CHECK, 560);
        this.sub = sub;

        place(linkedContext(sub, true)); gap(14);
        previous.setText(BillingRules.money(sub.price).toPlainString());
        previous.setEnabled(false);
        Field fPrev = new Field("Previous Price", false, null, previous, "Current recorded price before this change.");
        fNew = new Field("New Price", true, null, newPrice, "Enter the updated subscription price.");
        place(pair(fPrev, fNew)); gap(10);
        fDate = new Field("Effective Date", true, null, date, "Price changes are effective on the date they are recorded.");
        place(fDate); gap(10);
        place(new Field(fieldLabel("Notes / Reason", false, "(Optional)", null), notes, "Optional descriptive note for future reference in Price History."));
        gap(12);
        place(infoNote("<b>About Price Changes:</b> Recording a price change updates the subscription\u2019s current price without modifying previously recorded payments.", true));
        date.setDate(Store.today());
    }

    @Override protected Runnable prepareSave() {
        boolean ok = true;
        BigDecimal p = parsePositiveAmount(newPrice.getText());
        String err = p == null
                ? "Enter a valid positive amount (e.g., 899.00)."
                : p.compareTo(BillingRules.money(sub.price)) == 0
                        ? "The new price is the same as the current price."
                        : null;
        fNew.error(err);
        ok &= err == null;
        LocalDate d = date.getDate();
        fDate.error(date.isBlank() ? "Effective date is required." : d == null ? "Enter a valid date (MM/dd/yyyy or yyyy-MM-dd)." : null);
        ok &= d != null;
        if (!ok) { refit(); return null; }
        String n = notes.getText().trim();
        String reason = n.isEmpty() ? null : n;
        double newPriceVal = p.doubleValue();
        return () -> Store.get().recordPriceChange(sub, d, newPriceVal, reason);
    }
}
