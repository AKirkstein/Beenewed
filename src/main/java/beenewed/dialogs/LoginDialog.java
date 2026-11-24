package beenewed.dialogs;

import beenewed.ui.Icons;
import beenewed.ui.InputFilters;
import beenewed.ui.Theme;
import beenewed.ui.UI;
import com.beenewed.supabase.LocalSnapshotCache;
import com.beenewed.supabase.PasswordPolicy;
import com.beenewed.supabase.SupabaseAuthException;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseRestClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Startup authentication dialog supporting Supabase Sign In, Create Account,
 * Email OTP verification, and Forgot Password recovery flows, styled with
 * Beenewed's Swing theme.
 */
public final class LoginDialog extends JDialog {
    private static final int DIALOG_WIDTH = 440;

    private enum Mode { SIGN_IN, SIGN_UP, VERIFY_OTP, FORGOT_PASSWORD, RESET_PASSWORD }

    private final SupabaseAuthService authService;
    private final Runnable onAuthenticatedLoad;
    private final LocalSnapshotCache localCache;
    private final Consumer<LocalSnapshotCache.CachedSnapshot> onOfflineOpen;
    private final BooleanSupplier connectivityChecker;

    private Mode mode = Mode.SIGN_IN;
    private String pendingVerifyEmail;
    private String pendingResetEmail;
    private boolean authenticated = false;
    private boolean offlineOpened = false;
    private boolean busy = false;
    private boolean networkOffline = false;
    private boolean checkingConnectivity = false;

    private final UI.Btn signInTab = UI.soft("Sign In", null);
    private final UI.Btn signUpTab = UI.ghost("Create Account", null);
    private final JPanel modeBarWrap;

    private final JLabel headingLabel = UI.h2("Welcome back");
    private final JLabel subtitleLabel = UI.label("Sign in to access your Beenewed subscriptions and preferences.", Theme.SMALL, Theme.MUTED);

    private final UI.RoundPanel offlineNoticePanel = new UI.RoundPanel(new BorderLayout(8, 0), 8, Theme.AMBER_BG, Theme.INFO_BORDER);
    private final JLabel offlineNoticeIcon = new JLabel();
    private final JTextArea offlineNoticeText = new JTextArea();
    private final UI.Btn retryConnectBtn = UI.secondary("Retry", Icons.Type.REFRESH).small();
    private final JPanel offlineNoticeWrap;

    private final UI.RoundPanel bannerPanel = new UI.RoundPanel(new BorderLayout(8, 0), 8, Theme.RED_BG, null);
    private final JLabel bannerIcon = new JLabel();
    private final JTextArea bannerText = new JTextArea();
    private final JPanel bannerWrap;

    private final JLabel verifyEmailLabel = UI.label("", Theme.BODY_B, Theme.TEXT);
    private final JPanel verifyEmailWrap;

    private final UI.TextField emailField = new UI.TextField("you@example.com");
    private final UI.TextField recoveryCodeField = new UI.TextField("Enter 6-digit recovery code");
    private final UI.PasswordField passwordField = new UI.PasswordField("Enter your password");
    private final UI.PasswordField confirmPasswordField = new UI.PasswordField("Re-enter your password");
    private final UI.TextField otpField = new UI.TextField("Enter 8-digit verification code");
    /** Look-and-feel echo character captured before any reveal toggling. */
    private final char defaultEchoChar = passwordField.getEchoChar() != 0 ? passwordField.getEchoChar() : '\u2022';

    // Live password-strength checklist (Create Account & Reset Password modes)
    private final Map<PasswordPolicy.Rule, JLabel> ruleLabels = new EnumMap<>(PasswordPolicy.Rule.class);
    private final JPanel strengthPanel;
    private final JLabel matchHint = UI.label(" ", Theme.SMALL, Theme.MUTED);
    private boolean strengthAttempted = false;

    // Show-password toggle and Forgot Password link
    private final JCheckBox showPasswordBox = new JCheckBox("Show password");
    private final UI.Btn forgotPasswordLink = UI.link("Forgot Password?");
    private final JPanel showPasswordWrap;

    private final JLabel passwordFieldLabel = fieldLabel("Password");
    private final JLabel confirmFieldLabel = fieldLabel("Confirm Password");
    private final JPanel emailSection;
    private final JPanel recoveryCodeSection;
    private final JPanel passwordSection;
    private final JPanel confirmSection;
    private final JPanel otpSection;

    private final UI.Btn submitBtn = UI.primary("Sign In", null);
    private final UI.Btn backToSignInBtn = UI.secondary("Back to Sign In", null);
    private final JPanel backToSignInWrap;
    private final UI.Btn offlineBtn = UI.secondary("Continue Offline (Read-Only)", Icons.Type.CLOCK);
    private final JPanel offlineWrap;
    private final UI.Btn resendBtn = UI.secondary("Resend Code", null);
    private final JPanel resendWrap;
    private final UI.Btn switchModeLink = UI.link("Don't have an account? Create one");
    private final JPanel linkWrap;

    public LoginDialog(Frame owner, SupabaseAuthService authService) {
        this(owner, authService, null, null, null);
    }

    public LoginDialog(Frame owner, SupabaseAuthService authService, Runnable onAuthenticatedLoad) {
        this(owner, authService, onAuthenticatedLoad, null, null);
    }

    public LoginDialog(
            Frame owner,
            SupabaseAuthService authService,
            Runnable onAuthenticatedLoad,
            LocalSnapshotCache localCache,
            Consumer<LocalSnapshotCache.CachedSnapshot> onOfflineOpen) {
        this(owner, authService, onAuthenticatedLoad, localCache, onOfflineOpen,
                authService == null ? () -> true : authService::isServerReachable);
    }

