package com.beenewed.supabase;

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

    record Call(String path, String query, String authorization, String body) {
    }

    private HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger refreshCount = new AtomicInteger();
    private volatile long signInExpiresIn = 3600;
    private volatile int refreshStatus = 200;
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
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call(path, query, ex.getRequestHeaders().getFirst("Authorization"), body));

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
            response = sessionJson("otp-access", "otp-refresh", 3600);
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
}
