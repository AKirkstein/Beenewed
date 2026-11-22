package beenewed.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * Lightweight table built from panels so cells can hold icons, badges and buttons
 * exactly like the design (a JTable would need custom renderers/editors for each).
 * Columns get widths proportional to their weights.
 */
public class DataTable extends JPanel {
    public static final int L = SwingConstants.LEFT, R = SwingConstants.RIGHT, C = SwingConstants.CENTER;

    private final float[] weights;
    private final int[] aligns;
    private final JPanel body;
    private final JPanel footer;
    private final boolean outlined;
    private int rowCount;

    public DataTable(String[] headers, float[] weights, int[] aligns, boolean outlined) {
        super(new BorderLayout());
        this.weights = weights; this.aligns = aligns; this.outlined = outlined;
        setOpaque(false);
        if (outlined) setBorder(new EmptyBorder(1, 1, 1, 1));

        JPanel head = new JPanel(new ColumnLayout(weights, aligns));
        head.setBackground(Theme.TABLE_HEAD);
        head.setBorder(new EmptyBorder(9, 12, 9, 12));
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i];
            JLabel l = UI.label(h.contains("\n")
                    ? "<html><div style='text-align:" + (aligns[i] == R ? "right" : aligns[i] == C ? "center" : "left") + "'>" + h.toUpperCase().replace("\n", "<br>") + "</div></html>"
                    : h.toUpperCase(), Theme.CAPS, Theme.MUTED);
            l.setHorizontalAlignment(aligns[i]);
            head.add(l);
        }
        add(head, BorderLayout.NORTH);

        body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        add(body, BorderLayout.CENTER);

        footer = new JPanel(new BorderLayout());
        footer.setBackground(Theme.SUBTLE);
        footer.setBorder(new EmptyBorder(10, 12, 10, 12));
        footer.setVisible(false);
        add(footer, BorderLayout.SOUTH);
    }

    public void clear() { body.removeAll(); rowCount = 0; revalidate(); repaint(); }
    public int rows() { return rowCount; }

    /** Adds a row. Strings become labels; `onClick` (may be null) makes the whole row clickable. */
    public JPanel addRow(Runnable onClick, Object... cells) {
        JPanel row = new JPanel(new ColumnLayout(weights, aligns)) {
            boolean hover;
            {
                if (onClick != null) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    addMouseListener(new MouseAdapter() {
                        public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                        public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                        public void mouseClicked(MouseEvent e) { onClick.run(); }
                    });
                }
            }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
            @Override protected void paintComponent(Graphics g) {
                g.setColor(hover ? new Color(0xF8FAFD) : Color.WHITE);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Theme.BORDER);
                g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        row.setBorder(new EmptyBorder(9, 12, 9, 12));
        for (int i = 0; i < cells.length; i++) {
            Object c = cells[i];
            Component comp;
            if (c instanceof Component cc) comp = cc;
            else {
                JLabel l = UI.label(c == null ? "\u2014" : c.toString(), Theme.BODY, Theme.TEXT_2);
                l.setHorizontalAlignment(aligns[i]);
                comp = l;
            }
            row.add(comp);
        }
        row.setAlignmentX(0f);
        body.add(row);
        rowCount++;
        return row;
    }

    /** Bold summary row with a tinted background, aligned with the columns (e.g. "Total"). */
    public void addTotalRow(Object... cells) {
        JPanel row = new JPanel(new ColumnLayout(weights, aligns)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        row.setBackground(Theme.TABLE_HEAD);
        row.setBorder(new EmptyBorder(9, 12, 9, 12));
        for (int i = 0; i < cells.length; i++) {
            JLabel l = UI.label(cells[i] == null ? "" : cells[i].toString(), Theme.BODY_B, Theme.TEXT);
            l.setHorizontalAlignment(aligns[i]);
            row.add(l);
        }
        row.setAlignmentX(0f);
        body.add(row);
    }

    public void setEmpty(String message) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(26, 12, 26, 12));
        p.add(UI.label(message, Theme.BODY, Theme.MUTED));
        p.setAlignmentX(0f);
        body.add(p);
    }

    public void setFooter(Component left, Component right) {
        footer.removeAll();
        if (left != null) footer.add(left, BorderLayout.WEST);
        if (right != null) footer.add(right, BorderLayout.EAST);
        footer.setVisible(left != null || right != null);
    }

    @Override protected void paintChildren(Graphics g0) {
        if (!outlined) { super.paintChildren(g0); return; }
        // Clip children to the rounded outline, then draw the border on top.
        Graphics2D g = (Graphics2D) g0.create();
        UI.aa(g);
        RoundRectangle2D shape = new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 10, 10);
        g.clip(shape);
        super.paintChildren(g);
        g.dispose();
        Graphics2D b = (Graphics2D) g0.create();
        UI.aa(b);
        b.setColor(Theme.BORDER);
        b.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1.5, getHeight() - 1.5, 10, 10));
        b.dispose();
    }

    // ------------------------------------------------------------------
    /** Lays children out in proportional columns, each child at its preferred width, aligned per column. */
    public static class ColumnLayout implements LayoutManager {
        private final float[] w; private final int[] a;
        private static final int GAP = 12;
        public ColumnLayout(float[] weights, int[] aligns) { w = weights; a = aligns; }
        public void addLayoutComponent(String n, Component c) {}
        public void removeLayoutComponent(Component c) {}
        public Dimension preferredLayoutSize(Container p) {
            Insets in = p.getInsets();
            int h = 0, minW = 0;
            for (Component c : p.getComponents()) { h = Math.max(h, c.getPreferredSize().height); }
            for (int i = 0; i < p.getComponentCount() && i < w.length; i++) minW += 40;
            return new Dimension(minW + in.left + in.right, h + in.top + in.bottom);
        }
        public Dimension minimumLayoutSize(Container p) { return preferredLayoutSize(p); }
        public void layoutContainer(Container p) {
            Insets in = p.getInsets();
            int avail = p.getWidth() - in.left - in.right;
            float total = 0; for (float f : w) total += f;
            int innerH = p.getHeight() - in.top - in.bottom;
            float x = in.left;
            Component[] cs = p.getComponents();
            for (int i = 0; i < cs.length && i < w.length; i++) {
                int colW = Math.round(avail * w[i] / total);
                int cellW = colW - (i < w.length - 1 ? GAP : 0);
                Dimension pref = cs[i].getPreferredSize();
                int cw = Math.min(pref.width, cellW);
                if (cs[i] instanceof JLabel && !(cs[i] instanceof UI.Pill)) cw = cellW;   // labels fill the cell and align their own text
                int cx = (int) x;
                if (a[i] == R) cx = (int) x + cellW - cw;
                else if (a[i] == C) cx = (int) x + (cellW - cw) / 2;
                int ch = Math.min(pref.height, innerH);
                cs[i].setBounds(cx, in.top + (innerH - ch) / 2, cw, ch);
                x += colW;
            }
        }
    }
}
