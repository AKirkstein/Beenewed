package beenewed.dialogs;

import beenewed.ui.Icons;
import beenewed.ui.Theme;
import beenewed.ui.UI;
import com.beenewed.supabase.SupabaseAuthException;
import com.beenewed.supabase.SupabaseAuthService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/**
 * Startup authentication dialog supporting Supabase Sign In, Create Account, and
 * Email OTP verification flows, styled with Beenewed's Swing theme.
 */
public final class LoginDialog extends JDialog {
    private static final int DIALOG_WIDTH = 440;

    private enum Mode { SIGN_IN, SIGN_UP, VERIFY_OTP }

    private final SupabaseAuthService authService;

    private Mode mode = Mode.SIGN_IN;
    private String pendingVerifyEmail;
    private boolean authenticated = false;
    private boolean busy = false;

    private final UI.Btn signInTab = UI.soft("Sign In", null);
    private final UI.Btn signUpTab = UI.ghost("Create Account", null);
    private final JPanel modeBarWrap;

    private final JLabel headingLabel = UI.h2("Welcome back");
    private final JLabel subtitleLabel = UI.label("Sign in to access your Beenewed subscriptions and preferences.", Theme.SMALL, Theme.MUTED);

    private final UI.RoundPanel bannerPanel = new UI.RoundPanel(new BorderLayout(8, 0), 8, Theme.RED_BG, null);
    private final JLabel bannerIcon = new JLabel();
    private final JTextArea bannerText = new JTextArea();
    private final JPanel bannerWrap;

    private final JLabel verifyEmailLabel = UI.label("", Theme.BODY_B, Theme.TEXT);
    private final JPanel verifyEmailWrap;

    private final UI.TextField emailField = new UI.TextField("you@example.com");
    private final UI.PasswordField passwordField = new UI.PasswordField("Enter your password");
    private final UI.PasswordField confirmPasswordField = new UI.PasswordField("Re-enter your password");
    private final UI.TextField otpField = new UI.TextField("Enter verification code");

    private final JPanel emailSection;
    private final JPanel passwordSection;
    private final JPanel confirmSection;
    private final JPanel otpSection;

    private final UI.Btn submitBtn = UI.primary("Sign In", null);
    private final UI.Btn resendBtn = UI.secondary("Resend Code", null);
    private final JPanel resendWrap;
    private final UI.Btn switchModeLink = UI.link("Don't have an account? Create one");

    public LoginDialog(Frame owner, SupabaseAuthService authService) {
        super(owner, "Beenewed \u2014 Sign In", true);
        this.authService = authService;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(Theme.BG);
        outer.setBorder(new EmptyBorder(24, 24, 24, 24));

        UI.RoundPanel card = new UI.RoundPanel(new BorderLayout(), 14, Theme.SURFACE, Theme.BORDER);
        card.setBorder(new EmptyBorder(24, 26, 22, 26));

        // Brand header
        UI.RoundPanel logo = new UI.RoundPanel(new GridBagLayout(), 8, Theme.PRIMARY, null);
        logo.add(new JLabel(Icons.of(Icons.Type.WALLET, 16, Theme.ON_PRIMARY)));
        logo.setPreferredSize(new Dimension(28, 28));
        logo.setMaximumSize(new Dimension(28, 28));
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
        passwordSection = labeledField("Password", passwordField, 12);
        confirmSection = labeledField("Confirm Password", confirmPasswordField, 12);
        confirmSection.setVisible(false);
        otpSection = labeledField("Verification Code", otpField, 14);
        otpSection.setVisible(false);

        // Primary submit button
        submitBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        submitBtn.setPreferredSize(new Dimension(DIALOG_WIDTH - 100, 40));
        submitBtn.addActionListener(e -> submit());
        JPanel submitWrap = wrapSection(submitBtn, 10);
        submitWrap.setBorder(new EmptyBorder(6, 0, 10, 0));

        // Resend code button (Verify Email mode only)
        resendBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        resendBtn.setPreferredSize(new Dimension(DIALOG_WIDTH - 100, 38));
        resendBtn.addActionListener(e -> resendCode());
        resendWrap = wrapSection(resendBtn, 12);
        resendWrap.setVisible(false);

        // Bottom link
        switchModeLink.addActionListener(e -> handleSwitchLink());
        JPanel linkWrap = UI.clear(new FlowLayout(FlowLayout.CENTER, 0, 0));
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
                bannerWrap,
                verifyEmailWrap,
                emailSection,
                passwordSection,
                confirmSection,
                otpSection,
                submitWrap,
                resendWrap,
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
                        dispose();
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
        SwingUtilities.invokeLater(emailField::requestFocusInWindow);
        setVisible(true);
        return authenticated;
    }

    private void switchMode(Mode target) {
        if (busy || this.mode == target) {
            return;
        }
        this.mode = target;
        clearBanner();
        clearFieldErrors();
        confirmPasswordField.setText("");
        if (target == Mode.SIGN_IN && pendingVerifyEmail != null && !pendingVerifyEmail.isBlank()) {
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
            case SIGN_UP, VERIFY_OTP -> {
                switchMode(Mode.SIGN_IN);
                SwingUtilities.invokeLater(() -> {
                    if (emailField.getText().isBlank()) {
                        emailField.requestFocusInWindow();
                    } else {
                        passwordField.requestFocusInWindow();
                    }
                });
            }
        }
    }