    public LoginDialog(
            Frame owner,
            SupabaseAuthService authService,
            Runnable onAuthenticatedLoad,
            LocalSnapshotCache localCache,
            Consumer<LocalSnapshotCache.CachedSnapshot> onOfflineOpen,
            BooleanSupplier connectivityChecker) {
        super(owner, "Beenewed \u2014 Sign In", true);
        this.authService = authService;
        this.onAuthenticatedLoad = onAuthenticatedLoad;
        this.localCache = localCache;
        this.onOfflineOpen = onOfflineOpen;
        this.connectivityChecker = connectivityChecker != null
                ? connectivityChecker
                : (authService == null ? () -> true : authService::isServerReachable);

        UI.applyWindowIcon(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(Theme.BG);
        outer.setBorder(new EmptyBorder(24, 24, 24, 24));

        UI.RoundPanel card = new UI.RoundPanel(new BorderLayout(), 14, Theme.SURFACE, Theme.BORDER);
        card.setBorder(new EmptyBorder(24, 26, 22, 26));

        // Brand header (shown across Sign In, Create Account, and Verify Email modes)
        JComponent logo = UI.brandLogo(32, 8, 16);
        JPanel brandRow = UI.hrow(10, logo, UI.label("Beenewed", Theme.H2, Theme.TEXT));
        brandRow.setBorder(new EmptyBorder(0, 0, 16, 0));

        // Mode switcher (hidden in VERIFY_OTP view)
        signInTab.addActionListener(e -> switchMode(Mode.SIGN_IN));
        signUpTab.addActionListener(e -> switchMode(Mode.SIGN_UP));
        JPanel modeBar = new JPanel(new GridLayout(1, 2, 8, 0));
        modeBar.setOpaque(false);
        modeBar.add(signInTab);
        modeBar.add(signUpTab);
        modeBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        modeBarWrap = wrapSection(modeBar, 16);

        // Heading & subtitle
        JPanel headerBlock = UI.vstack(4, headingLabel, subtitleLabel);
        headerBlock.setBorder(new EmptyBorder(0, 0, 14, 0));

        // Offline connectivity banner (visible when Supabase is unreachable)
        offlineNoticePanel.setBorder(new EmptyBorder(10, 12, 10, 10));
        offlineNoticeIcon.setVerticalAlignment(SwingConstants.TOP);
        offlineNoticeIcon.setIcon(Icons.of(Icons.Type.ALERT, 15, Theme.AMBER));
        offlineNoticeText.setFont(Theme.SMALL);
        offlineNoticeText.setForeground(Theme.AMBER);
        offlineNoticeText.setLineWrap(true);
        offlineNoticeText.setWrapStyleWord(true);
        offlineNoticeText.setEditable(false);
        offlineNoticeText.setFocusable(false);
        offlineNoticeText.setOpaque(false);
        offlineNoticeText.setBorder(null);
        retryConnectBtn.addActionListener(e -> checkConnectivityAsync());
        JPanel retryWrap = UI.clear(new GridBagLayout());
        retryWrap.add(retryConnectBtn);
        offlineNoticePanel.add(offlineNoticeIcon, BorderLayout.WEST);
        offlineNoticePanel.add(offlineNoticeText, BorderLayout.CENTER);
        offlineNoticePanel.add(retryWrap, BorderLayout.EAST);
        offlineNoticeWrap = wrapSection(offlineNoticePanel, 12);
        offlineNoticeWrap.setVisible(false);

        // Inline banner (hidden until an error or info notice occurs)
        bannerPanel.setBorder(new EmptyBorder(10, 12, 10, 12));
        bannerIcon.setVerticalAlignment(SwingConstants.TOP);
        bannerText.setFont(Theme.SMALL);
        bannerText.setLineWrap(true);
        bannerText.setWrapStyleWord(true);
        bannerText.setEditable(false);
        bannerText.setFocusable(false);
        bannerText.setOpaque(false);
        bannerText.setBorder(null);
        bannerPanel.add(bannerIcon, BorderLayout.WEST);
        bannerPanel.add(bannerText, BorderLayout.CENTER);
        bannerWrap = wrapSection(bannerPanel, 12);
        bannerWrap.setVisible(false);

        // Target email pill shown in Verify Email mode
        UI.RoundPanel emailBadge = new UI.RoundPanel(new BorderLayout(8, 0), 8, Theme.SUBTLE, Theme.BORDER);
        emailBadge.setBorder(new EmptyBorder(10, 12, 10, 12));
        emailBadge.add(new JLabel(Icons.of(Icons.Type.USER, 14, Theme.ACCENT)), BorderLayout.WEST);
        emailBadge.add(verifyEmailLabel, BorderLayout.CENTER);
        verifyEmailWrap = wrapSection(emailBadge, 14);
        verifyEmailWrap.setVisible(false);

        // Form fields (bottom margin lives inside each section so hidden fields collapse cleanly)
        emailSection = labeledField("Email", emailField, 12);

        // Password strength checklist, one row per rule
        strengthPanel = new JPanel();
        strengthPanel.setOpaque(false);
        strengthPanel.setLayout(new BoxLayout(strengthPanel, BoxLayout.Y_AXIS));
        strengthPanel.setBorder(new EmptyBorder(8, 2, 0, 0));
        JLabel reqTitle = UI.label("Password must include:", Theme.SMALL_B, Theme.TEXT_2);
        reqTitle.setBorder(new EmptyBorder(0, 0, 4, 0));
        reqTitle.setAlignmentX(0f);
        strengthPanel.add(reqTitle);
        for (PasswordPolicy.Rule rule : PasswordPolicy.Rule.values()) {
            JLabel row = UI.label(rule.label, Theme.SMALL, Theme.MUTED);
            row.setIconTextGap(6);
            row.setBorder(new EmptyBorder(1, 0, 1, 0));
            row.setAlignmentX(0f);
            ruleLabels.put(rule, row);
            strengthPanel.add(row);
        }
        strengthPanel.setVisible(false);

        matchHint.setIconTextGap(6);
        matchHint.setBorder(new EmptyBorder(6, 2, 0, 0));
        matchHint.setVisible(false);

        InputFilters.installDigitsFilter(recoveryCodeField, InputFilters.RECOVERY_CODE_MAX_LENGTH);
        recoveryCodeSection = labeledField("Recovery Code", recoveryCodeField, 12);
        recoveryCodeSection.setVisible(false);

        passwordSection = dynamicSection(12, passwordFieldLabel, passwordField, strengthPanel);
        confirmSection = dynamicSection(12, confirmFieldLabel, confirmPasswordField, matchHint);
        confirmSection.setVisible(false);
        InputFilters.installDigitsFilter(otpField, InputFilters.OTP_CODE_LENGTH);
        otpSection = labeledField("Verification Code", otpField, 14);
        otpSection.setVisible(false);

        // Show password (applies to both Password and Confirm Password) + Forgot Password? link (Sign In only)
        showPasswordBox.setOpaque(false);
        showPasswordBox.setFont(Theme.SMALL);
        showPasswordBox.setForeground(Theme.TEXT_2);
        showPasswordBox.setFocusPainted(false);
        showPasswordBox.setMnemonic(KeyEvent.VK_H);
        showPasswordBox.setToolTipText("Reveal the characters you typed (Alt+H)");
        showPasswordBox.addItemListener(e -> applyPasswordVisibility());
        forgotPasswordLink.setToolTipText("Reset your password via email");
        forgotPasswordLink.addActionListener(e -> openForgotPassword());
        JPanel showRow = UI.clear(new BorderLayout(8, 0));
        showRow.add(showPasswordBox, BorderLayout.WEST);
        showRow.add(forgotPasswordLink, BorderLayout.EAST);
        showPasswordWrap = wrapSection(showRow, 10);

        DocumentListener clearEmailErr = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { UI.setError(emailField, false); }
            public void removeUpdate(DocumentEvent e) { UI.setError(emailField, false); }
            public void changedUpdate(DocumentEvent e) { UI.setError(emailField, false); }
        };
        emailField.getDocument().addDocumentListener(clearEmailErr);

        DocumentListener clearRecoveryErr = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { UI.setError(recoveryCodeField, false); }
            public void removeUpdate(DocumentEvent e) { UI.setError(recoveryCodeField, false); }
            public void changedUpdate(DocumentEvent e) { UI.setError(recoveryCodeField, false); }
        };
        recoveryCodeField.getDocument().addDocumentListener(clearRecoveryErr);

