package beenewed.dialogs;

import beenewed.model.Models;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;

/** D1 / D1-B / D1-C \u2014 Add or edit a subscription, with icon selector and validation. */
public class SubscriptionDialog extends BaseDialog {
    private final Subscription editing;
    private Icons.Type icon;
    private boolean iconCustom;

    private final JLabel iconPreview = new JLabel();
    private final JLabel iconName = UI.label("", Theme.BODY, Theme.TEXT);
    private final UI.Btn changeIcon = UI.secondary("Change Icon", Icons.Type.DESIGN).small();
    private final JPanel selector = UI.clear(new BorderLayout());
    private final JPanel iconGrid = UI.clear(new GridLayout(4, 4, 8, 8));
    private final JLabel categoryDefault = UI.label("", Theme.SMALL, Theme.MUTED);

    private final UI.TextField name = new UI.TextField("e.g., Netflix");
    private final UI.Combo<String> category;
    private final UI.Combo<Cycle> cycle = new UI.Combo<>(Cycle.values());
    private final UI.PrefixField price = new UI.PrefixField("\u20B1", "0.00");
    private final DateField renewal = new DateField("mm/dd/yyyy");

    private final Field fName, fCategory, fPrice, fDate;

    public SubscriptionDialog(JFrame owner, Subscription s) {
        super(owner, s == null ? "Add Subscription" : "Edit Subscription",
                s == null ? "Enter the details of your recurring subscription." : "Update the details of this subscription.",
                null, "Save Subscription", Icons.Type.CHECK, 640);
        editing = s;
        String[] cats = new String[Models.CATEGORIES.length + 1];
        cats[0] = "Select category";
        System.arraycopy(Models.CATEGORIES, 0, cats, 1, Models.CATEGORIES.length);
        category = new UI.Combo<>(cats);

        // ---- Icon section
        place(fieldLabel("Subscription Icon", false, "(Optional)", "Auto-suggested from category or choose custom"));
        gap(8);
        UI.RoundPanel current = new UI.RoundPanel(new BorderLayout(12, 0), 10, Theme.SUBTLE, Theme.BORDER);
        current.setBorder(new EmptyBorder(9, 10, 9, 10));
        UI.RoundPanel box = new UI.RoundPanel(new GridBagLayout(), 8, Color.WHITE, Theme.BORDER);
        box.setPreferredSize(new Dimension(32, 32));
        box.add(iconPreview);
        current.add(box, BorderLayout.WEST);
        current.add(UI.vstack(2, iconName, UI.label("Current Selection", Theme.SMALL, Theme.MUTED)), BorderLayout.CENTER);
        JPanel cw = UI.clear(new GridBagLayout()); cw.add(changeIcon);
        current.add(cw, BorderLayout.EAST);
        current.setMaximumSize(new Dimension(Integer.MAX_VALUE, current.getPreferredSize().height));
        place(current);
        changeIcon.addActionListener(e -> toggleSelector());

        UI.RoundPanel sel = new UI.RoundPanel(new BorderLayout(0, 10), 10, Color.WHITE, Theme.BORDER);
        sel.setBorder(new EmptyBorder(12, 12, 10, 12));
        JLabel selTitle = UI.label("Select Icon", Theme.SMALL_B, Theme.TEXT);
        selTitle.setIcon(Icons.of(Icons.Type.DASHBOARD, 13, Theme.TEXT_2));
        sel.add(UI.split(selTitle, UI.label("16 generic icons", Theme.TINY, Theme.MUTED)), BorderLayout.NORTH);
        sel.add(iconGrid, BorderLayout.CENTER);
        UI.Btn reset = UI.link("Reset to default");
        reset.addActionListener(e -> { iconCustom = false; setIcon(Models.defaultIcon(categoryValue())); });
        sel.add(UI.split(categoryDefault, reset), BorderLayout.SOUTH);
        selector.add(Box.createVerticalStrut(8), BorderLayout.NORTH);
        selector.add(sel, BorderLayout.CENTER);
        selector.setVisible(false);
        place(selector);
        gap(16);

        // ---- Fields
        fName = new Field("Subscription Name", true, null, name, null);
        place(fName); gap(10);
        fCategory = new Field("Category", true, null, category, null);
        Field fCycle = new Field("Billing Cycle", true, null, cycle, null);
        place(pair(fCategory, fCycle)); gap(10);
        fPrice = new Field("Subscription Price", true, null, price, "Amount in Philippine Peso (PHP)");
        fDate = new Field("Next Renewal Date", true, null, renewal, "e.g., " + Theme.date(Store.today().plusDays(7)));
        place(pair(fPrice, fDate)); gap(14);
        place(infoNote("<b style='color:#0F172A'>Note:</b> Once created, price updates should be logged via <b style='color:#0F172A'>Record Price Change</b> to maintain historical records.", false));

        category.addActionListener(e -> {
            if (!iconCustom) setIcon(Models.defaultIcon(categoryValue()));
            categoryDefault.setText("<html>Category default: <b style='color:#0F172A'>" + Icons.label(Models.defaultIcon(categoryValue())) + "</b></html>");
            if (category.getSelectedIndex() > 0) fCategory.error(null);
        });

        if (s != null) {
            name.setText(s.name);
            category.setSelectedItem(s.category);
            cycle.setSelectedItem(s.cycle);
            price.setText(Theme.num(s.price).replace(",", ""));
            renewal.setDate(s.nextRenewal);
            iconCustom = s.icon != Models.defaultIcon(s.category);
            setIcon(s.icon);
            // In edit mode the price is managed through Record Price Change
            addDelete("subscription", () -> Store.get().deleteSubscription(s));
        } else {
            setIcon(Icons.Type.TELEVISION);
        }
        categoryDefault.setText("<html>Category default: <b style='color:#0F172A'>" + Icons.label(Models.defaultIcon(categoryValue())) + "</b></html>");
        buildGrid();
    }

