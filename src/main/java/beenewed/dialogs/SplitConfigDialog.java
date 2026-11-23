package beenewed.dialogs;

import beenewed.model.BillingRules;
import beenewed.model.Models.Member;
import beenewed.model.Models.Subscription;
import beenewed.model.SplitAllocator;
import beenewed.model.SplitAllocator.Participant;
import beenewed.model.SplitAllocator.ParticipantShare;
import beenewed.model.SplitAllocator.SplitConfig;
import beenewed.model.SplitAllocator.SplitMode;
import beenewed.model.Store;
import beenewed.ui.*;
import com.beenewed.backend.exception.ValidationException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configures family/shared payment distribution for a subscription: designates who pays
 * (Group Head / Primary Payer), chooses Equal vs. Custom split, and validates exact total alignment.
 */
public class SplitConfigDialog extends BaseDialog {
    private final Subscription sub;
    private final List<Member> members;
    private final BigDecimal targetTotal;

    private final UI.Combo<Participant> payerCombo = new UI.Combo<>();
    private final JCheckBox includeOwnerBox = new JCheckBox("Include Account Owner (You) in the split");
    private final UI.Combo<SplitMode> modeCombo = new UI.Combo<>(SplitMode.values());
    private final JPanel rowsPanel = UI.vstack(8);
    private final UI.RoundPanel totalBanner = new UI.RoundPanel(new BorderLayout(8, 0), 8, Theme.SUBTLE, Theme.BORDER);
    private final JLabel totalIcon = new JLabel();
    private final JLabel totalLabel = UI.label("", Theme.SMALL_B, Theme.TEXT);

    private final Map<String, UI.PrefixField> customFields = new LinkedHashMap<>();
    private List<Participant> activeParticipants = List.of();
    private boolean buildingRows;

