package beenewed.dialogs;

import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;

import javax.swing.*;

/** D3 \u2014 Add or edit a member who shares a subscription. */
public class MemberDialog extends BaseDialog {
    private final Subscription sub;
    private final Member editing;
    private final UI.TextField name = new UI.TextField("e.g. Taylor Cruz");
    private final UI.TextArea notes = new UI.TextArea("e.g., Household member, colleague, or additional notes", 3);
    private final Field fName;

    public MemberDialog(JFrame owner, Subscription sub, Member m) {
        super(owner, m == null ? "Add Member" : "Edit Member",
                m == null ? "Add someone who shares this subscription." : "Update this member's details.",
                Icons.Type.USER_PLUS, m == null ? "Add Member" : "Save Member", Icons.Type.CHECK, 480);
        this.sub = sub; this.editing = m;

        place(linkedContext(sub, false)); gap(14);
        fName = new Field("Member Name", true, null, name, "Identifies the person sharing this subscription.");
        place(fName); gap(10);
        place(new Field(fieldLabel("Role / Notes", false, null, "(Optional)"), notes,
                "Optional descriptive note about the member's relation to this subscription."));

        if (m != null) {
            name.setText(m.name);
            notes.setText(m.notes == null ? "" : m.notes);
            addDelete("member", () -> Store.get().deleteMember(m));
        }
    }

    @Override protected Runnable prepareSave() {
        String n = name.getText().trim();
        fName.error(n.isEmpty() ? "Member name is required." : null);
        if (n.isEmpty()) return null;
        String nt = notes.getText().trim();
        Member payload = new Member(editing == null ? 0 : editing.id, sub, n, nt.isEmpty() ? null : nt);
        return () -> {
            Store st = Store.get();
            if (editing == null) st.addMember(payload);
            else st.updateMember(payload);
        };
    }
}
