package beenewed.dialogs;

import beenewed.model.Models.*;
import beenewed.model.SplitAllocator;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;
import java.awt.*;

/** D3 \u2014 Add or edit a member who shares a subscription. */
public class MemberDialog extends BaseDialog {
    private final Subscription sub;
    private final Member editing;
    private final SplitAllocator.MemberSplitMeta existingMeta;
    private final UI.TextField name = new UI.TextField("e.g. Taylor Cruz");
    private final JCheckBox primaryPayerBox = new JCheckBox("Designate as Primary Payer (Group Head)");
    private final UI.TextArea notes = new UI.TextArea("e.g., Household member, colleague, or additional notes", 3);
    private final Field fName;

    public MemberDialog(JFrame owner, Subscription sub, Member m) {
        super(owner, m == null ? "Add Member" : "Edit Member",
                m == null ? "Add someone who shares this subscription." : "Update this member's details.",
                Icons.Type.USER_PLUS, m == null ? "Add Member" : "Save Member", Icons.Type.CHECK, 480);
        this.sub = sub;
        this.editing = m;
        SplitAllocator.SplitConfig currentSplit = Store.get().splitConfigFor(sub);
        this.existingMeta = m == null
                ? new SplitAllocator.MemberSplitMeta(false, null, currentSplit.includeOwner(), currentSplit.mode())
                : SplitAllocator.parseMemberMeta(m.notes);

        place(linkedContext(sub, false)); gap(14);
        fName = new Field("Member Name", true, null, name, "Identifies the person sharing this subscription.");
        place(fName); gap(10);

        boolean isCurrentPrimaryPayer = m != null && SplitAllocator.memberKey(m.id).equals(currentSplit.payerKey());
        primaryPayerBox.setSelected(isCurrentPrimaryPayer);
        primaryPayerBox.setOpaque(false);
        primaryPayerBox.setFont(Theme.BODY);
        primaryPayerBox.setForeground(Theme.TEXT);
        primaryPayerBox.setFocusPainted(false);
        JPanel payerRow = UI.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        payerRow.add(primaryPayerBox);
        payerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, payerRow.getPreferredSize().height));
        place(payerRow); gap(10);

        place(new Field(fieldLabel("Role / Notes", false, null, "(Optional)"), notes,
                "Optional descriptive note about the member's relation to this subscription."));

        if (m != null) {
            name.setText(m.name);
            String clean = SplitAllocator.cleanNotes(m.notes);
            notes.setText(clean == null ? "" : clean);
            addDelete("member", () -> Store.get().deleteMember(m));
        }
    }

    @Override protected Runnable prepareSave() {
        String n = name.getText().trim();
        fName.error(n.isEmpty() ? "Member name is required." : null);
        if (n.isEmpty()) return null;
        String nt = notes.getText().trim();
        SplitAllocator.MemberSplitMeta nextMeta = new SplitAllocator.MemberSplitMeta(
                primaryPayerBox.isSelected(),
                existingMeta.customShare(),
                existingMeta.includeOwner(),
                existingMeta.mode());
        String encodedNotes = SplitAllocator.encodeMemberNotes(nt.isEmpty() ? null : nt, nextMeta);
        Member payload = new Member(editing == null ? 0 : editing.id, sub, n, encodedNotes);
        return () -> {
            Store st = Store.get();
            if (editing == null) st.addMember(payload);
            else st.updateMember(payload);
        };
    }
}
