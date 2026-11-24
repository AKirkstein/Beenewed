package com.beenewed.supabase;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises SupabaseAuthService against an in-process fake GoTrue server (no real network). */
class SupabaseAuthServiceTest {

    record Call(String method, String path, String query, String apiKey, String contentType, String authorization, String body) {
    }

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger refreshCount = new AtomicInteger();
    private volatile long signInExpiresIn = 3600;
    private volatile int refreshStatus = 200;
    private volatile int recoverStatus = 200;
    private volatile String recoverBody = "{}";
    private volatile int verifyStatus = 200;
    private volatile String verifyBody = null;
    private volatile int userUpdateStatus = 200;
    private volatile String userUpdateBody = "{\"id\":\"user-123\",\"email\":\"kirk@example.com\"}";
    private SupabaseAuthService auth;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/v1/", this::handle);
        server.start();
        auth = new SupabaseAuthService(new SupabaseConfig(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-anon-key"));
    }

    @AfterEach
    void stop() {
        auth.close();
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call(
                method,
                path,
                query,
                ex.getRequestHeaders().getFirst("apikey"),
                ex.getRequestHeaders().getFirst("Content-Type"),
                ex.getRequestHeaders().getFirst("Authorization"),
                body));

        int status = 200;
        String response = "{}";
        if (path.equals("/auth/v1/token") && "grant_type=password".equals(query)) {
            response = sessionJson("access-1", "refresh-1", signInExpiresIn);
        } else if (path.equals("/auth/v1/token") && "grant_type=refresh_token".equals(query)) {
            int n = refreshCount.incrementAndGet();
            status = refreshStatus;
            response = status == 200
                    ? sessionJson("access-" + (n + 1), "refresh-" + (n + 1), 3600)
                    : "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid Refresh Token\"}";
        } else if (path.equals("/auth/v1/verify")) {
            status = verifyStatus;
            response = verifyBody != null ? verifyBody : sessionJson("otp-access", "otp-refresh", 3600);
        } else if (path.equals("/auth/v1/user")) {
            status = userUpdateStatus;
            response = userUpdateBody;
        } else if (path.equals("/auth/v1/recover")) {
            status = recoverStatus;
            response = recoverBody;
        } else if (path.equals("/auth/v1/logout")) {
            status = 204;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    private static String sessionJson(String access, String refresh, long expiresIn) {
        return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh + "\","
                + "\"expires_in\":" + expiresIn + ",\"token_type\":\"bearer\","
                + "\"user\":{\"id\":\"user-123\",\"email\":\"kirk@example.com\"}}";
    }

    private List<Call> callsTo(String path) {
        return calls.stream().filter(c -> c.path().equals(path)).toList();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within timeout");
    }

    @Test
    void weakSignUpPasswordIsRejectedBeforeAnyNetworkCall() {
        SupabaseAuthException error = assertThrows(SupabaseAuthException.class,
                () -> auth.signUp("kirk@example.com", "123456"));
        assertTrue(error.getMessage().startsWith("Password must contain"), error.getMessage());
        assertTrue(calls.isEmpty(), "no request should be sent for a weak password");
    }

    @Test
    void signInStoresSessionWithExpiry() throws Exception {
        Instant before = Instant.now();
        auth.signIn("kirk@example.com", "anything");
        assertTrue(auth.isAuthenticated());
        assertEquals("access-1", auth.getAccessToken());
        assertEquals("user-123", auth.getUserId());
        assertNotNull(auth.getCurrentSession().expiresAt());
        assertFalse(auth.getSignedInAt().isBefore(before));
    }

    @Test
    void refreshSessionRotatesTokensAndPreservesSignInTime() throws Exception {
        auth.signIn("kirk@example.com", "anything");
        Instant signedInAt = auth.getSignedInAt();

        SupabaseSession refreshed = auth.refreshSession();

        assertEquals("access-2", refreshed.accessToken());
        assertEquals("refresh-2", refreshed.refreshToken());
        assertEquals(signedInAt, refreshed.signedInAt());
        Call call = callsTo("/auth/v1/token").get(1);
        assertEquals("grant_type=refresh_token", call.query());
        assertTrue(call.body().contains("refresh-1"));
    }

    @Test
    void getAccessTokenRefreshesAutomaticallyWhenAboutToExpire() throws Exception {
        signInExpiresIn = 30; // inside the 60s refresh margin
        auth.signIn("kirk@example.com", "anything");

        String token = auth.getAccessToken();

        assertEquals("access-2", token);
        assertEquals(1, refreshCount.get());
        assertEquals("access-2", auth.getAccessToken(), "fresh token should be reused without another refresh");
        assertEquals(1, refreshCount.get());
    }

    @Test
    void rejectedRefreshTokenClearsSession() throws Exception {
        auth.signIn("kirk@example.com", "anything");
        refreshStatus = 400;

        SupabaseAuthException error = assertThrows(SupabaseAuthException.class, auth::refreshSession);

        assertTrue(error.getMessage().contains("expired"));
        assertFalse(auth.isAuthenticated());
        assertThrows(SupabaseException.class, auth::getAccessToken);
    }

    @Test
    void signOutClearsLocallyAndRevokesThisDeviceOnly() throws Exception {
        auth.signIn("kirk@example.com", "anything");

        auth.signOutAndWait(Duration.ofSeconds(3));

        assertFalse(auth.isAuthenticated());
        assertNull(auth.getCurrentSession());
        assertThrows(SupabaseException.class, auth::getAccessToken);
        List<Call> logouts = callsTo("/auth/v1/logout");
        assertEquals(1, logouts.size());
        assertEquals("scope=local", logouts.get(0).query());
        assertEquals("Bearer access-1", logouts.get(0).authorization());
    }

    @Test
    void signOutWhenSignedOutIsANoOp() {
        auth.signOutAndWait(Duration.ofSeconds(1));
        assertTrue(calls.isEmpty());
    }

    @Test
    void verifyOtpRevokesTheLeftoverSession() throws Exception {
        auth.verifySignupOtp("kirk@example.com", "123456");

        awaitTrue(() -> !callsTo("/auth/v1/logout").isEmpty());
        assertEquals("Bearer otp-access", callsTo("/auth/v1/logout").get(0).authorization());
        assertFalse(auth.isAuthenticated(), "verify must not sign the user in");
    }

    @Test
    void isServerReachableDetectsOnlineAndOfflineStates() {
        assertTrue(auth.isServerReachable(Duration.ofSeconds(2)),
                "Mock GoTrue server should be reported reachable while running");

        server.stop(0);
        assertFalse(auth.isServerReachable(Duration.ofSeconds(1)),
                "Stopped server should be reported unreachable/offline");
    }

    @Test
    void isValidEmailAcceptsValidAndRejectsMalformedAddresses() {
        assertTrue(SupabaseAuthService.isValidEmail("user@example.com"));
        assertTrue(SupabaseAuthService.isValidEmail("  first.last+tag@sub.example.co.uk  "));
        assertTrue(SupabaseAuthService.isValidEmail("kirk_123-dev@beenewed.app"));

        assertFalse(SupabaseAuthService.isValidEmail(null));
        assertFalse(SupabaseAuthService.isValidEmail(""));
        assertFalse(SupabaseAuthService.isValidEmail("   "));
        assertFalse(SupabaseAuthService.isValidEmail("plainaddress"));
        assertFalse(SupabaseAuthService.isValidEmail("@example.com"));
        assertFalse(SupabaseAuthService.isValidEmail("user@"));
        assertFalse(SupabaseAuthService.isValidEmail("user@localhost"));
        assertFalse(SupabaseAuthService.isValidEmail("user@.com"));
        assertFalse(SupabaseAuthService.isValidEmail("user@example."));
        assertFalse(SupabaseAuthService.isValidEmail("user@exam..ple.com"));
        assertFalse(SupabaseAuthService.isValidEmail(".user@example.com"));
        assertFalse(SupabaseAuthService.isValidEmail("user.@example.com"));
        assertFalse(SupabaseAuthService.isValidEmail("user..name@example.com"));
        assertFalse(SupabaseAuthService.isValidEmail("user name@example.com"));
        assertFalse(SupabaseAuthService.isValidEmail("a@b@c.com"));
    }

    @Test
    void buildPasswordRecoveryPayloadConstructsTrimmedEmailJsonAndRejectsInvalid() throws Exception {
        JsonObject payload = SupabaseAuthService.buildPasswordRecoveryPayload("  kirk@example.com  ");
        assertEquals(1, payload.size());
        assertEquals("kirk@example.com", payload.get("email").getAsString());

        SupabaseAuthException blankErr = assertThrows(SupabaseAuthException.class,
                () -> SupabaseAuthService.buildPasswordRecoveryPayload("   "));
        assertTrue(blankErr.getMessage().toLowerCase().contains("email"));

        SupabaseAuthException invalidErr = assertThrows(SupabaseAuthException.class,
                () -> SupabaseAuthService.buildPasswordRecoveryPayload("not-an-email"));
        assertTrue(invalidErr.getMessage().toLowerCase().contains("valid email"));
    }

    @Test
    void sendPasswordResetEmailPostsToRecoverEndpointWithApiKeyAndJsonPayload() throws Exception {
        auth.sendPasswordResetEmail("  kirk@example.com  ");

        List<Call> recoverCalls = callsTo("/auth/v1/recover");
        assertEquals(1, recoverCalls.size());
        Call call = recoverCalls.get(0);
        assertEquals("POST", call.method());
        assertNull(call.query());
        assertEquals("test-anon-key", call.apiKey());
        assertEquals("application/json", call.contentType());
        assertNull(call.authorization(), "Recovery request should not include a Bearer Authorization header");

        JsonObject sentBody = JsonParser.parseString(call.body()).getAsJsonObject();
        assertEquals(1, sentBody.size());
        assertEquals("kirk@example.com", sentBody.get("email").getAsString());
        assertFalse(auth.isAuthenticated(), "Password recovery must not create a local session");
    }

    @Test
    void sendPasswordResetEmailRejectsInvalidEmailBeforeNetworkCall() {
        assertThrows(SupabaseAuthException.class, () -> auth.sendPasswordResetEmail(""));
        assertThrows(SupabaseAuthException.class, () -> auth.sendPasswordResetEmail("invalid@domain"));
        assertTrue(calls.isEmpty(), "Invalid email must be rejected locally without calling Supabase");
    }

    @Test
    void sendPasswordResetEmailSurfacesReadableRateLimitError() {
        recoverStatus = 429;
        recoverBody = "{\"code\":429,\"msg\":\"Email rate limit exceeded\"}";

        SupabaseAuthException error = assertThrows(SupabaseAuthException.class,
                () -> auth.sendPasswordResetEmail("kirk@example.com"));
        assertEquals("Email rate limit exceeded", error.getMessage());
    }

    @Test
    void sendPasswordResetEmailHandlesAuthAndServerErrorsWithoutExposingTokensOrStackTraces() {
        recoverStatus = 401;
        recoverBody = "{\"message\":\"Invalid apikey eyJhbGciOiJIUzI1NiJ9.secret\"}";
        SupabaseAuthException authError = assertThrows(SupabaseAuthException.class,
                () -> auth.sendPasswordResetEmail("kirk@example.com"));
        assertFalse(authError.getMessage().contains("eyJ"), "Must not expose tokens");
        assertFalse(authError.getMessage().toLowerCase().contains("apikey"), "Must not expose apikey details");

        recoverStatus = 500;
        recoverBody = "{\"message\":\"java.lang.NullPointerException: boom\\n\\tat com.internal.Auth.run(Auth.java:42)\"}";
        SupabaseAuthException serverError = assertThrows(SupabaseAuthException.class,
                () -> auth.sendPasswordResetEmail("kirk@example.com"));
        assertFalse(serverError.getMessage().contains("NullPointerException"), "Must not expose stack traces");
        assertTrue(serverError.getMessage().toLowerCase().contains("temporarily unavailable"));

        recoverStatus = 400;
        recoverBody = "<html><body>502 Bad Gateway</body></html>";
        SupabaseAuthException htmlError = assertThrows(SupabaseAuthException.class,
                () -> auth.sendPasswordResetEmail("kirk@example.com"));
        assertFalse(htmlError.getMessage().contains("<html"), "Must not expose raw HTML");
    }

    @Test
    void sendPasswordResetEmailHandlesNetworkFailureCleanly() {
        server.stop(0);

        SupabaseAuthException error = assertThrows(SupabaseAuthException.class,
                () -> auth.sendPasswordResetEmail("kirk@example.com"));
        assertTrue(SupabaseRestClient.isConnectivityFailure(error),
                "Network failure should be recognized as connectivity outage");
        assertTrue(error.getMessage().toLowerCase().contains("could not reach supabase"));
        assertFalse(error.getMessage().contains("null"));
    }

    @Test
    void buildRecoveryVerifyAndPasswordUpdatePayloadsValidateInputs() throws Exception {
        JsonObject verifyPayload = SupabaseAuthService.buildRecoveryVerifyPayload("  kirk@example.com  ", " 123456 ");
        assertEquals("recovery", verifyPayload.get("type").getAsString());
        assertEquals("kirk@example.com", verifyPayload.get("email").getAsString());
        assertEquals("123456", verifyPayload.get("token").getAsString());

        assertThrows(SupabaseAuthException.class,
                () -> SupabaseAuthService.buildRecoveryVerifyPayload("kirk@example.com", "   "));

        JsonObject updatePayload = SupabaseAuthService.buildPasswordUpdatePayload("NewPassw0rd!");
        assertEquals(1, updatePayload.size());
        assertEquals("NewPassw0rd!", updatePayload.get("password").getAsString());

        assertThrows(SupabaseAuthException.class,
                () -> SupabaseAuthService.buildPasswordUpdatePayload("weak"));
    }

    @Test
    void resetPasswordWithRecoveryCodeVerifiesOtpUpdatesUserAndRevokesTempSession() throws Exception {
        auth.resetPasswordWithRecoveryCode("  kirk@example.com  ", " 654321 ", "NewPassw0rd!");

        List<Call> verifyCalls = callsTo("/auth/v1/verify");
        assertEquals(1, verifyCalls.size());
        Call verifyCall = verifyCalls.get(0);
        assertEquals("POST", verifyCall.method());
        assertEquals("test-anon-key", verifyCall.apiKey());
        assertNull(verifyCall.authorization());
        JsonObject verifySent = JsonParser.parseString(verifyCall.body()).getAsJsonObject();
        assertEquals("recovery", verifySent.get("type").getAsString());
        assertEquals("kirk@example.com", verifySent.get("email").getAsString());
        assertEquals("654321", verifySent.get("token").getAsString());

        List<Call> userCalls = callsTo("/auth/v1/user");
        assertEquals(1, userCalls.size());
        Call userCall = userCalls.get(0);
        assertEquals("PUT", userCall.method());
        assertEquals("test-anon-key", userCall.apiKey());
        assertEquals("Bearer otp-access", userCall.authorization());
        JsonObject userSent = JsonParser.parseString(userCall.body()).getAsJsonObject();
        assertEquals("NewPassw0rd!", userSent.get("password").getAsString());

        awaitTrue(() -> !callsTo("/auth/v1/logout").isEmpty());
        assertEquals("Bearer otp-access", callsTo("/auth/v1/logout").get(0).authorization());
        assertFalse(auth.isAuthenticated(), "Password reset must not leave user signed in");
    }

    @Test
    void resetPasswordWithRecoveryCodeRejectsWeakPasswordBeforeCallingVerify() {
        assertThrows(SupabaseAuthException.class,
                () -> auth.resetPasswordWithRecoveryCode("kirk@example.com", "654321", "short"));
        assertTrue(calls.isEmpty(), "Weak password must not consume the recovery OTP");
    }

    @Test
    void resetPasswordWithRecoveryCodeHandlesInvalidOtpAndUpdateErrors() throws Exception {
        verifyStatus = 403;
        verifyBody = "{\"msg\":\"Token has expired or is invalid\"}";
        SupabaseAuthException otpErr = assertThrows(SupabaseAuthException.class,
                () -> auth.resetPasswordWithRecoveryCode("kirk@example.com", "000000", "NewPassw0rd!"));
        assertEquals("Token has expired or is invalid", otpErr.getMessage());
        assertTrue(callsTo("/auth/v1/user").isEmpty(), "User update must not be called if verify fails");

        // Reset verify to succeed, fail PUT /auth/v1/user and ensure temp token is still revoked
        verifyStatus = 200;
        verifyBody = null;
        userUpdateStatus = 422;
        userUpdateBody = "{\"msg\":\"New password should be different from the old password.\"}";
        SupabaseAuthException updateErr = assertThrows(SupabaseAuthException.class,
                () -> auth.resetPasswordWithRecoveryCode("kirk@example.com", "654321", "NewPassw0rd!"));
        assertTrue(updateErr.getMessage().contains("different from the old password"));
        awaitTrue(() -> !callsTo("/auth/v1/logout").isEmpty());
        assertFalse(auth.isAuthenticated());
    }
}
