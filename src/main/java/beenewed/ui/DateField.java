package beenewed.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.Locale;

/** Date input (mm/dd/yyyy) with a calendar button that opens a month picker. */
public class DateField extends JPanel {
    private final UI.TextField field;
    private final JButton cal;
    private final JPopupMenu popup = new JPopupMenu();
    private YearMonth shown = YearMonth.now();

    public DateField(String placeholder) {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(new UI.FieldBorder(0, 0));
        field = new UI.TextField(placeholder == null ? "mm/dd/yyyy" : placeholder);
        field.setBorder(new EmptyBorder(9, 12, 9, 4));
        InputFilters.installDateFilter(field);
        field.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent e) { DateField.this.repaint(); }
            public void focusLost(FocusEvent e) { DateField.this.repaint(); }
        });
        cal = new JButton(Icons.of(Icons.Type.CALENDAR, 16, Theme.MUTED));
        cal.setBorder(new EmptyBorder(0, 6, 0, 10));
        cal.setContentAreaFilled(false);
        cal.setFocusPainted(false);
        cal.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cal.setToolTipText("Pick a date");
        cal.addActionListener(e -> openPicker());
        add(field, BorderLayout.CENTER);
        add(cal, BorderLayout.EAST);
        popup.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        field.setEnabled(enabled);
        field.setEditable(enabled);
        field.setFocusable(enabled);
        field.setDisabledTextColor(Theme.MUTED);
        cal.setEnabled(enabled);
        cal.setFocusable(enabled);
        cal.setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        cal.setToolTipText(enabled ? "Pick a date" : null);
        if (!enabled) {
            popup.setVisible(false);
        }
        repaint();
    }

    public boolean isPopupVisible() {
        return popup.isVisible();
    }

    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    @Override protected void paintComponent(Graphics g) { UI.paintFieldBg(this, g); super.paintComponent(g); }

    public void setDate(LocalDate d) { field.setText(d == null ? "" : Theme.INPUT_DATE.format(d)); }
    public boolean isBlank() { return field.getText().isBlank(); }

    /** Strictly parses mm/dd/yyyy, m/d/yyyy, or yyyy-mm-dd; returns null when blank or invalid. */
    public LocalDate getDate() {
        return InputFilters.parseStrictDate(field.getText());
    }

    private void openPicker() {
        if (!isEnabled()) return;
        LocalDate d = getDate();
        shown = YearMonth.from(d != null ? d : beenewed.model.Store.today());
        rebuild();
        popup.show(this, 0, getHeight() + 2);
    }

    private void rebuild() {
        popup.removeAll();
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(Theme.ELEVATED);
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        JButton prev = navButton(Icons.Type.CHEVRON_LEFT, () -> { shown = shown.minusMonths(1); rebuild(); popup.pack(); popup.repaint(); });
        JButton next = navButton(Icons.Type.CHEVRON_RIGHT, () -> { shown = shown.plusMonths(1); rebuild(); popup.pack(); popup.repaint(); });
        JLabel title = UI.label(shown.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + shown.getYear(), Theme.BODY_B, Theme.TEXT);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(prev, BorderLayout.WEST); head.add(title, BorderLayout.CENTER); head.add(next, BorderLayout.EAST);
        p.add(head, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));
        grid.setOpaque(false);
        DayOfWeek[] order = {DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY};
        for (DayOfWeek dw : order) {
            JLabel l = UI.label(dw.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).substring(0, 2), Theme.TINY, Theme.MUTED);
            l.setHorizontalAlignment(SwingConstants.CENTER);
            grid.add(l);
        }
        int offset = shown.atDay(1).getDayOfWeek().getValue() % 7;
        for (int i = 0; i < offset; i++) grid.add(new JLabel());
        LocalDate sel = getDate(), today = beenewed.model.Store.today();
        for (int day = 1; day <= shown.lengthOfMonth(); day++) {
            LocalDate date = shown.atDay(day);
            JButton b = new JButton(String.valueOf(day));
            b.setFont(Theme.SMALL);
            b.setMargin(new Insets(4, 4, 4, 4));
            b.setFocusPainted(false);
            b.setBorder(new EmptyBorder(5, 7, 5, 7));
            boolean isSel = date.equals(sel);
            b.setBackground(isSel ? Theme.PRIMARY : date.equals(today) ? Theme.PRIMARY_LIGHT : Theme.ELEVATED);
            b.setForeground(isSel ? Theme.ON_PRIMARY : Theme.TEXT);
            b.setOpaque(true);
            b.setBorderPainted(false);
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(e -> { setDate(date); popup.setVisible(false); field.requestFocusInWindow(); });
            grid.add(b);
        }
        p.add(grid, BorderLayout.CENTER);
        popup.add(p);
    }

    private JButton navButton(Icons.Type t, Runnable r) {
        JButton b = new JButton(Icons.of(t, 14, Theme.TEXT_2));
        b.setBorder(new EmptyBorder(4, 6, 4, 6));
        b.setContentAreaFilled(false);
        b.setFocusPainted(false);
        b.addActionListener(e -> r.run());
        return b;
    }
}
