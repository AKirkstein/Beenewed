package beenewed;

import beenewed.dialogs.*;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.screens.*;
import beenewed.ui.*;
import com.beenewed.supabase.LocalSnapshotCache;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseConfig;
import com.beenewed.supabase.SupabaseException;
import com.beenewed.supabase.SupabaseRestClient;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
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
    private UI.Btn bellButton;
    private JLabel bellBadge;
    private JPanel offlineBanner;
    private JLabel offlineBannerLabel;
    private UI.Btn reconnectBtn;
    private boolean reconnecting;
    private final SupabaseAuthService authService;
    private final SupabaseRestClient restClient;
    private final LocalSnapshotCache localCache;
    private final Runnable storeListener = this::refreshCurrent;

    private static final DateTimeFormatter SYNC_TIME_FMT =
            DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH);

    public App(SupabaseAuthService authService, SupabaseRestClient restClient) {
        this(authService, restClient, null);
    }

    public App(SupabaseAuthService authService, SupabaseRestClient restClient, LocalSnapshotCache localCache) {
        super("Beenewed \u2014 Subscription Tracker");
        this.authService = authService;
        this.restClient = restClient;
        this.localCache = localCache;
        instance = this;
        UI.applyWindowIcon(this);
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

        Store.get().onChange(storeListener);
        buildUI();
        show(Screen.DASHBOARD);
    }

    @Override
    public void dispose() {
        closeProfileMenu();
        if (Store.isInitialized()) {
            Store.get().offChange(storeListener);
        }
        super.dispose();
    }

    public String getCurrentUserEmail() {
        String email = authService.getEmail();
        if ((email == null || email.isBlank()) && Store.isInitialized()) {
            email = Store.get().getCachedUserEmail();
        }
        return email == null || email.isBlank() ? "Signed-in account" : email;
    }

    /** Invalidates local offline cache/session state and clears the active in-memory session. */
    void clearSessionForSignOut() {
        closeProfileMenu();
        // Purge persisted offline snapshots and active session state so relaunching offline
        // cannot bypass authentication or expose private account data.
        if (localCache != null) {
            localCache.clearSessionOnSignOut();
        }
        // Clears the in-memory session immediately; the server-side revoke runs asynchronously
        // (the JVM stays alive for the login dialog, so the request completes).
        authService.signOut();
        Store.reset();
    }

    /** Clears the active Supabase session and local offline cache, hides the main window, and reopens login. */
    public void logout() {
        clearSessionForSignOut();
        closeWindowAndReopenLogin();
    }

    /** Returns to the login dialog from offline read-only mode without clearing the active offline session. */
    private void reopenLoginOnline() {
        closeProfileMenu();
        Store.reset();
        closeWindowAndReopenLogin();
    }

    private void closeWindowAndReopenLogin() {
        setVisible(false);
        dispose();
        instance = null;

        Theme.apply(false);
        installLookAndFeel(false);
        SwingUtilities.invokeLater(() -> launchWithLogin(authService, restClient, localCache));
    }

    /** Asks for confirmation, then signs out. Used by the profile menu and the Settings screen. */
    public void confirmAndLogout() {
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Sign out of Beenewed?\nYour local offline cache will be cleared and you will need a network connection to sign in again.",
                "Sign Out",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            logout();
        }
    }

    // ---- Profile / account menu ------------------------------------------
    private ProfileMenu profileMenu;
    private long profileMenuClosedAt;

    private void showProfileMenu(JComponent anchor) {
        if (profileMenu != null && profileMenu.isVisible()) {
            closeProfileMenu();
            return;
        }
        // The same mouse press that dismissed the popup should not immediately reopen it.
        if (System.currentTimeMillis() - profileMenuClosedAt < 250) {
            return;
        }
        profileMenu = new ProfileMenu(
                getCurrentUserEmail(),
                authService.getSignedInAt(),
                Theme.isDark(),
                new ProfileMenu.Actions(
                        () -> show(Screen.SETTINGS),
                        () -> setDarkMode(!Theme.isDark()),
                        this::refreshDataFromServer,
                        () -> SwingUtilities.invokeLater(this::confirmAndLogout)));
        profileMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { profileMenuClosedAt = System.currentTimeMillis(); }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
        });
        profileMenu.showBelow(anchor);
    }

    private void closeProfileMenu() {
        if (profileMenu != null) {
            profileMenu.setVisible(false);
            profileMenu = null;
        }
    }

    /** Re-fetches all user data from Supabase in the background, then refreshes the screen. */
    private void refreshDataFromServer() {
        if (!authService.isAuthenticated()) {
            reopenLoginOnline();
            return;
        }
        reconnecting = true;
        updateConnectivityBanner();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return Store.get().forceRefresh();
            }

            @Override
            protected void done() {
                reconnecting = false;
                setCursor(Cursor.getDefaultCursor());
                try {
                    get();
                    Store.get().changed();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    updateConnectivityBanner();
                } catch (java.util.concurrent.ExecutionException error) {
                    updateConnectivityBanner();
                    Throwable cause = error.getCause();
                    if (cause instanceof RuntimeException runtime) {
                        Common.showBackendError(App.this, runtime);
                    } else {
                        JOptionPane.showMessageDialog(App.this,
                                cause == null ? "Unable to refresh data." : cause.getMessage(),
                                "Refresh failed", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        }.execute();
    }

    /** Builds (or rebuilds) every component with the current Theme colours. */
    private void buildUI() {
        closeProfileMenu();
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
        JPanel topBars = new JPanel();
        topBars.setLayout(new BoxLayout(topBars, BoxLayout.Y_AXIS));
        topBars.setOpaque(false);
        JComponent headerBar = buildHeader();
        JComponent bannerBar = buildConnectivityBanner();
        headerBar.setAlignmentX(0f);
        bannerBar.setAlignmentX(0f);
        topBars.add(headerBar);
        topBars.add(bannerBar);
        main.add(topBars, BorderLayout.NORTH);

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
        updateConnectivityBanner();

        // Keyboard shortcut from the design: Ins = New Subscription
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), "newSub");
        root.getActionMap().put("newSub", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { addSubscription(); }
        });
    }

    private JComponent buildConnectivityBanner() {
        offlineBanner = new JPanel(new BorderLayout(12, 0));
        offlineBanner.setBackground(Theme.AMBER_BG);
        offlineBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                new EmptyBorder(8, 22, 8, 22)));
        offlineBannerLabel = UI.label("", Theme.SMALL_B, Theme.AMBER);
        offlineBannerLabel.setIcon(Icons.of(Icons.Type.ALERT, 15, Theme.AMBER));
        offlineBannerLabel.setIconTextGap(8);
        reconnectBtn = UI.secondary("Reconnect", Icons.Type.REFRESH).small();
        reconnectBtn.addActionListener(e -> refreshDataFromServer());
        offlineBanner.add(offlineBannerLabel, BorderLayout.CENTER);
        offlineBanner.add(reconnectBtn, BorderLayout.EAST);
        offlineBanner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        offlineBanner.setVisible(false);
        return offlineBanner;
    }

    private void updateConnectivityBanner() {
        if (offlineBanner == null || !Store.isInitialized()) return;
        Store st = Store.get();
        boolean show = st.isOffline() || reconnecting;
        offlineBanner.setVisible(show);
        if (!show) return;

        if (reconnecting) {
            offlineBannerLabel.setText("Reconnecting to Supabase\u2026");
            reconnectBtn.setEnabled(false);
            reconnectBtn.setText("Reconnecting\u2026");
        } else {
            Instant synced = st.getLastSyncedAt();
            String syncSuffix = synced == null
                    ? ""
                    : " \u2022 Last synced " + SYNC_TIME_FMT.format(synced.atZone(ZoneId.systemDefault()));
            offlineBannerLabel.setText("Offline mode (read-only) \u2014 Showing cached data" + syncSuffix);
            reconnectBtn.setEnabled(true);
            reconnectBtn.setText(authService.isAuthenticated() ? "Reconnect" : "Sign In Online");
        }
    }

    /** Package-private accessor used by UI tests to inspect offline banner state. */
    boolean isOfflineBannerVisibleForTest() {
        return offlineBanner != null && offlineBanner.isVisible();
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
        bellButton = UI.iconButton(Icons.Type.BELL, this::showReminders);
        UI.Btn bell = bellButton;
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

        // Profile icon: opens the account popover (click, Enter, Space, or Shift+F10)
        String profileTip = "Account \u2013 " + getCurrentUserEmail();
        JComponent profile = new JPanel(null) {
            boolean hover;
            {
                setOpaque(false);
                Dimension avatarSize = new Dimension(32, 32);
                setPreferredSize(avatarSize);
                setMinimumSize(avatarSize);
                setMaximumSize(avatarSize);
                setToolTipText(profileTip);
                setFocusable(true);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                AccessibleContext ctx = getAccessibleContext();
                if (ctx != null) {
                    ctx.setAccessibleName("Account menu");
                    ctx.setAccessibleDescription(profileTip);
                }
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                    @Override public void mousePressed(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e)) { requestFocusInWindow(); showProfileMenu(this_()); }
                    }
                });
                addFocusListener(new FocusAdapter() {
                    @Override public void focusGained(FocusEvent e) { repaint(); }
                    @Override public void focusLost(FocusEvent e) { repaint(); }
                });
                Action open = new AbstractAction() {
                    public void actionPerformed(ActionEvent e) { showProfileMenu(this_()); }
                };
                getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openProfile");
                getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "openProfile");
                getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "openProfile");
                getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "openProfile");
                getActionMap().put("openProfile", open);
            }
            private JComponent this_() { return this; }
            @Override
            public AccessibleContext getAccessibleContext() {
                if (accessibleContext == null) {
                    accessibleContext = new AccessibleJPanel() {
                        @Override
                        public AccessibleRole getAccessibleRole() {
                            return AccessibleRole.PUSH_BUTTON;
                        }
                    };
                }
                return accessibleContext;
            }
            @Override protected void paintComponent(Graphics g0) {
                boolean active = hover || isFocusOwner();
                UI.paintCircularAvatar(
                        (Graphics2D) g0,
                        this,
                        getWidth(),
                        getHeight(),
                        null,
                        Icons.of(Icons.Type.USER, 18, Theme.ACCENT),
                        active ? Theme.SOFT_HOVER : Theme.PRIMARY_LIGHT,
                        active ? Theme.PRIMARY : Theme.BORDER_STRONG,
                        active ? 2f : 1f);
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
        int n = Store.get().activeReminderCount();
        bellBadge.setText(n > 9 ? "9+" : n > 0 ? String.valueOf(n) : "");
        bellBadge.setVisible(n > 0);
        if (bellButton != null) {
            bellButton.setToolTipText(n > 0 ? "Renewal reminders (" + n + ")" : "Renewal reminders");
        }
    }

    /** Package-private accessor used by UI tests to inspect notification badge state. */
    JLabel bellBadgeForTest() {
        return bellBadge;
    }

    // ---- Light / Dark mode ---------------------------------------------
    /**
     * Switches the whole application between Light and Dark mode immediately.
     * Switches the FlatLaf look-and-feel and rebuilds every screen in place with
     * the new colours, keeps you on the same page, and persists the choice to
     * Supabase on a best-effort basis when online (never blocked by offline mode).
     */
    public void setDarkMode(boolean dark) {
        if (dark == Theme.isDark()) return;
        applyThemeToWindow(dark);

        if (!Store.isInitialized()) return;
        if (Store.get().isOffline()) {
            Store.get().saveTheme(dark);
            return;
        }

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                Store.get().saveTheme(dark);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // Best-effort remote sync; keep the local theme applied.
                }
                updateConnectivityBanner();
            }
        }.execute();
    }

    private void applyThemeToWindow(boolean dark) {
        Screen keep = current;
        Subscription detailSub = details.getSubscription();
        int detailTab = details.getTab();
        int renewTab = renewals.getTab();

        Theme.apply(dark);
        installLookAndFeel(dark);
        FlatLaf.updateUI();
        buildUI();
        if (detailSub != null) details.setSubscription(detailSub, detailTab);
        renewals.selectTab(renewTab);
        show(keep);
        revalidate();
        repaint();
    }

    private JComponent buildSidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Theme.SURFACE);
        side.setPreferredSize(new Dimension(232, 10));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER));

        // Brand
        JComponent logo = UI.brandLogo(28, 6, 15);
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
            updateBell();
            updateConnectivityBanner();
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
        closeProfileMenu();
        // Wait briefly so the server-side revoke is actually sent before the JVM exits.
        authService.signOutAndWait(java.time.Duration.ofSeconds(3));
        authService.close();
        restClient.close();
        Store.reset();
        dispose();
        System.exit(0);
    }

    public void openDetails(Subscription s, int tab) { details.setSubscription(s, tab); show(Screen.DETAILS); }
    public void openRenewals(int tab) { renewals.selectTab(tab); show(Screen.RENEWALS); }
    public void openReports(int tab) { reports.selectBasis(tab); show(Screen.REPORTS); }

    // ---- Dialog shortcuts ---------------------------------------------
    public void addSubscription()                    { new SubscriptionDialog(this, null).open(); }
    public void editSubscription(Subscription s)     { new SubscriptionDialog(this, s).open(); }
    public void recordPayment(Subscription s)        { new PaymentDialog(this, s).open(); }
    public void viewPayment(Payment p)               { PaymentDialog.viewReceipt(this, p).open(); }
    public void changeStatus(Subscription s)         { new StatusDialog(this, s, null).open(); }
    public void showReminders()                      { new RemindersDialog(this).open(); }

    // ---- Entry point ---------------------------------------------------
    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> {
            Theme.apply(false);
            installLookAndFeel(false);

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
            LocalSnapshotCache localCache = LocalSnapshotCache.defaultCache();
            launchWithLogin(authService, restClient, localCache);
        });
    }

    private static void launchWithLogin(
            SupabaseAuthService authService,
            SupabaseRestClient restClient,
            LocalSnapshotCache localCache) {
        LoginDialog loginDialog = new LoginDialog(
                null,
                authService,
                () -> Store.initialize(restClient, localCache, authService.getUserId(), authService.getEmail()),
                localCache,
                cached -> Store.initializeOffline(restClient, localCache, cached));
        boolean loggedIn = loginDialog.showDialog();
        boolean offlineBoot = loginDialog.isOfflineOpened() && Store.isInitialized();
        if ((!loggedIn || !authService.isAuthenticated()) && !offlineBoot) {
            authService.close();
            restClient.close();
            System.exit(0);
            return;
        }

        boolean dark = Store.get().getTheme() == com.beenewed.backend.model.Theme.DARK;
        Theme.apply(dark);
        installLookAndFeel(dark);
        new App(authService, restClient, localCache).setVisible(true);
    }

    /** Installs FlatLaf (Light or Dark); falls back to the cross-platform look if setup fails. */
    private static void installLookAndFeel(boolean dark) {
        boolean installed = dark ? FlatDarkLaf.setup() : FlatLightLaf.setup();
        if (!installed) {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {}
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
