package beenewed;

import beenewed.dialogs.*;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.screens.*;
import beenewed.ui.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Beenewed \u2014 subscription tracker (Java Swing).
 * Main window: sidebar navigation on the left, a header bar, and screens in a CardLayout.
 */
public class App extends JFrame {
    private static App instance;
    public static App get() { return instance; }

    public enum Screen { DASHBOARD, SUBSCRIPTIONS, RENEWALS, REPORTS, SETTINGS, DETAILS }

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Map<Screen, JLabel> navItems = new LinkedHashMap<>();
    private Screen current = Screen.DASHBOARD;

    private final DashboardScreen dashboard = new DashboardScreen();
    private final SubscriptionsScreen subscriptions = new SubscriptionsScreen();
    private final DetailsScreen details = new DetailsScreen();
    private final RenewalsScreen renewals = new RenewalsScreen();
    private final ReportsScreen reports = new ReportsScreen();
    private final SettingsScreen settings = new SettingsScreen();

    public App() {
        super("Beenewed \u2014 Subscription Tracker");
        instance = this;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 860);
        setMinimumSize(new Dimension(1080, 680));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.add(buildSidebar(), BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(Theme.BG);
        JPanel header = new JPanel();
        header.setBackground(Color.WHITE);
        header.setPreferredSize(new Dimension(10, 56));
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        main.add(header, BorderLayout.NORTH);

        content.setBackground(Theme.BG);
        content.add(dashboard, Screen.DASHBOARD.name());
        content.add(subscriptions, Screen.SUBSCRIPTIONS.name());
        content.add(details, Screen.DETAILS.name());
        content.add(renewals, Screen.RENEWALS.name());
        content.add(reports, Screen.REPORTS.name());
        content.add(settings, Screen.SETTINGS.name());
        main.add(content, BorderLayout.CENTER);
        root.add(main, BorderLayout.CENTER);
        setContentPane(root);

        // Dim overlay shown behind modal dialogs
        JPanel glass = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Theme.OVERLAY);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        glass.setOpaque(false);
        glass.addMouseListener(new MouseAdapter() {});
        setGlassPane(glass);