    private String categoryValue() { return category.getSelectedIndex() <= 0 ? null : category.selected(); }

    private void toggleSelector() {
        boolean show = !selector.isVisible();
        selector.setVisible(show);
        changeIcon.setText("Change Icon " + (show ? "\u02C4" : ""));
        if (isVisible()) refit();
    }

    private void setIcon(Icons.Type t) {
        icon = t;
        iconPreview.setIcon(Icons.of(t, 18, Theme.TEXT));
        iconName.setText(Icons.label(t));
        buildGrid();
    }

    private void buildGrid() {
        iconGrid.removeAll();
        for (Icons.Type t : Icons.SUBSCRIPTION_ICONS) {
            boolean selected = t == icon;
            UI.RoundPanel cell = new UI.RoundPanel(new FlowLayout(FlowLayout.LEFT, 8, 6), 6,
                    selected ? Theme.PRIMARY_LIGHT : Color.WHITE, selected ? Theme.PRIMARY : Theme.BORDER);
            JLabel l = UI.label(Icons.label(t), selected ? Theme.SMALL_B : Theme.SMALL, selected ? Theme.PRIMARY : Theme.TEXT_2);
            l.setIcon(Icons.of(t, 14, selected ? Theme.PRIMARY : Theme.TEXT_2));
            l.setIconTextGap(7);
            cell.add(l);
            cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            cell.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) { iconCustom = t != Models.defaultIcon(categoryValue()); setIcon(t); }
            });
            iconGrid.add(cell);
        }
        iconGrid.revalidate(); iconGrid.repaint();
    }

    @Override protected boolean save() {
        boolean ok = true;
        String n = name.getText().trim();
        fName.error(n.isEmpty() ? "Subscription name is required." : null);
        ok &= !n.isEmpty();
        fCategory.error(categoryValue() == null ? "Please select a category." : null);
        ok &= categoryValue() != null;
        Double p = parseAmount(price.getText());
        fPrice.error(p == null ? "Enter an amount greater than zero." : null);
        ok &= p != null;
        LocalDate d = renewal.getDate();
        fDate.error(renewal.isBlank() ? "Next renewal date is required." : d == null ? "Enter a valid date (mm/dd/yyyy)." : null);
        ok &= d != null;
        if (!ok) { body.revalidate(); body.repaint(); return false; }

        Store st = Store.get();
        if (editing == null) {
            st.subscriptions.add(new Subscription(st.newId(), n, categoryValue(), icon, p, cycle.selected(), d));
        } else {
            if (Math.abs(editing.price - p) > 0.001) {
                // keep history consistent: log the edit as a price change
                st.priceChanges.add(new PriceChange(st.newId(), editing, Store.today(), editing.price, p, "Edited via Edit Subscription"));
            }
            editing.name = n; editing.category = categoryValue(); editing.icon = icon;
            editing.price = p; editing.cycle = cycle.selected(); editing.nextRenewal = d;
        }
        return true;
    }
}