    private void updateModeUI() {
        boolean isSignIn = mode == Mode.SIGN_IN;
        boolean isSignUp = mode == Mode.SIGN_UP;
        boolean isVerify = mode == Mode.VERIFY_OTP;

        setTitle(switch (mode) {
            case SIGN_IN -> "Beenewed \u2014 Sign In";
            case SIGN_UP -> "Beenewed \u2014 Create Account";
            case VERIFY_OTP -> "Beenewed \u2014 Verify Email";
        });

        modeBarWrap.setVisible(!isVerify);
        signInTab.setKind(isSignIn ? UI.Kind.SOFT : UI.Kind.GHOST);
        signUpTab.setKind(isSignUp ? UI.Kind.SOFT : UI.Kind.GHOST);

        headingLabel.setText(switch (mode) {
            case SIGN_IN -> "Welcome back";
            case SIGN_UP -> "Create your account";
            case VERIFY_OTP -> "Verify your email";
        });
        subtitleLabel.setText(switch (mode) {
            case SIGN_IN -> "Sign in to access your Beenewed subscriptions and preferences.";
            case SIGN_UP -> "Set up an account to store your subscriptions securely in Supabase.";
            case VERIFY_OTP -> "Enter the verification code sent to your email address.";
        });

        verifyEmailLabel.setText(pendingVerifyEmail == null ? "" : pendingVerifyEmail);
        verifyEmailWrap.setVisible(isVerify);

        emailSection.setVisible(!isVerify);
        passwordSection.setVisible(!isVerify);
        passwordField.setPlaceholder(isSignUp ? "At least 6 characters" : "Enter your password");
        confirmSection.setVisible(isSignUp);
        otpSection.setVisible(isVerify);

        submitBtn.setText(defaultSubmitLabel());
        resendBtn.setText("Resend Code");
        resendWrap.setVisible(isVerify);

        switchModeLink.setText(switch (mode) {
            case SIGN_IN -> "Don't have an account? Create one";
            case SIGN_UP -> "Already have an account? Sign in";
            case VERIFY_OTP -> "Back to Sign In";
        });
    }

    private String defaultSubmitLabel() {
        return switch (mode) {
            case SIGN_IN -> "Sign In";
            case SIGN_UP -> "Create Account";
            case VERIFY_OTP -> "Verify Code";
        };
    }

    private void submit() {
        if (busy) {
            return;
        }
        clearBanner();
        clearFieldErrors();

        if (mode == Mode.VERIFY_OTP) {
            submitVerifyOtp();
            return;
        }

        String email = emailField.getText().trim();
        char[] passwordChars = passwordField.getPassword();
        char[] confirmChars = confirmPasswordField.getPassword();
        String password = new String(passwordChars);
        String confirm = new String(confirmChars);
        Arrays.fill(passwordChars, '\0');
        Arrays.fill(confirmChars, '\0');

        if (email.isEmpty() || !email.contains("@")) {
            UI.setError(emailField, true);
            showBanner("Please enter a valid email address.", true);
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
            if (password.length() < 6) {
                UI.setError(passwordField, true);
                showBanner("Password must be at least 6 characters.", true);
                passwordField.requestFocusInWindow();
                return;
            }
            if (!password.equals(confirm)) {
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
                    return authService.signUp(email, password);
                }
                return authService.signIn(email, password);
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
                    showBanner(errorMessage(error, "Authentication failed. Please try again."), true);
                }
            }
        };
        worker.execute();
    }

    private void submitVerifyOtp() {
        String code = otpField.getText().trim();
        if (code.isEmpty()) {
            UI.setError(otpField, true);
            showBanner("Please enter the verification code from your email.", true);
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
                    UI.setError(otpField, true);
                    showBanner(errorMessage(error, "Invalid or expired verification code."), true);
                    otpField.requestFocusInWindow();
                }
            }
        };
        worker.execute();
    }

    private void resendCode() {
        if (busy || mode != Mode.VERIFY_OTP || pendingVerifyEmail == null || pendingVerifyEmail.isBlank()) {
            return;
        }
        clearBanner();
        clearFieldErrors();

        final String targetEmail = pendingVerifyEmail;
        setBusy(true, defaultSubmitLabel(), "Resending...");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                authService.resendSignupOtp(targetEmail);
                return null;
            }

            @Override
            protected void done() {
                setBusy(false, defaultSubmitLabel(), "Resend Code");
                try {
                    get();
                    showBanner("A new verification code has been sent to " + targetEmail + ".", false);
                    SwingUtilities.invokeLater(otpField::requestFocusInWindow);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showBanner("Resend request was interrupted. Please try again.", true);
                } catch (ExecutionException error) {
                    showBanner(errorMessage(error, "Unable to resend verification code."), true);
                }
            }
        };
        worker.execute();
    }

    private void setBusy(boolean isBusy, String submitLabel, String resendLabel) {
        this.busy = isBusy;
        emailField.setEnabled(!isBusy);
        passwordField.setEnabled(!isBusy);
        confirmPasswordField.setEnabled(!isBusy);
        otpField.setEnabled(!isBusy);
        signInTab.setEnabled(!isBusy);
        signUpTab.setEnabled(!isBusy);
        switchModeLink.setEnabled(!isBusy);
        submitBtn.setEnabled(!isBusy);
        submitBtn.setText(submitLabel);
        resendBtn.setEnabled(!isBusy);
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
            return cause.getMessage();
        }
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
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

    private static JPanel labeledField(String label, JComponent field, int bottomGap) {
        JLabel l = UI.label(label, Theme.BODY_B, Theme.TEXT);
        JPanel panel = UI.vstack(6, l, field);
        panel.setBorder(new EmptyBorder(0, 0, bottomGap, 0));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }
}