        // Keyboard shortcut from the design: Ins = New Subscription
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), "newSub");
        root.getActionMap().put("newSub", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { addSubscription(); }
        });

        Store.get().onChange(this::refreshCurrent);
        show(Screen.DASHBOARD);
    }

    private JComponent buildSidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Color.WHITE);
        side.setPreferredSize(new Dimension(232, 10));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER));

        // Brand
        UI.RoundPanel logo = new UI.RoundPanel(new GridBagLayout(), 6, Theme.PRIMARY, null);
        logo.add(new JLabel(Icons.of(Icons.Type.WALLET, 15, Color.WHITE)));
        logo.setPreferredSize(new Dimension(24, 24));
        logo.setMaximumSize(new Dimension(24, 24));
        JPanel brand = UI.hrow(10, logo, UI.label("Beenewed", Theme.BODY_B, Theme.TEXT));
        brand.setBorder(new EmptyBorder(0, 16, 0, 16));
        JPanel brandBar = new JPanel(new BorderLayout());
        brandBar.setOpaque(false);
        brandBar.setPreferredSize(new Dimension(10, 56));
        brandBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        brandBar.add(brand, BorderLayout.CENTER);
        side.add(brandBar, BorderLayout.NORTH);

        JPanel nav = UI.vstack(4);
        nav.setBorder(new EmptyBorder(10, 8, 10, 8));
        addNav(nav, Screen.DASHBOARD, "Dashboard", Icons.Type.DASHBOARD);
        addNav(nav, Screen.SUBSCRIPTIONS, "Subscriptions", Icons.Type.CARD);
        addNav(nav, Screen.RENEWALS, "Renewals & Payments", Icons.Type.CALENDAR);
        addNav(nav, Screen.REPORTS, "Reports", Icons.Type.CHART);
        addNav(nav, Screen.SETTINGS, "Settings", Icons.Type.GEAR);
        JPanel navWrap = UI.clear(new BorderLayout());
        navWrap.add(nav, BorderLayout.NORTH);
        side.add(navWrap, BorderLayout.CENTER);
        return side;
    }

    private void addNav(JPanel nav, Screen s, String text, Icons.Type icon) {
        JLabel item = new JLabel(text) {
            @Override protected void paintComponent(Graphics g0) {
                if (s == navScreen(current)) {
                    Graphics2D g = (Graphics2D) g0.create(); UI.aa(g);
                    g.setColor(Theme.PRIMARY_LIGHT);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    g.dispose();
                }
                super.paintComponent(g0);
            }
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        item.putClientProperty("icon", icon);
        item.setFont(Theme.BODY);
        item.setIconTextGap(10);
        item.setBorder(new EmptyBorder(8, 10, 8, 10));
        item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        item.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (s == Screen.RENEWALS) renewals.selectTab(0);
                show(s);
            }
        });
        navItems.put(s, item);
        nav.add(item);
    }

    private static Screen navScreen(Screen s) { return s == Screen.DETAILS ? Screen.SUBSCRIPTIONS : s; }

    // ---- Navigation ----------------------------------------------------
    public void show(Screen s) {
        current = s;
        cards.show(content, s.name());
        for (Map.Entry<Screen, JLabel> e : navItems.entrySet()) {
            boolean sel = e.getKey() == navScreen(s);
            JLabel l = e.getValue();
            l.setForeground(sel ? Theme.PRIMARY : Theme.TEXT_2);
            l.setFont(sel ? Theme.BODY_B : Theme.BODY);
            l.setIcon(Icons.of((Icons.Type) l.getClientProperty("icon"), 16, sel ? Theme.PRIMARY : Theme.TEXT_2));
            l.repaint();
        }
        refreshCurrent();
    }

    private void refreshCurrent() {
        switch (current) {
            case DASHBOARD -> dashboard.refresh();
            case SUBSCRIPTIONS -> subscriptions.refresh();
            case DETAILS -> details.refresh();
            case RENEWALS -> renewals.refresh();
            case REPORTS -> reports.refresh();
            case SETTINGS -> settings.refresh();
        }
    }

    public void openDetails(Subscription s, int tab) { details.setSubscription(s, tab); show(Screen.DETAILS); }
    public void openRenewals(int tab) { renewals.selectTab(tab); show(Screen.RENEWALS); }

    // ---- Dialog shortcuts ---------------------------------------------
    public void addSubscription()                    { new SubscriptionDialog(this, null).open(); }
    public void editSubscription(Subscription s)     { new SubscriptionDialog(this, s).open(); }
    public void recordPayment(Subscription s)        { new PaymentDialog(this, null, s).open(); }
    public void editPayment(Payment p)               { new PaymentDialog(this, p, p.sub).open(); }
    public void changeStatus(Subscription s)         { new StatusDialog(this, s, null).open(); }
    public void showReminders()                      { new RemindersDialog(this).open(); }

    // ---- Entry point ---------------------------------------------------
    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> {
            installLookAndFeel();
            new App().setVisible(true);
        });
    }

    /** Uses FlatLaf if its jar is on the classpath (the design is a FlatLaf replica); otherwise the cross-platform look. */
    private static void installLookAndFeel() {
        try {
            Class<?> flat = Class.forName("com.formdev.flatlaf.FlatLightLaf");
            UIManager.setLookAndFeel((LookAndFeel) flat.getDeclaredConstructor().newInstance());
        } catch (Exception noFlatLaf) {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
        }
        UIManager.put("ToolTip.font", Theme.SMALL);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(Theme.BORDER));
        UIManager.put("MenuItem.font", Theme.BODY);
        UIManager.put("MenuItem.selectionBackground", Theme.PRIMARY_LIGHT);
        UIManager.put("MenuItem.selectionForeground", Theme.PRIMARY);
        UIManager.put("OptionPane.messageFont", Theme.BODY);
        UIManager.put("OptionPane.buttonFont", Theme.BODY_B);
        UIManager.put("ComboBox.selectionBackground", Theme.PRIMARY_LIGHT);
        UIManager.put("ComboBox.selectionForeground", Theme.PRIMARY);
        UIManager.put("TextField.selectionBackground", new Color(0xC7D8FB));
        UIManager.put("TextArea.selectionBackground", new Color(0xC7D8FB));
    }
}
