package beenewed.screens;

import beenewed.App;
import beenewed.model.Models;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.*;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.List;
import java.util.stream.Collectors;

/** S2 \u2014 Subscriptions list with search, category and status filters. */
public class SubscriptionsScreen extends JPanel {
    private final UI.TextField search = new UI.TextField("Search subscriptions...");
    private final UI.Combo<String> category;
    private final UI.Combo<String> status = new UI.Combo<>("All Statuses", "Active", "Paused", "Cancelled");
    private final JLabel showing = UI.label("", Theme.SMALL, Theme.TEXT_2);
    private final JPanel tableHolder = UI.clear(new BorderLayout());

    public SubscriptionsScreen() {
        super(new BorderLayout());
        setBackground(Theme.BG);
        String[] cats = new String[Models.CATEGORIES.length + 1];
        cats[0] = "All Categories";
        System.arraycopy(Models.CATEGORIES, 0, cats, 1, Models.CATEGORIES.length);
        category = new UI.Combo<>(cats);

        UI.Btn add = UI.primary("Add Subscription", Icons.Type.PLUS);
        add.addActionListener(e -> App.get().addSubscription());
        JComponent header = Common.pageHeader("Subscriptions", "View and manage your recurring subscriptions.", add);

        // ---- Filter bar
        search.setBorder(new UI.FieldBorder(8, 34));
        JLayeredPane searchWrap = new JLayeredPane() {
            @Override public Dimension getPreferredSize() { return new Dimension(260, search.getPreferredSize().height); }
            @Override public void doLayout() { search.setBounds(0, 0, getWidth(), getHeight()); icon.setBounds(11, 0, 18, getHeight()); }
            final JLabel icon = new JLabel(Icons.of(Icons.Type.SEARCH, 15, Theme.MUTED));
            { add(search, JLayeredPane.DEFAULT_LAYER); add(icon, JLayeredPane.PALETTE_LAYER); }
        };
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); }
            public void removeUpdate(DocumentEvent e) { refresh(); }
            public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        category.addActionListener(e -> refresh());
        status.addActionListener(e -> refresh());
        category.setPreferredSize(new Dimension(160, category.getPreferredSize().height));
        status.setPreferredSize(new Dimension(140, status.getPreferredSize().height));
        UI.Btn reset = UI.soft("Reset", Icons.Type.FILTER).small();
        reset.setForeground(Theme.TEXT_2);
        reset.addActionListener(e -> { search.setText(""); category.setSelectedIndex(0); status.setSelectedIndex(0); });
        showing.setIcon(Icons.of(Icons.Type.SLIDERS, 14, Theme.MUTED));

        UI.RoundPanel filters = UI.card(new BorderLayout(), 12);
        JPanel left = UI.hrow(10, searchWrap, category, status, reset);
        filters.add(left, BorderLayout.WEST);
        filters.add(showing, BorderLayout.EAST);

        // ---- Keyboard hints
        JPanel hints = UI.hrow(6, kbd("\u2191/\u2193"), UI.muted("Select Row"), Box.createHorizontalStrut(10),
                kbd("Enter"), UI.muted("Open Details"), Box.createHorizontalStrut(10), kbd("Ins"), UI.muted("New Subscription"));

        JPanel body = UI.vstack(14, header, filters, tableHolder, hints);
        add(Common.page(body), BorderLayout.CENTER);
    }

    private JComponent kbd(String t) {
        JLabel l = UI.label(t, Theme.TINY, Theme.TEXT_2);
        l.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(2, 5, 2, 5)));
        l.setOpaque(true);
        l.setBackground(Theme.SURFACE);
        return l;
    }

    public void refresh() {
        Store st = Store.get();
        String q = search.getText().trim().toLowerCase();
        String cat = category.selected();
        String stat = status.selected();
        List<Subscription> list = st.subscriptions.stream()
                .filter(s -> q.isEmpty() || s.name.toLowerCase().contains(q) || s.category.toLowerCase().contains(q))
                .filter(s -> cat == null || cat.startsWith("All") || s.category.equals(cat))
                .filter(s -> stat == null || stat.startsWith("All") || s.status.label.equals(stat))
                .collect(Collectors.toList());

        long activeShown = list.stream().filter(s -> s.status == Status.ACTIVE).count();
        showing.setText("Showing " + list.size() + (list.size() == activeShown ? " active" : "") + " subscription" + (list.size() == 1 ? "" : "s"));

        DataTable t = new DataTable(
                new String[]{"Subscription", "Category", "Price", "Billing Cycle", "Next Renewal", "Status", "Actions"},
                new float[]{2.6f, 1.7f, 1.3f, 1.5f, 1.6f, 1.3f, 1.4f},
                new int[]{DataTable.L, DataTable.L, DataTable.R, DataTable.L, DataTable.L, DataTable.L, DataTable.R}, true);
        for (Subscription s : list) {
            UI.Btn view = UI.secondary("View", null).small();
            view.addActionListener(e -> App.get().openDetails(s, 0));
            UI.Btn more = new UI.Btn(null, null, UI.Kind.SECONDARY);
            more.setIcon(Icons.of(Icons.Type.DOTS, 14, Theme.TEXT_2));
            more.setBorder(new EmptyBorder(6, 6, 6, 6));
            more.addActionListener(e -> moreMenu(s).show(more, 0, more.getHeight() + 2));
            JLabel price = Common.money(s.price, true);
            t.addRow(() -> App.get().openDetails(s, 0), Common.subCell(s, true), UI.chip(s.category), price,
                    s.cycle.label, Theme.date(s.nextRenewal), Common.statusBadge(s.status), UI.hrow(6, view, more));
        }
        if (list.isEmpty()) t.setEmpty("No subscriptions match your filters.");
        JLabel totalActive = UI.label("Total active subscriptions: " + st.active().size(), Theme.SMALL, Theme.TEXT_2);
        totalActive.setIcon(Icons.of(Icons.Type.CHECK_CIRCLE, 14, Theme.GREEN));
        JLabel totals = UI.label("<html>Monthly normalized cost: <b style='color:" + Theme.hex(Theme.ACCENT) + "'>" + Theme.peso(st.monthlyTotal())
                + "</b> &nbsp;\u2022&nbsp; Annual equivalent: <b style='color:" + Theme.hex(Theme.TEXT) + "'>" + Theme.peso(st.annualTotal()) + "</b></html>", Theme.SMALL, Theme.TEXT_2);
        t.setFooter(totalActive, totals);

        installKeyboard(t, list);
        tableHolder.removeAll();
        UI.RoundPanel card = new UI.RoundPanel(new BorderLayout(), 12, Theme.SURFACE, null);
        card.add(t);
        tableHolder.add(card, BorderLayout.CENTER);
        tableHolder.revalidate();
        tableHolder.repaint();
    }

    private JPopupMenu moreMenu(Subscription s) {
        JPopupMenu m = new JPopupMenu();
        m.add(item("Edit Subscription", Icons.Type.PENCIL, () -> App.get().editSubscription(s)));
        m.add(item("Change Status", Icons.Type.SWAP, () -> App.get().changeStatus(s)));
        m.add(item("Record Payment", Icons.Type.RECEIPT, () -> App.get().recordPayment(s)));
        m.addSeparator();
        m.add(item("Delete", Icons.Type.TRASH, () -> {
            int ok = JOptionPane.showConfirmDialog(App.get(), "Delete " + s.name + " and all of its records?", "Delete Subscription",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok == JOptionPane.OK_OPTION) {
                Window win = SwingUtilities.getWindowAncestor(this);
                if (win != null) win.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() {
                        Store.get().deleteSubscription(s);
                        return null;
                    }

                    @Override
                    protected void done() {
                        if (win != null) win.setCursor(Cursor.getDefaultCursor());
                        try {
                            get();
                            Store.get().changed();
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        } catch (java.util.concurrent.ExecutionException error) {
                            Throwable cause = error.getCause();
                            if (cause instanceof ValidationException || cause instanceof NotFoundException
                                    || cause instanceof Store.BackendAccessException) {
                                Common.showBackendError(SubscriptionsScreen.this, (RuntimeException) cause);
                            } else {
                                JOptionPane.showMessageDialog(
                                        SubscriptionsScreen.this,
                                        cause == null ? "Unable to delete subscription." : cause.getMessage(),
                                        "Delete failed",
                                        JOptionPane.ERROR_MESSAGE);
                            }
                        }
                    }
                }.execute();
            }
        }));
        return m;
    }

    static JMenuItem item(String text, Icons.Type icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.of(icon, 14, Theme.TEXT_2));
        i.setFont(Theme.BODY);
        i.setBorder(new EmptyBorder(6, 8, 6, 14));
        i.addActionListener(e -> r.run());
        return i;
    }

    /** Up/Down selects a row (highlight), Enter opens it. */
    private int selectedRow = -1;
    private void installKeyboard(DataTable t, List<Subscription> list) {
        selectedRow = -1;
        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();
        im.put(KeyStroke.getKeyStroke("DOWN"), "rowDown");
        im.put(KeyStroke.getKeyStroke("UP"), "rowUp");
        im.put(KeyStroke.getKeyStroke("ENTER"), "rowOpen");
        am.put("rowDown", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { move(t, list, 1); } });
        am.put("rowUp", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) { move(t, list, -1); } });
        am.put("rowOpen", new AbstractAction() { public void actionPerformed(java.awt.event.ActionEvent e) {
            if (isShowing() && selectedRow >= 0 && selectedRow < list.size()) App.get().openDetails(list.get(selectedRow), 0);
        } });
    }
    private void move(DataTable t, List<Subscription> list, int d) {
        if (!isShowing() || list.isEmpty() || search.isFocusOwner()) return;
        selectedRow = Math.max(0, Math.min(list.size() - 1, selectedRow + d));
        JPanel body = (JPanel) ((BorderLayout) t.getLayout()).getLayoutComponent(BorderLayout.CENTER);
        for (int i = 0; i < body.getComponentCount(); i++) {
            JComponent row = (JComponent) body.getComponent(i);
            row.setBorder(i == selectedRow
                    ? BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, Theme.PRIMARY), new EmptyBorder(9, 9, 9, 12))
                    : new EmptyBorder(9, 12, 9, 12));
        }
        body.repaint();
    }
}