        DocumentListener feedback = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updatePasswordFeedback(); }
            public void removeUpdate(DocumentEvent e) { updatePasswordFeedback(); }
            public void changedUpdate(DocumentEvent e) { updatePasswordFeedback(); }
        };
        passwordField.getDocument().addDocumentListener(feedback);
        confirmPasswordField.getDocument().addDocumentListener(feedback);

        // Primary submit button
        submitBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        submitBtn.setPreferredSize(new Dimension(DIALOG_WIDTH - 100, 40));
        submitBtn.addActionListener(e -> submit());
        JPanel submitWrap = wrapSection(submitBtn, 10);
        submitWrap.setBorder(new EmptyBorder(6, 0, 10, 0));

        // Back to Sign In button (Forgot Password & Reset Password modes)
        backToSignInBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        backToSignInBtn.setPreferredSize(new Dimension(DIALOG_WIDTH - 100, 38));
        backToSignInBtn.addActionListener(e -> returnToSignIn());
        backToSignInWrap = wrapSection(backToSignInBtn, 4);
        backToSignInWrap.setVisible(false);

        // Offline read-only fallback button (visible on Sign In only when offline AND an active local session exists)
        offlineBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        offlineBtn.setPreferredSize(new Dimension(DIALOG_WIDTH - 100, 38));
        offlineBtn.addActionListener(e -> openOfflineCache());
        offlineWrap = wrapSection(offlineBtn, 10);
        offlineWrap.setVisible(false);

        // Resend code button (Verify Email and Reset Password modes)
        resendBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        resendBtn.setPreferredSize(new Dimension(DIALOG_WIDTH - 100, 38));
        resendBtn.addActionListener(e -> resendCode());
        resendWrap = wrapSection(resendBtn, 10);
        resendWrap.setVisible(false);

        // Bottom link
        switchModeLink.addActionListener(e -> handleSwitchLink());
        linkWrap = UI.clear(new FlowLayout(FlowLayout.CENTER, 0, 0));
        linkWrap.setBorder(new EmptyBorder(2, 0, 0, 0));
        linkWrap.add(switchModeLink);

        JPanel stack = new JPanel() {
            {
                setOpaque(false);
                setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            }
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(DIALOG_WIDTH - 100, d.height);
            }
        };
        for (Component c : new Component[]{
                brandRow,
                modeBarWrap,
                headerBlock,
                offlineNoticeWrap,
                bannerWrap,
                verifyEmailWrap,
                emailSection,
                recoveryCodeSection,
                passwordSection,
                confirmSection,
                showPasswordWrap,
                otpSection,
                submitWrap,
                resendWrap,
                backToSignInWrap,
                offlineWrap,
                linkWrap
        }) {
            if (c instanceof JComponent jc) jc.setAlignmentX(0f);
            stack.add(c);
        }

        card.add(stack, BorderLayout.CENTER);
        outer.add(card, BorderLayout.CENTER);
        setContentPane(outer);

        getRootPane().setDefaultButton(submitBtn);
        getRootPane().registerKeyboardAction(
                e -> {
                    if (!busy) {
                        if (mode == Mode.FORGOT_PASSWORD || mode == Mode.RESET_PASSWORD) {
                            returnToSignIn();
                        } else {
                            dispose();
                        }
                    }
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        updateModeUI();
        fitDialog();
    }

    /** Shows the modal login dialog and returns {@code true} if the user authenticated successfully. */
    public boolean showDialog() {
        fitDialog();
        setLocationRelativeTo(getOwner());
        SwingUtilities.invokeLater(() -> {
            emailField.requestFocusInWindow();
            checkConnectivityAsync();
        });
        setVisible(true);
        return authenticated;
    }

    /** Returns {@code true} if the user chose to open Beenewed from local offline cache. */
    public boolean isOfflineOpened() {
        return offlineOpened;
    }

    /** Returns {@code true} if the auth screen is currently gated because Supabase is unreachable. */
    public boolean isNetworkOffline() {
        return networkOffline;
    }

    private boolean hasActiveOfflineSession() {
        return localCache != null && onOfflineOpen != null && localCache.hasActiveSessionSnapshot();
    }

    private void openOfflineCache() {
        if (busy || !networkOffline || localCache == null || onOfflineOpen == null) {
            return;
        }
        Optional<LocalSnapshotCache.CachedSnapshot> snap = localCache.loadActiveSessionSnapshot();
        if (snap.isEmpty()) {
            offlineWrap.setVisible(false);
            offlineBtn.setEnabled(false);
            showBanner("No active offline session is available. Sign in online first to enable offline mode.", true);
            return;
        }
        onOfflineOpen.accept(snap.get());
        offlineOpened = true;
        dispose();
    }

    private void checkConnectivityAsync() {
        if (checkingConnectivity) {
            return;
        }
        checkingConnectivity = true;
        retryConnectBtn.setEnabled(false);
        retryConnectBtn.setText("Checking\u2026");

        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    return connectivityChecker.getAsBoolean();
                } catch (RuntimeException error) {
                    return false;
                }
            }

            @Override
            protected void done() {
                boolean online = false;
                try {
                    online = Boolean.TRUE.equals(get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ignored) {
                    online = false;
                }
                applyConnectivityState(online);
            }
        }.execute();
    }

    private void applyConnectivityState(boolean online) {
        this.networkOffline = !online;
        this.checkingConnectivity = false;
        retryConnectBtn.setText("Retry");
        updateModeUI();
        fitDialog();
    }

    // ---- Package-private test accessors --------------------------------

    void checkConnectivitySyncForTest() {
        boolean online;
        try {
            online = connectivityChecker.getAsBoolean();
        } catch (RuntimeException error) {
            online = false;
        }
        applyConnectivityState(online);
    }

    void switchModeToSignUpForTest() {
        switchMode(Mode.SIGN_UP);
    }

    void triggerOfflineOpenForTest() {
        openOfflineCache();
    }

    boolean isOfflineNoticeVisibleForTest() {
        return offlineNoticeWrap.isVisible();
    }

    String getOfflineNoticeTextForTest() {
        return offlineNoticeText.getText();
    }

    boolean isOfflineContinueVisibleForTest() {
        return offlineWrap.isVisible();
    }

    boolean isOfflineContinueEnabledForTest() {
        return offlineBtn.isEnabled();
    }

    boolean isSubmitEnabledForTest() {
        return submitBtn.isEnabled();
    }

    String getSubmitButtonTextForTest() {
        return submitBtn.getText();
    }

    boolean isForgotPasswordLinkVisibleForTest() {
        return forgotPasswordLink.isVisible();
    }

    boolean isForgotPasswordLinkEnabledForTest() {
        return forgotPasswordLink.isEnabled();
    }

    void clickForgotPasswordForTest() {
        forgotPasswordLink.doClick();
    }

    boolean isForgotPasswordModeForTest() {
        return mode == Mode.FORGOT_PASSWORD;
    }

    boolean isResetPasswordModeForTest() {
        return mode == Mode.RESET_PASSWORD;
    }

    boolean isSignInModeForTest() {
        return mode == Mode.SIGN_IN;
    }

    boolean isEmailEditableForTest() {
        return emailField.isEditable();
    }

    void setRecoveryCodeForTest(String code) {
        recoveryCodeField.setText(code);
    }

    void setPasswordForTest(String password) {
        passwordField.setText(password);
    }

    void setConfirmPasswordForTest(String confirmPassword) {
        confirmPasswordField.setText(confirmPassword);
    }

    boolean isResendVisibleForTest() {
        return resendWrap.isVisible();
    }

    void clickResendForTest() {
        resendBtn.doClick();
    }

    boolean isBackToSignInVisibleForTest() {
        return backToSignInWrap.isVisible();
    }

    boolean isBackToSignInEnabledForTest() {
        return backToSignInBtn.isEnabled();
    }

    void clickBackToSignInForTest() {
        backToSignInBtn.doClick();
    }

    void setEmailForTest(String email) {
        emailField.setText(email);
    }

    String getEmailForTest() {
        return emailField.getText();
    }

    void clickSubmitForTest() {
        submitBtn.doClick();
    }

    boolean isBannerVisibleForTest() {
        return bannerWrap.isVisible();
    }

    String getBannerTextForTest() {
        return bannerText.getText();
    }

    Color getBannerForegroundForTest() {
        return bannerText.getForeground();
    }

    boolean isBusyForTest() {
        return busy;
    }

    boolean isPasswordSectionVisibleForTest() {
        return passwordSection.isVisible();
    }

    private void openForgotPassword() {
        if (busy) {
            return;
        }
        switchMode(Mode.FORGOT_PASSWORD);
        SwingUtilities.invokeLater(emailField::requestFocusInWindow);
    }

    private void returnToSignIn() {
        if (busy) {
            return;
        }
        switchMode(Mode.SIGN_IN);
        SwingUtilities.invokeLater(() -> {
            if (emailField.getText().isBlank()) {
                emailField.requestFocusInWindow();
            } else {
                passwordField.requestFocusInWindow();
            }
        });
    }

    private void switchMode(Mode target) {
        if (busy || this.mode == target) {
            return;
        }
        Mode previous = this.mode;
        this.mode = target;
        clearBanner();
        clearFieldErrors();
        strengthAttempted = false;
        showPasswordBox.setSelected(false);
        confirmPasswordField.setText("");
        if (target == Mode.FORGOT_PASSWORD || target == Mode.RESET_PASSWORD) {
            passwordField.setText("");
            recoveryCodeField.setText("");
        }
        if (target == Mode.SIGN_IN) {
            pendingResetEmail = null;
            recoveryCodeField.setText("");
        }
        if (target == Mode.SIGN_IN && previous == Mode.VERIFY_OTP
                && pendingVerifyEmail != null && !pendingVerifyEmail.isBlank()) {
            emailField.setText(pendingVerifyEmail);
            passwordField.setText("");
            otpField.setText("");
        }
        updateModeUI();
        fitDialog();
    }

    private void handleSwitchLink() {
        if (busy) {
            return;
        }
        switch (mode) {
            case SIGN_IN -> switchMode(Mode.SIGN_UP);
            case SIGN_UP, VERIFY_OTP, FORGOT_PASSWORD, RESET_PASSWORD -> returnToSignIn();
        }
    }

    private void updateModeUI() {
        boolean isSignIn = mode == Mode.SIGN_IN;
        boolean isSignUp = mode == Mode.SIGN_UP;
        boolean isVerify = mode == Mode.VERIFY_OTP;
        boolean isForgot = mode == Mode.FORGOT_PASSWORD;
        boolean isReset = mode == Mode.RESET_PASSWORD;
        boolean needsStrength = isSignUp || isReset;
        if (!needsStrength) {
            strengthAttempted = false;
        }

        setTitle(switch (mode) {
            case SIGN_IN -> "Beenewed \u2014 Sign In";
            case SIGN_UP -> "Beenewed \u2014 Create Account";
            case VERIFY_OTP -> "Beenewed \u2014 Verify Email";
            case FORGOT_PASSWORD -> "Beenewed \u2014 Forgot Password";
            case RESET_PASSWORD -> "Beenewed \u2014 Reset Password";
        });

        modeBarWrap.setVisible(isSignIn || isSignUp);
        signInTab.setKind(isSignIn ? UI.Kind.SOFT : UI.Kind.GHOST);
        signUpTab.setKind(isSignUp ? UI.Kind.SOFT : UI.Kind.GHOST);

        headingLabel.setText(switch (mode) {
            case SIGN_IN -> "Welcome back";
            case SIGN_UP -> "Create your account";
            case VERIFY_OTP -> "Verify your email";
            case FORGOT_PASSWORD -> "Reset your password";
            case RESET_PASSWORD -> "Enter recovery code";
        });
        subtitleLabel.setText(switch (mode) {
            case SIGN_IN -> "Sign in to access your Beenewed subscriptions and preferences.";
            case SIGN_UP -> "Set up an account to store your subscriptions securely in Supabase.";
            case VERIFY_OTP -> "Enter the verification code sent to your email address.";
            case FORGOT_PASSWORD -> "Enter your account email and we'll send a 6-digit recovery code.";
            case RESET_PASSWORD -> "Enter the 6-digit code from your email and choose a new password.";
        });

        verifyEmailLabel.setText(pendingVerifyEmail == null ? "" : pendingVerifyEmail);
        verifyEmailWrap.setVisible(isVerify);

        emailSection.setVisible(!isVerify);
        emailField.setEditable(!isReset);
        recoveryCodeSection.setVisible(isReset);

        passwordFieldLabel.setText(isReset ? "New Password" : "Password");
        confirmFieldLabel.setText(isReset ? "Confirm New Password" : "Confirm Password");
        passwordSection.setVisible(isSignIn || isSignUp || isReset);
        passwordField.setPlaceholder(needsStrength
                ? "Min " + PasswordPolicy.MIN_LENGTH + " chars with A-Z, a-z, 0-9 & a symbol"
                : "Enter your password");
        confirmPasswordField.setPlaceholder(isReset ? "Re-enter your new password" : "Re-enter your password");
        confirmSection.setVisible(needsStrength);
        strengthPanel.setVisible(needsStrength);
        showPasswordWrap.setVisible(isSignIn || isSignUp || isReset);
        forgotPasswordLink.setVisible(isSignIn);
        if (!isSignIn && !isSignUp && !isReset) {
            showPasswordBox.setSelected(false);
        }
        otpSection.setVisible(isVerify);
        updatePasswordFeedback();

        Optional<LocalSnapshotCache.CachedSnapshot> activeSnapshot =
                (localCache != null && onOfflineOpen != null) ? localCache.loadActiveSessionSnapshot() : Optional.empty();
        boolean activeOffline = activeSnapshot.isPresent();

        offlineNoticeWrap.setVisible(networkOffline);
        if (networkOffline) {
            offlineNoticeIcon.setIcon(Icons.of(Icons.Type.ALERT, 15, Theme.AMBER));
            offlineNoticeText.setForeground(Theme.AMBER);
            offlineNoticeText.setText(activeOffline
                    ? "You are offline. Network required to sign in, or continue in read-only mode."
                    : "You are offline. Network required to sign in or create an account.");
            offlineNoticeText.setSize(new Dimension(DIALOG_WIDTH - 210, Short.MAX_VALUE));
        }

        boolean canContinueOffline = networkOffline && activeOffline;
        backToSignInWrap.setVisible(isForgot || isReset);
        offlineWrap.setVisible(isSignIn && canContinueOffline);
        if (canContinueOffline) {
            offlineBtn.setToolTipText("View cached read-only data for " + activeSnapshot.get().email());
        } else {
            offlineBtn.setToolTipText(null);
        }
        resendBtn.setText("Resend Code");
        resendWrap.setVisible(isVerify || isReset);

        linkWrap.setVisible(!isForgot && !isReset);
        switchModeLink.setText(switch (mode) {
            case SIGN_IN -> "Don't have an account? Create one";
            case SIGN_UP -> "Already have an account? Sign in";
            case VERIFY_OTP, FORGOT_PASSWORD, RESET_PASSWORD -> "Back to Sign In";
        });

        applyInteractiveState(canContinueOffline);
    }

    private String defaultSubmitLabel() {
        return switch (mode) {
            case SIGN_IN -> "Sign In";
            case SIGN_UP -> "Create Account";
            case VERIFY_OTP -> "Verify Code";
            case FORGOT_PASSWORD -> "Send Code";
            case RESET_PASSWORD -> "Reset Password";
        };
    }

    private String offlineSubmitLabel() {
        return switch (mode) {
            case SIGN_IN -> "Network required to sign in";
            case SIGN_UP -> "Network required to create account";
            case VERIFY_OTP -> "Network required to verify";
            case FORGOT_PASSWORD -> "Network required to send code";
            case RESET_PASSWORD -> "Network required to reset password";
        };
    }

    private void applyInteractiveState(boolean activeOffline) {
        boolean formEnabled = !busy && !networkOffline;
        emailField.setEnabled(formEnabled);
        recoveryCodeField.setEnabled(formEnabled);
        passwordField.setEnabled(formEnabled);
        confirmPasswordField.setEnabled(formEnabled);
        otpField.setEnabled(formEnabled);
        showPasswordBox.setEnabled(formEnabled);
        forgotPasswordLink.setEnabled(!busy);
        backToSignInBtn.setEnabled(!busy);
        signInTab.setEnabled(!busy);
        signUpTab.setEnabled(!busy);
        switchModeLink.setEnabled(!busy);
        submitBtn.setEnabled(formEnabled);
        if (!busy) {
            submitBtn.setText(networkOffline ? offlineSubmitLabel() : defaultSubmitLabel());
        }
        resendBtn.setEnabled(formEnabled);
        offlineBtn.setEnabled(!busy && networkOffline && activeOffline);
        retryConnectBtn.setEnabled(!busy && !checkingConnectivity);
    }

    private void submit() {
        if (busy || networkOffline) {
            return;
        }
        clearBanner();
        clearFieldErrors();

        if (mode == Mode.VERIFY_OTP) {
            submitVerifyOtp();
            return;
        }
        if (mode == Mode.FORGOT_PASSWORD) {
            submitForgotPassword();
            return;
        }
        if (mode == Mode.RESET_PASSWORD) {
            submitResetPassword();
            return;
        }

        String email = emailField.getText().trim();
        char[] passwordChars = passwordField.getPassword();
        char[] confirmChars = confirmPasswordField.getPassword();
        EnumSet<PasswordPolicy.Rule> unmetRules = PasswordPolicy.unmet(passwordChars);
        boolean confirmMatches = Arrays.equals(passwordChars, confirmChars);
        String password = new String(passwordChars);
        Arrays.fill(passwordChars, '\0');
        Arrays.fill(confirmChars, '\0');

        if (!SupabaseAuthService.isValidEmail(email)) {
            UI.setError(emailField, true);
            showBanner(email.isEmpty() ? "Please enter your email address." : "Please enter a valid email address.", true);
            emailField.requestFocusInWindow();
            return;
        }
        if (password.isEmpty()) {
            UI.setError(passwordField, true);
            showBanner("Please enter your password.", true);
            passwordField.requestFocusInWindow();
            return;
        }
        if (mode == Mode.SIGN_UP) {
            if (!unmetRules.isEmpty()) {
                strengthAttempted = true;
                updatePasswordFeedback();
                UI.setError(passwordField, true);
                showBanner(PasswordPolicy.describe(unmetRules), true);
                passwordField.requestFocusInWindow();
                return;
            }
            if (!confirmMatches) {
                UI.setError(confirmPasswordField, true);
                showBanner("Passwords do not match.", true);
                confirmPasswordField.requestFocusInWindow();
                return;
            }
        }

        final boolean creating = mode == Mode.SIGN_UP;
        setBusy(true, creating ? "Creating account..." : "Signing in...", "Resend Code");

        SwingWorker<Object, Void> worker = new SwingWorker<>() {
            @Override
            protected Object doInBackground() throws Exception {
                if (creating) {
                    SupabaseAuthService.SignUpResult signUpResult = authService.signUp(email, password);
                    if (!signUpResult.confirmationRequired() && onAuthenticatedLoad != null) {
                        SwingUtilities.invokeLater(() -> submitBtn.setText("Loading data..."));
                        try {
                            onAuthenticatedLoad.run();
                        } catch (RuntimeException loadError) {
                            authService.signOut();
                            throw loadError;
                        }
                    }
                    return signUpResult;
                }
                Object session = authService.signIn(email, password);
                if (onAuthenticatedLoad != null) {
                    SwingUtilities.invokeLater(() -> submitBtn.setText("Loading data..."));
                    try {
                        onAuthenticatedLoad.run();
                    } catch (RuntimeException loadError) {
                        authService.signOut();
                        throw loadError;
                    }
                }
                return session;
            }

            @Override
            protected void done() {
                setBusy(false, defaultSubmitLabel(), "Resend Code");
                try {
                    Object result = get();
                    if (result instanceof SupabaseAuthService.SignUpResult signUpResult
                            && signUpResult.confirmationRequired()) {
                        pendingVerifyEmail = signUpResult.email();
                        emailField.setText(pendingVerifyEmail);
                        passwordField.setText("");
                        confirmPasswordField.setText("");
                        otpField.setText("");
                        mode = Mode.VERIFY_OTP;
                        updateModeUI();
                        showBanner(
                                "Verification code sent to " + pendingVerifyEmail
                                        + ". Enter the code below to verify your email.",
                                false);
                        SwingUtilities.invokeLater(otpField::requestFocusInWindow);
                        return;
                    }
                    authenticated = true;
                    dispose();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showBanner("Authentication was interrupted. Please try again.", true);
                } catch (ExecutionException error) {
                    if (SupabaseRestClient.isConnectivityFailure(error.getCause())) {
                        clearBanner();
                        applyConnectivityState(false);
                    } else {
                        showBanner(errorMessage(error, "Authentication failed. Please try again."), true);
                    }
                }
            }
        };
        worker.execute();
    }

    private void submitForgotPassword() {
        String email = emailField.getText().trim();
        if (email.isEmpty()) {
            UI.setError(emailField, true);
            showBanner("Please enter your email address.", true);
            emailField.requestFocusInWindow();
            return;
        }
        if (!SupabaseAuthService.isValidEmail(email)) {
            UI.setError(emailField, true);
            showBanner("Please enter a valid email address.", true);
            emailField.requestFocusInWindow();
            return;
        }

        final String targetEmail = email;
        setBusy(true, "Sending code...", "Resend Code");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                authService.sendPasswordResetEmail(targetEmail);
                return null;
            }

            @Override
            protected void done() {
                setBusy(false, defaultSubmitLabel(), "Resend Code");
                try {
                    get();
                    pendingResetEmail = targetEmail;
                    emailField.setText(pendingResetEmail);
                    recoveryCodeField.setText("");
                    passwordField.setText("");
                    confirmPasswordField.setText("");
                    strengthAttempted = false;
                    showPasswordBox.setSelected(false);
                    mode = Mode.RESET_PASSWORD;
                    updateModeUI();
                    showBanner(
                            "Recovery code sent to " + pendingResetEmail
                                    + ". Check your inbox or spam folder and enter the 6-digit code below.",
                            false);
                    SwingUtilities.invokeLater(recoveryCodeField::requestFocusInWindow);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showBanner("Password reset request was interrupted. Please try again.", true);
                } catch (ExecutionException error) {
                    if (SupabaseRestClient.isConnectivityFailure(error.getCause())) {
                        clearBanner();
                        applyConnectivityState(false);
                    } else {
                        showBanner(
                                errorMessage(error, "Unable to send recovery code. Please try again."),
                                true);
                    }
                }
            }
        };
        worker.execute();
    }

    private void submitResetPassword() {
        String targetEmail = pendingResetEmail != null && !pendingResetEmail.isBlank()
                ? pendingResetEmail
                : emailField.getText().trim();
        String code = recoveryCodeField.getText().trim();
        char[] passwordChars = passwordField.getPassword();
        char[] confirmChars = confirmPasswordField.getPassword();
        EnumSet<PasswordPolicy.Rule> unmetRules = PasswordPolicy.unmet(passwordChars);
        boolean confirmMatches = Arrays.equals(passwordChars, confirmChars);
        String newPassword = new String(passwordChars);
        Arrays.fill(passwordChars, '\0');
        Arrays.fill(confirmChars, '\0');

        if (!SupabaseAuthService.isValidEmail(targetEmail)) {
            UI.setError(emailField, true);
            showBanner("Please enter a valid email address.", true);
            return;
        }
        if (code.isEmpty()) {
            UI.setError(recoveryCodeField, true);
            showBanner("Please enter the 6-digit recovery code from your email.", true);
            recoveryCodeField.requestFocusInWindow();
            return;
        }
        if (!InputFilters.isValidRecoveryCode(code)) {
            UI.setError(recoveryCodeField, true);
            showBanner("Recovery code must be 6 to 8 digits.", true);
            recoveryCodeField.requestFocusInWindow();
            return;
        }
        if (newPassword.isEmpty()) {
            UI.setError(passwordField, true);
            showBanner("Please enter a new password.", true);
            passwordField.requestFocusInWindow();
            return;
        }
        if (!unmetRules.isEmpty()) {
            strengthAttempted = true;
            updatePasswordFeedback();
            UI.setError(passwordField, true);
            showBanner(PasswordPolicy.describe(unmetRules), true);
            passwordField.requestFocusInWindow();
            return;
        }
        if (!confirmMatches) {
            UI.setError(confirmPasswordField, true);
            showBanner("Passwords do not match.", true);
            confirmPasswordField.requestFocusInWindow();
            return;
        }

        final String resetEmail = targetEmail;
        setBusy(true, "Resetting password...", "Resend Code");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                authService.resetPasswordWithRecoveryCode(resetEmail, code, newPassword);
                return null;
            }

            @Override
            protected void done() {
                setBusy(false, defaultSubmitLabel(), "Resend Code");
                try {
                    get();
                    pendingResetEmail = null;
                    mode = Mode.SIGN_IN;
                    emailField.setText(resetEmail);
                    recoveryCodeField.setText("");
                    passwordField.setText("");
                    confirmPasswordField.setText("");
                    showPasswordBox.setSelected(false);
                    updateModeUI();
                    showBanner("Password updated successfully! You can now log in.", false);
                    SwingUtilities.invokeLater(passwordField::requestFocusInWindow);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showBanner("Password reset was interrupted. Please try again.", true);
                } catch (ExecutionException error) {
                    if (SupabaseRestClient.isConnectivityFailure(error.getCause())) {
                        clearBanner();
                        applyConnectivityState(false);
                    } else {
                        String msg = errorMessage(error, "Unable to reset password. Please try again.");
                        String lower = msg.toLowerCase();
                        if (lower.contains("code") || lower.contains("token") || lower.contains("otp") || lower.contains("expired")) {
                            UI.setError(recoveryCodeField, true);
                            recoveryCodeField.requestFocusInWindow();
                        }
                        showBanner(msg, true);
                    }
                }
            }
        };
        worker.execute();
    }

    private void submitVerifyOtp() {
        String code = otpField.getText().trim();
        if (!InputFilters.isValidVerificationCode(code)) {
            UI.setError(otpField, true);
            showBanner(code.isEmpty()
                    ? "Please enter the 8-digit verification code from your email."
                    : "Verification code must be exactly 8 digits.", true);
            otpField.requestFocusInWindow();
            return;
        }

        final String targetEmail = pendingVerifyEmail;
        setBusy(true, "Verifying...", "Resend Code");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                authService.verifySignupOtp(targetEmail, code);
                return null;
            }

            @Override
            protected void done() {
                setBusy(false, defaultSubmitLabel(), "Resend Code");
                try {
                    get();
                    mode = Mode.SIGN_IN;
                    emailField.setText(targetEmail);
                    passwordField.setText("");
                    confirmPasswordField.setText("");
                    otpField.setText("");
                    updateModeUI();
                    showBanner("Email verified! Please sign in.", false);
                    SwingUtilities.invokeLater(passwordField::requestFocusInWindow);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showBanner("Verification was interrupted. Please try again.", true);
                } catch (ExecutionException error) {
                    if (SupabaseRestClient.isConnectivityFailure(error.getCause())) {
                        clearBanner();
                        applyConnectivityState(false);
                    } else {
                        UI.setError(otpField, true);
                        showBanner(errorMessage(error, "Invalid or expired verification code."), true);
                        otpField.requestFocusInWindow();
                    }
                }
            }
        };
        worker.execute();
    }

    private void resendCode() {
        boolean isVerifyOtp = mode == Mode.VERIFY_OTP;
        boolean isResetPassword = mode == Mode.RESET_PASSWORD;
        String targetEmail = isVerifyOtp ? pendingVerifyEmail : isResetPassword ? pendingResetEmail : null;
        if (busy || networkOffline || targetEmail == null || targetEmail.isBlank()) {
            return;
        }
        clearBanner();
        clearFieldErrors();

        setBusy(true, defaultSubmitLabel(), "Resending...");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (isResetPassword) {
                    authService.sendPasswordResetEmail(targetEmail);
                } else {
                    authService.resendSignupOtp(targetEmail);
                }
                return null;
            }

            @Override
            protected void done() {
                setBusy(false, defaultSubmitLabel(), "Resend Code");
                try {
                    get();
                    showBanner(
                            isResetPassword
                                    ? "A new recovery code has been sent to " + targetEmail + "."
                                    : "A new verification code has been sent to " + targetEmail + ".",
                            false);
                    SwingUtilities.invokeLater((isResetPassword ? recoveryCodeField : otpField)::requestFocusInWindow);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showBanner("Resend request was interrupted. Please try again.", true);
                } catch (ExecutionException error) {
                    if (SupabaseRestClient.isConnectivityFailure(error.getCause())) {
                        clearBanner();
                        applyConnectivityState(false);
                    } else {
                        showBanner(
                                errorMessage(error,
                                        isResetPassword
                                                ? "Unable to resend recovery code."
                                                : "Unable to resend verification code."),
                                true);
                    }
                }
            }
        };
        worker.execute();
    }

    private void setBusy(boolean isBusy, String submitLabel, String resendLabel) {
        this.busy = isBusy;
        applyInteractiveState(hasActiveOfflineSession());
        if (isBusy) {
            submitBtn.setText(submitLabel);
        }
        resendBtn.setText(resendLabel);
    }

    private void showBanner(String message, boolean error) {
        bannerPanel.setColors(error ? Theme.RED_BG : Theme.GREEN_BG, null);
        bannerIcon.setIcon(Icons.of(error ? Icons.Type.ALERT : Icons.Type.CHECK_CIRCLE, 15, error ? Theme.RED : Theme.GREEN));
        bannerText.setForeground(error ? Theme.RED : Theme.GREEN);
        bannerText.setText(message);
        bannerText.setSize(new Dimension(DIALOG_WIDTH - 156, Short.MAX_VALUE));
        bannerWrap.setVisible(true);
        fitDialog();
    }

    private void clearBanner() {
        bannerWrap.setVisible(false);
        bannerText.setText("");
    }

    private void clearFieldErrors() {
        UI.setError(emailField, false);
        UI.setError(recoveryCodeField, false);
        UI.setError(passwordField, false);
        UI.setError(confirmPasswordField, false);
        UI.setError(otpField, false);
    }

    private void fitDialog() {
        pack();
        int width = Math.max(DIALOG_WIDTH, getPreferredSize().width);
        setSize(width, getPreferredSize().height);
        validate();
    }

    private static String errorMessage(ExecutionException error, String fallback) {
        Throwable cause = error.getCause();
        if (cause instanceof SupabaseAuthException && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return SupabaseAuthService.sanitizeErrorMessage(cause.getMessage(), fallback);
        }
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return SupabaseAuthService.sanitizeErrorMessage(cause.getMessage(), fallback);
        }
        return fallback;
    }

    private static JPanel wrapSection(JComponent content, int bottomGap) {
        JPanel wrap = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        wrap.setOpaque(false);
        wrap.setBorder(new EmptyBorder(0, 0, bottomGap, 0));
        wrap.add(content, BorderLayout.CENTER);
        return wrap;
    }

    // ---- Password feedback / visibility -------------------------------

    /** Toggles the echo character on both password fields according to the checkbox. */
    private void applyPasswordVisibility() {
        char echo = showPasswordBox.isSelected() ? (char) 0 : defaultEchoChar;
        passwordField.setEchoChar(echo);
        confirmPasswordField.setEchoChar(echo);
        passwordField.repaint();
        confirmPasswordField.repaint();
    }

    /** Refreshes the strength checklist and the "passwords match" hint (sign-up & password reset). */
    private void updatePasswordFeedback() {
        boolean hintWasVisible = matchHint.isVisible();
        if (mode != Mode.SIGN_UP && mode != Mode.RESET_PASSWORD) {
            matchHint.setVisible(false);
            if (hintWasVisible) fitDialog();
            return;
        }
        char[] pw = passwordField.getPassword();
        char[] confirm = confirmPasswordField.getPassword();
        EnumSet<PasswordPolicy.Rule> unmet = PasswordPolicy.unmet(pw);
        boolean confirmEmpty = confirm.length == 0;
        boolean matches = Arrays.equals(pw, confirm);
        Arrays.fill(pw, '\0');
        Arrays.fill(confirm, '\0');

        for (Map.Entry<PasswordPolicy.Rule, JLabel> entry : ruleLabels.entrySet()) {
            boolean met = !unmet.contains(entry.getKey());
            Color color = met ? Theme.GREEN : (strengthAttempted ? Theme.RED : Theme.MUTED);
            JLabel row = entry.getValue();
            row.setIcon(Icons.of(met ? Icons.Type.CHECK_CIRCLE : Icons.Type.X_CIRCLE, 13, color));
            row.setForeground(met ? Theme.GREEN : (strengthAttempted ? Theme.RED : Theme.TEXT_2));
        }
        if (unmet.isEmpty()) {
            UI.setError(passwordField, false);
        }

        if (confirmEmpty) {
            matchHint.setVisible(false);
        } else {
            matchHint.setText(matches ? "Passwords match" : "Passwords do not match");
            matchHint.setForeground(matches ? Theme.GREEN : Theme.RED);
            matchHint.setIcon(Icons.of(matches ? Icons.Type.CHECK_CIRCLE : Icons.Type.X_CIRCLE, 13,
                    matches ? Theme.GREEN : Theme.RED));
            matchHint.setVisible(true);
            if (matches) UI.setError(confirmPasswordField, false);
        }
        if (hintWasVisible != matchHint.isVisible()) {
            fitDialog();
        }
    }

    /** Wipes typed secrets and hides passwords again whenever the dialog closes. */
    @Override
    public void dispose() {
        showPasswordBox.setSelected(false);
        recoveryCodeField.setText("");
        passwordField.setText("");
        confirmPasswordField.setText("");
        otpField.setText("");
        super.dispose();
    }

    private static JLabel fieldLabel(String text) {
        JLabel l = UI.label(text, Theme.BODY_B, Theme.TEXT);
        l.setBorder(new EmptyBorder(0, 0, 6, 0));
        return l;
    }

    /** Vertical section whose height follows its visible children (hidden rows collapse cleanly). */
    private static JPanel dynamicSection(int bottomGap, JComponent... children) {
        JPanel panel = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(0, 0, bottomGap, 0));
        for (JComponent child : children) {
            child.setAlignmentX(0f);
            panel.add(child);
        }
        return panel;
    }

    private static JPanel labeledField(String label, JComponent field, int bottomGap) {
        JLabel l = UI.label(label, Theme.BODY_B, Theme.TEXT);
        JPanel panel = UI.vstack(6, l, field);
        panel.setBorder(new EmptyBorder(0, 0, bottomGap, 0));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }
}