    public SplitConfigDialog(JFrame owner, Subscription sub) {
        super(owner,
                "Configure Family Split",
                "Designate who pays the subscription bill and split the recurring cost across family members.",
                Icons.Type.USERS,
                "Save Split Plan",
                Icons.Type.CHECK,
                580);
        this.sub = sub;
        this.members = Store.get().membersFor(sub);
        this.targetTotal = BillingRules.money(sub.price);
        SplitConfig current = Store.get().splitConfigFor(sub);

        place(linkedContext(sub, false)); gap(14);

        payerCombo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object v, int idx, boolean sel, boolean f) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, v, idx, sel, f);
                l.setBorder(new EmptyBorder(5, 8, 5, 8));
                if (v instanceof Participant p) l.setText(p.name());
                l.setFont(Theme.BODY);
                if (idx == -1) l.setOpaque(false);
                else { l.setBackground(sel ? Theme.PRIMARY_LIGHT : Theme.ELEVATED); l.setForeground(Theme.TEXT); }
                return l;
            }
        });

        includeOwnerBox.setSelected(current.includeOwner());
        includeOwnerBox.setOpaque(false);
        includeOwnerBox.setFont(Theme.BODY);
        includeOwnerBox.setForeground(Theme.TEXT);
        includeOwnerBox.setFocusPainted(false);

        modeCombo.setSelectedItem(current.mode());

        Field fPayer = new Field("Designated Payer (Group Head)", true, null, payerCombo,
                "The person who pays the provider and collects shares from others.");
        Field fMode = new Field("Split Mode", true, null, modeCombo,
                "Equal split distributes fractional cents cleanly.");
        place(pair(fPayer, fMode)); gap(10);

        JPanel ownerWrap = UI.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ownerWrap.add(includeOwnerBox);
        ownerWrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, ownerWrap.getPreferredSize().height));
        place(ownerWrap); gap(12);

        place(fieldLabel("Member Allocations", true, null, "Total must equal " + Theme.peso(sub.price)));
        gap(8);
        place(rowsPanel); gap(12);

        totalBanner.setBorder(new EmptyBorder(10, 12, 10, 12));
        totalBanner.add(totalIcon, BorderLayout.WEST);
        totalBanner.add(totalLabel, BorderLayout.CENTER);
        totalBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        place(totalBanner);

        Map<String, BigDecimal> initialShares = new LinkedHashMap<>();
        for (ParticipantShare ps : current.shares()) {
            initialShares.put(ps.participant().key(), ps.share());
        }

        rebuildParticipantsAndRows(current.payerKey(), initialShares);

        includeOwnerBox.addActionListener(e -> {
            Participant prevPayer = payerCombo.selected();
            rebuildParticipantsAndRows(prevPayer == null ? SplitAllocator.OWNER_KEY : prevPayer.key(), readCurrentInputs());
        });
        modeCombo.addActionListener(e -> {
            Participant prevPayer = payerCombo.selected();
            rebuildParticipantsAndRows(prevPayer == null ? SplitAllocator.OWNER_KEY : prevPayer.key(), readCurrentInputs());
        });
        payerCombo.addActionListener(e -> {
            if (!buildingRows) {
                updateTotalStatus();
            }
        });
    }

    private Map<String, BigDecimal> readCurrentInputs() {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Map.Entry<String, UI.PrefixField> entry : customFields.entrySet()) {
            BigDecimal val = parseNonNegative(entry.getValue().getText());
            if (val != null) {
                map.put(entry.getKey(), val);
            }
        }
        return map;
    }

    private void rebuildParticipantsAndRows(String preferredPayerKey, Map<String, BigDecimal> seedShares) {
        buildingRows = true;
        try {
            boolean includeOwner = includeOwnerBox.isSelected();
            activeParticipants = SplitAllocator.buildParticipants(members, includeOwner);

            payerCombo.removeAllItems();
            int selectedPayerIdx = 0;
            for (int i = 0; i < activeParticipants.size(); i++) {
                Participant p = activeParticipants.get(i);
                payerCombo.addItem(p);
                if (p.key().equals(preferredPayerKey)) {
                    selectedPayerIdx = i;
                }
            }
            if (payerCombo.getItemCount() > 0) {
                payerCombo.setSelectedIndex(selectedPayerIdx);
            }

            rowsPanel.removeAll();
            customFields.clear();
            SplitMode mode = modeCombo.selected();
            List<BigDecimal> equalShares = SplitAllocator.splitEqually(targetTotal, activeParticipants.size());

            DocumentListener recalc = new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { updateTotalStatus(); }
                public void removeUpdate(DocumentEvent e) { updateTotalStatus(); }
                public void changedUpdate(DocumentEvent e) { updateTotalStatus(); }
            };

            for (int i = 0; i < activeParticipants.size(); i++) {
                Participant p = activeParticipants.get(i);
                BigDecimal initial = (mode == SplitMode.CUSTOM && seedShares != null && seedShares.containsKey(p.key()))
                        ? seedShares.get(p.key())
                        : equalShares.get(i);

                UI.RoundPanel row = new UI.RoundPanel(new BorderLayout(12, 0), 8, Theme.SUBTLE, Theme.BORDER);
                row.setBorder(new EmptyBorder(8, 12, 8, 12));

                JLabel nameLbl = UI.label(p.name(), Theme.BODY_B, Theme.TEXT);
                if (p.owner()) {
                    nameLbl.setIcon(Icons.of(Icons.Type.USER, 14, Theme.ACCENT));
                    nameLbl.setIconTextGap(6);
                }
                row.add(nameLbl, BorderLayout.CENTER);

                if (mode == SplitMode.EQUAL) {
                    JLabel shareLbl = UI.label(Theme.peso(equalShares.get(i).doubleValue()), Theme.BODY_B, Theme.TEXT);
                    row.add(shareLbl, BorderLayout.EAST);
                } else {
                    UI.PrefixField pf = new UI.PrefixField("\u20B1", "0.00");
                    pf.setPreferredSize(new Dimension(130, pf.getPreferredSize().height));
                    pf.setText(BillingRules.money(initial).toPlainString());
                    pf.field.getDocument().addDocumentListener(recalc);
                    customFields.put(p.key(), pf);
                    row.add(pf, BorderLayout.EAST);
                }
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
                rowsPanel.add(row);
            }
            rowsPanel.revalidate();
            rowsPanel.repaint();
        } finally {
            buildingRows = false;
        }
        updateTotalStatus();
        if (isVisible()) {
            refit();
        }
    }

    private void updateTotalStatus() {
        SplitMode mode = modeCombo.selected();
        if (mode == SplitMode.EQUAL) {
            totalBanner.setColors(Theme.GREEN_BG, Theme.BORDER);
            totalIcon.setIcon(Icons.of(Icons.Type.CHECK_CIRCLE, 15, Theme.GREEN));
            totalLabel.setForeground(Theme.GREEN);
            totalLabel.setText("Equal split total: " + Theme.peso(targetTotal.doubleValue())
                    + " across " + activeParticipants.size() + " participant" + (activeParticipants.size() == 1 ? "" : "s") + " (exact match)");
            return;
        }

        BigDecimal sum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        boolean anyInvalid = false;
        for (UI.PrefixField pf : customFields.values()) {
            BigDecimal val = parseNonNegative(pf.getText());
            if (val == null) {
                anyInvalid = true;
                UI.setError(pf, true);
            } else {
                UI.setError(pf, false);
                sum = sum.add(val);
            }
        }

        if (anyInvalid) {
            totalBanner.setColors(Theme.RED_BG, Theme.RED_BORDER);
            totalIcon.setIcon(Icons.of(Icons.Type.ALERT, 15, Theme.RED));
            totalLabel.setForeground(Theme.RED);
            totalLabel.setText("Enter valid non-negative peso amounts (up to 2 decimals) for every participant.");
            return;
        }

        int cmp = sum.compareTo(targetTotal);
        if (cmp == 0) {
            totalBanner.setColors(Theme.GREEN_BG, Theme.BORDER);
            totalIcon.setIcon(Icons.of(Icons.Type.CHECK_CIRCLE, 15, Theme.GREEN));
            totalLabel.setForeground(Theme.GREEN);
            totalLabel.setText("Allocated: " + Theme.peso(sum.doubleValue()) + " / " + Theme.peso(targetTotal.doubleValue()) + " \u2014 Exact match");
        } else if (cmp < 0) {
            BigDecimal diff = targetTotal.subtract(sum);
            totalBanner.setColors(Theme.RED_BG, Theme.RED_BORDER);
            totalIcon.setIcon(Icons.of(Icons.Type.ALERT, 15, Theme.RED));
            totalLabel.setForeground(Theme.RED);
            totalLabel.setText("Allocated: " + Theme.peso(sum.doubleValue()) + " / " + Theme.peso(targetTotal.doubleValue())
                    + " (" + Theme.peso(diff.doubleValue()) + " short)");
        } else {
            BigDecimal diff = sum.subtract(targetTotal);
            totalBanner.setColors(Theme.RED_BG, Theme.RED_BORDER);
            totalIcon.setIcon(Icons.of(Icons.Type.ALERT, 15, Theme.RED));
            totalLabel.setForeground(Theme.RED);
            totalLabel.setText("Allocated: " + Theme.peso(sum.doubleValue()) + " / " + Theme.peso(targetTotal.doubleValue())
                    + " (" + Theme.peso(diff.doubleValue()) + " over)");
        }
    }

    @Override protected Runnable prepareSave() {
        Participant payer = payerCombo.selected();
        String payerKey = payer == null ? SplitAllocator.OWNER_KEY : payer.key();
        boolean includeOwner = includeOwnerBox.isSelected();
        SplitMode mode = modeCombo.selected();

        Map<String, BigDecimal> customMap = new LinkedHashMap<>();
        if (mode == SplitMode.CUSTOM) {
            for (Participant p : activeParticipants) {
                UI.PrefixField pf = customFields.get(p.key());
                BigDecimal val = pf == null ? null : parseNonNegative(pf.getText());
                if (val == null) {
                    updateTotalStatus();
                    return null;
                }
                customMap.put(p.key(), val);
            }
            try {
                SplitAllocator.validateCustomSplits(targetTotal, customMap);
            } catch (ValidationException ex) {
                updateTotalStatus();
                return null;
            }
        }

        return () -> Store.get().saveSplitConfiguration(sub, payerKey, includeOwner, mode, customMap);
    }

    private static BigDecimal parseNonNegative(String text) {
        if (text == null) return null;
        String clean = text.replace(",", "").replace("\u20B1", "").trim();
        if (clean.isEmpty()) return null;
        try {
            BigDecimal bd = new BigDecimal(clean);
            if (bd.signum() < 0) return null;
            BigDecimal scaled = bd.setScale(2, RoundingMode.HALF_UP);
            return bd.compareTo(scaled) == 0 ? scaled : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
