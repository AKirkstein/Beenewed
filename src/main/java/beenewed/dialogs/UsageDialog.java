package beenewed.dialogs;

import beenewed.model.Models;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import java.time.LocalDate;

/** D5 \u2014 Record or edit a manual usage entry. */
public class UsageDialog extends BaseDialog {
    private final Subscription sub;
    private final Usage editing;
    private final DateField date = new DateField("mm/dd/yyyy");
    private final UI.TextField qty = new UI.TextField("0");
    private final UI.Combo<String> unit;
    private final UI.TextArea notes = new UI.TextArea("Optional notes or description of usage...", 3);
    private final Field fDate, fQty, fNotes;

    public UsageDialog(JFrame owner, Subscription sub, Usage u) {
        super(owner, u == null ? "Record Usage" : "Edit Usage",
                u == null ? "Add a manual usage entry for this subscription." : "Update this usage entry.",
                null, "Save Usage", Icons.Type.CHECK, 536);
        this.sub = sub; this.editing = u;
        String[] units = new String[Models.USAGE_UNITS.length + 1];
        units[0] = "Select unit";
        System.arraycopy(Models.USAGE_UNITS, 0, units, 1, Models.USAGE_UNITS.length);
        unit = new UI.Combo<>(units);

        place(linkedContext(sub, true)); gap(14);
        fDate = new Field("Usage Date", true, null, date, "Date on which the subscription was used.");
        place(fDate); gap(10);
        fQty = new Field("Quantity", false, "(Optional)", qty, null);
        Field fUnit = new Field("Unit", false, null, unit, null);
        place(pair(fQty, fUnit)); gap(5);
        JLabel hint = UI.label("Enter a quantity and unit, or describe your usage in Notes.", Theme.SMALL, Theme.MUTED);
        place(hint); gap(12);
        fNotes = new Field(fieldLabel("Notes", false, null, "Optional if quantity is provided"), notes, "Optional descriptive note explaining your activity.");
        place(fNotes); gap(12);
        place(infoNote("<b>Note:</b> Usage entries are recorded manually. Review your usage history anytime to help determine if this subscription remains valuable.", true));

        if (u != null) {
            date.setDate(u.date);
            if (u.quantity != null) qty.setText(u.quantity == Math.floor(u.quantity) ? String.valueOf(u.quantity.longValue()) : String.valueOf(u.quantity));
            if (u.unit != null) unit.setSelectedItem(u.unit);
            notes.setText(u.notes == null ? "" : u.notes);
            addDelete("usage entry", () -> Store.get().deleteUsage(u));
        } else {
            date.setDate(Store.today());
        }
    }

    @Override protected boolean save() {
        boolean ok = true;
        LocalDate d = date.getDate();
        fDate.error(date.isBlank() ? "Usage date is required." : d == null ? "Enter a valid date (mm/dd/yyyy)." : null);
        ok &= d != null;

        Double q = null;
        String qt = qty.getText().trim();
        if (!qt.isEmpty()) {
            try { q = Double.parseDouble(qt); if (q <= 0) q = null; } catch (NumberFormatException ignored) {}
            fQty.error(q == null ? "Enter a number greater than zero." : null);
            ok &= q != null;
        } else fQty.error(null);
        String u = unit.getSelectedIndex() <= 0 ? null : unit.selected();
        String n = notes.getText().trim();
        if (q == null && qt.isEmpty() && n.isEmpty()) {
            fNotes.error("Enter a quantity or describe your usage.");
            ok = false;
        } else fNotes.error(null);
        if (!ok) return false;

        Usage updated = editing == null
                ? new Usage(0, sub, d, q, q == null ? null : u, n.isEmpty() ? null : n)
                : editing;
        updated.date = d;
        updated.quantity = q;
        updated.unit = q == null ? null : u;
        updated.notes = n.isEmpty() ? null : n;
        Store st = Store.get();
        if (editing == null) st.addUsage(updated);
        else st.updateUsage(updated);
        return true;
    }
}
