package beenewed.dialogs;

import beenewed.ui.Theme;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the 2-step in-app OTP Forgot Password & Reset Password flow in {@link LoginDialog}:
 * - Step 1: Forgot Password link -> email validation -> POST /auth/v1/recover -> transitions to Step 2
 * - Step 2: Prefilled read-only email, 6-digit recovery code, new password + confirm validation ->
 *           POST /auth/v1/verify (type=recovery) + PUT /auth/v1/user -> returns to Sign In with success message
 * - Resend Code, API error handling, Dark theme, and offline gating
 */
class LoginDialogForgotPasswordTest {

    record CapturedRequest(String method, String path, String apiKey, String authorization, String body) {
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean met = new AtomicBoolean(false);
            SwingUtilities.invokeAndWait(() -> met.set(condition.getAsBoolean()));
            if (met.get()) {
                return;
            }
            Thread.sleep(25);
        }
        AtomicBoolean met = new AtomicBoolean(false);
        SwingUtilities.invokeAndWait(() -> met.set(condition.getAsBoolean()));
        assertTrue(met.get(), "Condition was not satisfied within timeout");
    }

    @Test
    void twoStepOtpForgotAndResetPasswordFlowEndToEnd() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a display to construct JDialog");

        List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
        AtomicInteger verifyStatus = new AtomicInteger(200);
        AtomicReference<String> verifyResponse = new AtomicReference<>(
                "{\"access_token\":\"recovery-token-1\",\"refresh_token\":\"recovery-refresh-1\","
                        + "\"expires_in\":3600,\"user\":{\"id\":\"u1\",\"email\":\"alice@example.com\"}}");
        CountDownLatch recoverEntered = new CountDownLatch(1);
        CountDownLatch allowRecover = new CountDownLatch(1);
        AtomicBoolean holdFirstRecover = new AtomicBoolean(true);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/v1/", (HttpExchange ex) -> {
            String path = ex.getRequestURI().getPath();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(
                    ex.getRequestMethod(),
                    path,
                    ex.getRequestHeaders().getFirst("apikey"),
                    ex.getRequestHeaders().getFirst("Authorization"),
                    body));

            int status = 200;
            String response = "{}";
            if (path.equals("/auth/v1/recover")) {
                if (holdFirstRecover.compareAndSet(true, false)) {
                    recoverEntered.countDown();
                    try {
                        allowRecover.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else if (path.equals("/auth/v1/verify")) {
                status = verifyStatus.get();
                response = verifyResponse.get();
            } else if (path.equals("/auth/v1/user")) {
                response = "{\"id\":\"u1\",\"email\":\"alice@example.com\"}";
            } else if (path.equals("/auth/v1/logout")) {
                status = 204;
            }

            if (status == 204) {
                ex.sendResponseHeaders(204, -1);
            } else {
                byte[] resp = response.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(status, resp.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(resp);
                }
            }
            ex.close();
        });
        server.start();

        SupabaseAuthService auth = new SupabaseAuthService(
                new SupabaseConfig("http://127.0.0.1:" + server.getAddress().getPort(), "ui-test-key"));
        AtomicReference<LoginDialog> dialogRef = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(false);
                LoginDialog dialog = new LoginDialog(null, auth, () -> {}, null, null, () -> true);
                dialogRef.set(dialog);

                // Sign In shows Forgot Password? link and carries over typed email to Step 1
                assertTrue(dialog.isSignInModeForTest());
                assertTrue(dialog.isForgotPasswordLinkVisibleForTest());
                dialog.setEmailForTest("alice@example.com");
                dialog.clickForgotPasswordForTest();

                assertTrue(dialog.isForgotPasswordModeForTest());
                assertEquals("alice@example.com", dialog.getEmailForTest());
                assertTrue(dialog.isEmailEditableForTest());
                assertFalse(dialog.isPasswordSectionVisibleForTest());
                assertEquals("Send Code", dialog.getSubmitButtonTextForTest());
                assertTrue(dialog.isBackToSignInVisibleForTest());

                // Step 1 validation: empty and invalid emails rejected locally
                dialog.setEmailForTest("   ");
                dialog.clickSubmitForTest();
                assertTrue(dialog.isBannerVisibleForTest());
                assertEquals(Theme.RED, dialog.getBannerForegroundForTest());

                dialog.setEmailForTest("bad@domain");
                dialog.clickSubmitForTest();
                assertTrue(dialog.getBannerTextForTest().toLowerCase().contains("valid email"));
                assertTrue(requests.isEmpty());

                // Step 1 valid email submits POST /auth/v1/recover and disables controls while busy
                dialog.setEmailForTest("alice@example.com");
                dialog.clickSubmitForTest();
                assertTrue(dialog.isBusyForTest());
                assertFalse(dialog.isSubmitEnabledForTest());
                assertFalse(dialog.isBackToSignInEnabledForTest());
                assertTrue(dialog.getSubmitButtonTextForTest().toLowerCase().contains("sending"));
            });

            assertTrue(recoverEntered.await(5, TimeUnit.SECONDS), "Recovery HTTP request should start");
            allowRecover.countDown();

            LoginDialog dialog = dialogRef.get();
            awaitCondition(() -> !dialog.isBusyForTest() && dialog.isResetPasswordModeForTest());

            // Verify Step 2 UI state and client-side validations
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(dialog.isResetPasswordModeForTest(), "Successful Send Code must transition to Step 2");
                assertEquals("alice@example.com", dialog.getEmailForTest());
                assertFalse(dialog.isEmailEditableForTest(), "Email must be read-only in Step 2");
                assertTrue(dialog.isPasswordSectionVisibleForTest());
                assertEquals("Reset Password", dialog.getSubmitButtonTextForTest());
                assertTrue(dialog.isResendVisibleForTest());
                assertTrue(dialog.isBackToSignInVisibleForTest());
                assertEquals(Theme.GREEN, dialog.getBannerForegroundForTest());

                // Validation: empty code
                dialog.setRecoveryCodeForTest("");
                dialog.setPasswordForTest("NewPassw0rd!");
                dialog.setConfirmPasswordForTest("NewPassw0rd!");
                dialog.clickSubmitForTest();
                assertEquals(Theme.RED, dialog.getBannerForegroundForTest());
                assertTrue(dialog.getBannerTextForTest().toLowerCase().contains("recovery code"));

                // Validation: short code (< 6 digits)
                dialog.setRecoveryCodeForTest("12345");
                dialog.clickSubmitForTest();
                assertTrue(dialog.getBannerTextForTest().toLowerCase().contains("6 to 8 digits"));

                // Validation: weak password
                dialog.setRecoveryCodeForTest("123456");
                dialog.setPasswordForTest("short");
                dialog.setConfirmPasswordForTest("short");
                dialog.clickSubmitForTest();
                assertTrue(dialog.getBannerTextForTest().startsWith("Password must contain"));

                // Validation: password mismatch
                dialog.setPasswordForTest("NewPassw0rd!");
                dialog.setConfirmPasswordForTest("Different0rd!");
                dialog.clickSubmitForTest();
                assertTrue(dialog.getBannerTextForTest().contains("do not match"));
                assertEquals(1, requests.size(), "Client-side validation must prevent extra network calls");

                // Test invalid OTP server error
                verifyStatus.set(403);
                verifyResponse.set("{\"msg\":\"Token has expired or is invalid\"}");
                dialog.setConfirmPasswordForTest("NewPassw0rd!");
                dialog.clickSubmitForTest();
            });

            awaitCondition(() -> !dialog.isBusyForTest()
                    && dialog.getBannerTextForTest().contains("Token has expired or is invalid"));

            SwingUtilities.invokeAndWait(() -> {
                assertTrue(dialog.isResetPasswordModeForTest());
                assertEquals(Theme.RED, dialog.getBannerForegroundForTest());

                // Test Resend Code in Step 2
                dialog.clickResendForTest();
            });

            awaitCondition(() -> !dialog.isBusyForTest()
                    && dialog.getBannerTextForTest().contains("A new recovery code has been sent"));

            // Now submit with valid OTP and matching strong password
            verifyStatus.set(200);
            verifyResponse.set("{\"access_token\":\"recovery-token-1\",\"refresh_token\":\"recovery-refresh-1\","
                    + "\"expires_in\":3600,\"user\":{\"id\":\"u1\",\"email\":\"alice@example.com\"}}");
            SwingUtilities.invokeAndWait(() -> {
                dialog.setRecoveryCodeForTest("654321");
                dialog.setPasswordForTest("NewPassw0rd!");
                dialog.setConfirmPasswordForTest("NewPassw0rd!");
                dialog.clickSubmitForTest();
            });

            awaitCondition(() -> !dialog.isBusyForTest() && dialog.isSignInModeForTest());

            SwingUtilities.invokeAndWait(() -> {
                assertTrue(dialog.isSignInModeForTest(), "Successful reset must return to Sign In");
                assertEquals("alice@example.com", dialog.getEmailForTest());
                assertTrue(dialog.isEmailEditableForTest(), "Email must be editable again on Sign In");
                assertEquals(Theme.GREEN, dialog.getBannerForegroundForTest());
                assertEquals("Password updated successfully! You can now log in.", dialog.getBannerTextForTest());
            });

            // Verify backend sequence: /auth/v1/verify (type=recovery) followed by PUT /auth/v1/user
            List<CapturedRequest> userUpdates = requests.stream()
                    .filter(r -> r.path().equals("/auth/v1/user"))
                    .toList();
            assertEquals(1, userUpdates.size());
            assertEquals("PUT", userUpdates.get(0).method());
            assertEquals("ui-test-key", userUpdates.get(0).apiKey());
            assertEquals("Bearer recovery-token-1", userUpdates.get(0).authorization());
            JsonObject updatedBody = JsonParser.parseString(userUpdates.get(0).body()).getAsJsonObject();
            assertEquals("NewPassw0rd!", updatedBody.get("password").getAsString());
            assertFalse(auth.isAuthenticated(), "User should remain signed out to sign in manually");
        } finally {
            LoginDialog dialog = dialogRef.get();
            if (dialog != null) {
                SwingUtilities.invokeAndWait(dialog::dispose);
            }
            auth.close();
            server.stop(0);
        }
    }

    @Test
    void forgotPasswordRespectsDarkThemeOfflineGateAndBackToSignIn() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a display to construct JDialog");

        SupabaseAuthService auth = new SupabaseAuthService(new SupabaseConfig("http://127.0.0.1:1", "test-key"));
        AtomicBoolean online = new AtomicBoolean(false);
        try {
            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(true);
                LoginDialog dialog = new LoginDialog(null, auth, () -> {}, null, null, online::get);
                try {
                    dialog.checkConnectivitySyncForTest();
                    assertTrue(dialog.isNetworkOffline());
                    assertTrue(dialog.isForgotPasswordLinkVisibleForTest());

                    dialog.clickForgotPasswordForTest();
                    assertTrue(dialog.isForgotPasswordModeForTest());
                    assertFalse(dialog.isSubmitEnabledForTest(), "Send Code must be disabled offline");
                    assertTrue(dialog.getSubmitButtonTextForTest().toLowerCase().contains("network required"));

                    online.set(true);
                    dialog.checkConnectivitySyncForTest();
                    assertTrue(dialog.isSubmitEnabledForTest());
                    assertEquals("Send Code", dialog.getSubmitButtonTextForTest());

                    dialog.clickBackToSignInForTest();
                    assertTrue(dialog.isSignInModeForTest());
                    assertTrue(dialog.isPasswordSectionVisibleForTest());
                } finally {
                    dialog.dispose();
                    Theme.apply(false);
                }
            });
        } finally {
            auth.close();
        }
    }
}
