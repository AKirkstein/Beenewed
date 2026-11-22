package beenewed;

import beenewed.dialogs.*;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.screens.*;
import beenewed.ui.*;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseConfig;
import com.beenewed.supabase.SupabaseException;
import com.beenewed.supabase.SupabaseRestClient;

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

    private CardLayout cards;
    private JPanel content;
    private final Map<Screen, JLabel> navItems = new LinkedHashMap<>();
    private Screen current = Screen.DASHBOARD;

    private DashboardScreen dashboard;
    private SubscriptionsScreen subscriptions;
    private DetailsScreen details;
    private RenewalsScreen renewals;
    private ReportsScreen reports;
    private SettingsScreen settings;
    private JLabel bellBadge;
    private final SupabaseAuthService authService;
    private final SupabaseRestClient restClient;

    public App(SupabaseAuthService authService, SupabaseRestClient restClient) {
        super("Beenewed \u2014 Subscription Tracker");
        this.authService = authService;
        this.restClient = restClient;
        instance = this;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                shutdown();
            }
        });
        setSize(1280, 860);
        setMinimumSize(new Dimension(1080, 680));
        setLocationRelativeTo(null);

        // Dim overlay shown behind modal dialogs (reads Theme.OVERLAY when painting, so it follows the theme)
        JPanel glass = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Theme.OVERLAY);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        glass.setOpaque(false);
        glass.addMouseListener(new MouseAdapter() {});
        setGlassPane(glass);

        Store.get().onChange(this::refreshCurrent);
        buildUI();
        show(Screen.DASHBOARD);
    }

    public String getCurrentUserEmail() {
        String email = authService.getEmail();
        return email == null || email.isBlank() ? "Signed-in account" : email;
    }

    /** Clears the active Supabase session, hides the main window, and reopens the login dialog. */
    public void logout() {
        authService.signOut();
        Store.reset();
        setVisible(false);
        dispose();
        instance = null;

        Theme.apply(false);
        installUIColors();
        SwingUtilities.invokeLater(() -> launchWithLogin(authService, restClient));
    }

    /** Builds (or rebuilds) every component with the current Theme colours. */
    private void buildUI() {
        cards = new CardLayout();
        content = new JPanel(cards);
        navItems.clear();
        dashboard = new DashboardScreen();
        subscriptions = new SubscriptionsScreen();
        details = new DetailsScreen();
        renewals = new RenewalsScreen();
        reports = new ReportsScreen();
        settings = new SettingsScreen();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG);
        root.add(buildSidebar(), BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(Theme.BG);
        main.add(buildHeader(), BorderLayout.NORTH);

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

        // Keyboard shortcut from the design: Ins = New Subscription
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), "newSub");
        root.getActionMap().put("newSub", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { addSubscription(); }
        });
    }

    /** Top header bar: theme switch, notifications bell and profile icon on the right. */
    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.SURFACE);
        header.setPreferredSize(new Dimension(10, 56));
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), BorderFactory.createEmptyBorder(0, 0, 0, 22)));

        UI.ThemeSwitch themeSwitch = new UI.ThemeSwitch(Theme.isDark(), this::setDarkMode);

        // Bell with a small count badge (renewal reminders)
        UI.Btn bell = UI.iconButton(Icons.Type.BELL, this::showReminders);
        bell.setIcon(Icons.of(Icons.Type.BELL, 18, Theme.TEXT_2));
        bell.setToolTipText("Renewal reminders");
        bellBadge = new JLabel("", SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); UI.aa(g);
                g.setColor(Theme.PRIMARY_HOVER);
                g.fillOval(0, 0, getWidth(), getHeight());
                g.dispose();
                super.paintComponent(g0);
            }
        };
        bellBadge.setFont(Theme.bold(9f));
        bellBadge.setForeground(Theme.ON_PRIMARY);
        JLayeredPane bellWrap = new JLayeredPane() {
            @Override public Dimension getPreferredSize() { return new Dimension(32, 32); }
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
            @Override public void doLayout() { bell.setBounds(1, 2, 28, 28); bellBadge.setBounds(17, 2, 15, 15); }
        };
        bellWrap.add(bell, JLayeredPane.DEFAULT_LAYER);
        bellWrap.add(bellBadge, JLayeredPane.PALETTE_LAYER);

        // Profile icon
        String profileTip = "Signed in as " + getCurrentUserEmail();
        JComponent profile = new JComponent() {
            { setPreferredSize(new Dimension(32, 32)); setMaximumSize(getPreferredSize()); setToolTipText(profileTip); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create(); UI.aa(g);
                g.setColor(Theme.PRIMARY_LIGHT);
                g.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                g.setColor(Theme.BORDER_STRONG);
                g.drawOval(0, 0, getWidth() - 1, getHeight() - 1);
                Icons.of(Icons.Type.USER, 18, Theme.ACCENT).paintIcon(this, g, (getWidth() - 18) / 2, (getHeight() - 18) / 2);
                g.dispose();
            }
        };

        JPanel right = UI.hrow(12, themeSwitch, bellWrap, profile);
        JPanel rw = UI.clear(new GridBagLayout());
        rw.add(right);
        header.add(rw, BorderLayout.EAST);
        updateBell();
        return header;
    }

    private void updateBell() {
        if (bellBadge == null) return;
        Store st = Store.get();
        int n = st.upcomingReminders().size() + (st.showOverdue ? st.overdue().size() : 0);
        bellBadge.setText(n > 9 ? "9+" : String.valueOf(n));
        bellBadge.setVisible(n > 0);
    }

    // ---- Light / Dark mode ---------------------------------------------
    /**
     * Switches the whole application between Light and Dark mode immediately.
     * Rebuilds every screen in place with the new colours, keeps you on the same page, and saves the choice.
     */
    public void setDarkMode(boolean dark) {
        if (dark == Theme.isDark()) return;
        try {
            Store.get().saveTheme(dark);
        } catch (Store.BackendAccessException error) {
            Common.showBackendError(this, error);
            return;
        }
        Screen keep = current;
        Subscription detailSub = details.getSubscription();
        int detailTab = details.getTab();
        int renewTab = renewals.getTab();

        Theme.apply(dark);
        installUIColors();
        buildUI();
        if (detailSub != null) details.setSubscription(detailSub, detailTab);
        renewals.selectTab(renewTab);
        show(keep);
        SwingUtilities.updateComponentTreeUI(getGlassPane());
        revalidate();
        repaint();
    }

    private JComponent buildSidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Theme.SURFACE);
        side.setPreferredSize(new Dimension(232, 10));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER));

        // Brand
        UI.RoundPanel logo = new UI.RoundPanel(new GridBagLayout(), 6, Theme.PRIMARY, null);
        logo.add(new JLabel(Icons.of(Icons.Type.WALLET, 15, Theme.ON_PRIMARY)));
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
            l.setForeground(sel ? Theme.ACCENT : Theme.TEXT_2);
            l.setFont(sel ? Theme.BODY_B : Theme.BODY);
            l.setIcon(Icons.of((Icons.Type) l.getClientProperty("icon"), 16, sel ? Theme.ACCENT : Theme.TEXT_2));
            l.repaint();
        }
        refreshCurrent();
    }

    private void refreshCurrent() {
        try {
            Store.get().refresh();
            updateBell();
            switch (current) {
                case DASHBOARD -> dashboard.refresh();
                case SUBSCRIPTIONS -> subscriptions.refresh();
                case DETAILS -> details.refresh();
                case RENEWALS -> renewals.refresh();
                case REPORTS -> reports.refresh();
                case SETTINGS -> settings.refresh();
            }
        } catch (Store.BackendAccessException error) {
            Common.showBackendError(this, error);
        }
    }

    private void shutdown() {
        authService.signOut();
        Store.reset();
        dispose();
        System.exit(0);
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
            Theme.apply(false);
            installLookAndFeel();

            SupabaseConfig config;
            try {
                config = SupabaseConfig.load();
            } catch (SupabaseException error) {
                JOptionPane.showMessageDialog(
                        null,
                        error.getMessage(),
                        "Supabase Configuration Required",
                        JOptionPane.ERROR_MESSAGE);
                System.exit(1);
                return;
            }

            SupabaseAuthService authService = new SupabaseAuthService(config);
            SupabaseRestClient restClient = new SupabaseRestClient(config, authService);
            launchWithLogin(authService, restClient);
        });
    }

    private static void launchWithLogin(SupabaseAuthService authService, SupabaseRestClient restClient) {
        LoginDialog loginDialog = new LoginDialog(null, authService);
        if (!loginDialog.showDialog() || !authService.isAuthenticated()) {
            System.exit(0);
            return;
        }

        try {
            Store.initialize(restClient);
            Theme.apply(Store.get().getTheme() == com.beenewed.backend.model.Theme.DARK);
            installUIColors();
            new App(authService, restClient).setVisible(true);
        } catch (SupabaseException | Store.BackendAccessException error) {
            authService.signOut();
            Store.reset();
            JOptionPane.showMessageDialog(
                    null,
                    error.getMessage(),
                    "Unable to load account data",
                    JOptionPane.ERROR_MESSAGE);
            launchWithLogin(authService, restClient);
        }
    }

    /** Uses FlatLaf if its jar is on the classpath (the design is a FlatLaf replica); otherwise the cross-platform look. */
    private static void installLookAndFeel() {
        try {
            Class<?> flat = Class.forName("com.formdev.flatlaf.FlatLightLaf");
            UIManager.setLookAndFeel((LookAndFeel) flat.getDeclaredConstructor().newInstance());
        } catch (Exception noFlatLaf) {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
        }
        installUIColors();
    }

    /** Colours for standard Swing pieces (popup menus, tooltips, confirm boxes) so they follow the theme too. */
    private static void installUIColors() {
        UIManager.put("ToolTip.font", Theme.SMALL);
        UIManager.put("ToolTip.background", Theme.ELEVATED);
        UIManager.put("ToolTip.foreground", Theme.TEXT);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(Theme.BORDER_STRONG));
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(Theme.BORDER));
        UIManager.put("PopupMenu.background", Theme.ELEVATED);
        UIManager.put("MenuItem.font", Theme.BODY);
        UIManager.put("MenuItem.background", Theme.ELEVATED);
        UIManager.put("MenuItem.foreground", Theme.TEXT);
        UIManager.put("MenuItem.selectionBackground", Theme.PRIMARY_LIGHT);
        UIManager.put("MenuItem.selectionForeground", Theme.ACCENT);
        UIManager.put("Separator.foreground", Theme.BORDER);
        UIManager.put("Separator.background", Theme.ELEVATED);
        UIManager.put("OptionPane.messageFont", Theme.BODY);
        UIManager.put("OptionPane.buttonFont", Theme.BODY_B);
        UIManager.put("OptionPane.background", Theme.ELEVATED);
        UIManager.put("OptionPane.messageForeground", Theme.TEXT);
        UIManager.put("Panel.background", Theme.ELEVATED);
        UIManager.put("Label.foreground", Theme.TEXT);
        UIManager.put("Button.background", Theme.SURFACE);
        UIManager.put("Button.foreground", Theme.TEXT);
        UIManager.put("ComboBox.background", Theme.FIELD_BG);
        UIManager.put("ComboBox.foreground", Theme.TEXT);
        UIManager.put("ComboBox.selectionBackground", Theme.PRIMARY_LIGHT);
        UIManager.put("ComboBox.selectionForeground", Theme.ACCENT);
        UIManager.put("List.background", Theme.ELEVATED);
        UIManager.put("List.foreground", Theme.TEXT);
        UIManager.put("TextField.selectionBackground", Theme.SELECTION);
        UIManager.put("TextField.selectionForeground", Theme.TEXT);
        UIManager.put("TextArea.selectionBackground", Theme.SELECTION);
        UIManager.put("TextArea.selectionForeground", Theme.TEXT);
        UIManager.put("TextPane.selectionBackground", Theme.SELECTION);
    }
}
