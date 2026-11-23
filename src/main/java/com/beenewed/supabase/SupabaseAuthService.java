package com.beenewed.supabase;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles Supabase GoTrue authentication ({@code /auth/v1/*}) and keeps the active
 * session in memory only.
 * <p>
 * Session lifecycle:
 * <ul>
 *   <li>Tokens are never written to disk; {@link SupabaseSession#toString()} redacts them.</li>
 *   <li>The access token is refreshed automatically ~60s before it expires (proactively on a
 *       background daemon thread, and lazily in {@link #getAccessToken()} as a fallback).</li>
 *   <li>{@link #signOut()} clears the local session <em>first</em> (so no further request can use
 *       it) and then revokes it server-side with {@code scope=local} (this device only).</li>
 * </ul>
 */
public final class SupabaseAuthService implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration LOGOUT_TIMEOUT = Duration.ofSeconds(10);
    /** Refresh the access token when it is this close to expiring. */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);
    private static final Duration MIN_REFRESH_DELAY = Duration.ofSeconds(5);
    private static final Duration REFRESH_RETRY_DELAY = Duration.ofSeconds(30);

    private final SupabaseConfig config;
    private final HttpClient http;
    private final Gson gson = new Gson();
    private final AtomicReference<SupabaseSession> currentSession = new AtomicReference<>();
    private final Object refreshLock = new Object();
    private final ScheduledExecutorService refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "supabase-session-refresh");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> scheduledRefresh;

    public SupabaseAuthService(SupabaseConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build());
    }

    public SupabaseAuthService(SupabaseConfig config, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.http = Objects.requireNonNull(http, "http must not be null");
    }

    public record SignUpResult(
            SupabaseSession session,
            boolean confirmationRequired,
            String email) {
    }

    // ---------------------------------------------------------------------
    // Sign up / verify / sign in
    // ---------------------------------------------------------------------

    public SignUpResult signUp(String email, String password) throws SupabaseAuthException {
        String cleanEmail = requireEmail(email);
        requirePassword(password);
        requireStrongPassword(password);

        JsonObject payload = new JsonObject();
        payload.addProperty("email", cleanEmail);
        payload.addProperty("password", password);

        HttpResponse<String> response = send(jsonPost("/auth/v1/signup", payload), "Unable to create account");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SupabaseAuthException(extractError(response.body(), "Unable to create account (HTTP " + response.statusCode() + ")"));
        }

        JsonObject body = parseObject(response.body());
        String accessToken = stringField(body, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            // Email confirmation is enabled on the Supabase project; user was created but has no session yet.
            clearLocalSession();
            String createdEmail = extractEmail(body, cleanEmail);
            return new SignUpResult(null, true, createdEmail);
        }

        SupabaseSession session = parseSession(body, cleanEmail, null, Instant.now());
        installSession(session);
        return new SignUpResult(session, false, session.email());
    }

    /**
     * Verifies a signup email OTP code via {@code /auth/v1/verify}. Any session returned by
     * Supabase is revoked immediately (best effort) so no stray valid refresh token is left
     * behind; the user completes a manual sign-in afterward.
     */
    public void verifySignupOtp(String email, String token) throws SupabaseAuthException {
        String cleanEmail = requireEmail(email);
        String cleanToken = requireOtpToken(token);

        JsonObject payload = new JsonObject();
        payload.addProperty("email", cleanEmail);
        payload.addProperty("token", cleanToken);
        payload.addProperty("type", "signup");

        HttpResponse<String> response = send(jsonPost("/auth/v1/verify", payload), "Unable to verify code");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SupabaseAuthException(extractError(
                    response.body(),
                    "Invalid or expired verification code. Please check the code or request a new one."));
        }

        try {
            JsonObject body = parseObject(response.body());
            String leftoverToken = stringField(body, "access_token");
            if (leftoverToken != null && !leftoverToken.isBlank()) {
                revokeAsync(leftoverToken);
            }
        } catch (SupabaseAuthException | RuntimeException ignored) {
            // Verification itself succeeded; nothing to revoke.
        }
    }

    /**
     * Requests a fresh signup email OTP code via {@code /auth/v1/resend}.
     */
    public void resendSignupOtp(String email) throws SupabaseAuthException {
        String cleanEmail = requireEmail(email);

        JsonObject payload = new JsonObject();
        payload.addProperty("email", cleanEmail);
        payload.addProperty("type", "signup");

        HttpResponse<String> response = send(jsonPost("/auth/v1/resend", payload), "Unable to resend verification code");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SupabaseAuthException(extractError(
                    response.body(),
                    "Unable to resend verification code (HTTP " + response.statusCode() + ")"));
        }
    }

    public SupabaseSession signIn(String email, String password) throws SupabaseAuthException {
        String cleanEmail = requireEmail(email);
        requirePassword(password);
        // Note: strength rules are NOT enforced on sign-in so older accounts can still log in.

        JsonObject payload = new JsonObject();
        payload.addProperty("email", cleanEmail);
        payload.addProperty("password", password);

        HttpResponse<String> response = send(jsonPost("/auth/v1/token?grant_type=password", payload), "Unable to sign in");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SupabaseAuthException(extractError(response.body(), "Invalid email or password."));
        }

        JsonObject body = parseObject(response.body());
        SupabaseSession session = parseSession(body, cleanEmail, null, Instant.now());
        installSession(session);
        return session;
    }

    // ---------------------------------------------------------------------
    // Refresh
    // ---------------------------------------------------------------------

    /**
     * Exchanges the current refresh token for a new access token. Safe to call concurrently:
     * if another thread already refreshed, the newer session is returned without a request.
     *
     * @throws SupabaseAuthException if not signed in, the network fails, or the refresh token
     *                               was rejected (in which case the local session is cleared)
     */
    public SupabaseSession refreshSession() throws SupabaseAuthException {
        SupabaseSession snapshot = currentSession.get();
        if (snapshot == null) {
            throw new SupabaseAuthException("You are not signed in. Please sign in again.");
        }
        return refreshFrom(snapshot, true);
    }

    private SupabaseSession refreshFrom(SupabaseSession expected, boolean force) throws SupabaseAuthException {
        synchronized (refreshLock) {
            SupabaseSession current = currentSession.get();
            if (current == null) {
                throw new SupabaseAuthException("You are not signed in. Please sign in again.");
            }
            if (current != expected && !current.expiresWithin(REFRESH_MARGIN)) {
                return current; // another thread refreshed while we waited for the lock
            }
            if (!force && current == expected && !current.expiresWithin(REFRESH_MARGIN)) {
                return current;
            }
            if (!current.hasRefreshToken()) {
                throw new SupabaseAuthException("Your session cannot be renewed. Please sign in again.");
            }

            JsonObject payload = new JsonObject();
            payload.addProperty("refresh_token", current.refreshToken());
            HttpResponse<String> response = send(
                    jsonPost("/auth/v1/token?grant_type=refresh_token", payload),
                    "Unable to renew your session");

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (status == 400 || status == 401 || status == 403) {
                    // Refresh token revoked/expired: the session is dead; drop it locally.
                    currentSession.compareAndSet(current, null);
                    cancelScheduledRefresh();
                    throw new SupabaseAuthException("Your session has expired. Please sign in again.");
                }
                throw new SupabaseAuthException(extractError(response.body(),
                        "Unable to renew your session (HTTP " + status + ")"));
            }

            SupabaseSession refreshed = parseSession(parseObject(response.body()),
                    current.email(), current.userId(), current.signedInAt());
            if (!currentSession.compareAndSet(current, refreshed)) {
                // Signed out while the refresh was in flight: don't resurrect, revoke the new token.
                revokeAsync(refreshed.accessToken());
                throw new SupabaseAuthException("You are not signed in. Please sign in again.");
            }
            scheduleRefresh(refreshed);
            return refreshed;
        }
    }

    private void scheduleRefresh(SupabaseSession session) {
        cancelScheduledRefresh();
        if (session == null || session.expiresAt() == null || !session.hasRefreshToken()) {
            return;
        }
        Duration delay = Duration.between(Instant.now(), session.expiresAt().minus(REFRESH_MARGIN));
        if (delay.compareTo(MIN_REFRESH_DELAY) < 0) {
            delay = MIN_REFRESH_DELAY;
        }
        scheduleRefreshAttempt(session, delay);
    }

    private void scheduleRefreshAttempt(SupabaseSession session, Duration delay) {
        try {
            scheduledRefresh = refreshScheduler.schedule(() -> runScheduledRefresh(session),
                    delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // Scheduler shut down; lazy refresh in getAccessToken() still applies.
        }
    }

    private void runScheduledRefresh(SupabaseSession session) {
        if (currentSession.get() != session) {
            return; // signed out or already refreshed
        }
        try {
            refreshFrom(session, true);
        } catch (SupabaseAuthException error) {
            SupabaseSession still = currentSession.get();
            if (still == session && !session.isExpired()) {
                scheduleRefreshAttempt(session, REFRESH_RETRY_DELAY); // transient (e.g. offline): retry
            }
        } catch (RuntimeException ignored) {
            // Never let the scheduler thread die.
        }
    }

    private void cancelScheduledRefresh() {
        ScheduledFuture<?> pending = scheduledRefresh;
        scheduledRefresh = null;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    // ---------------------------------------------------------------------
    // Sign out
    // ---------------------------------------------------------------------

    /**
     * Clears the in-memory session immediately and revokes it server-side asynchronously
     * ({@code scope=local}: only this device's session is signed out).
     *
     * @return a future that completes when the revoke request finishes (never fails)
     */
    public CompletableFuture<Void> signOut() {
        SupabaseSession session = clearLocalSession();
        if (session == null) {
            return CompletableFuture.completedFuture(null);
        }
        return revokeAsync(session.accessToken());
    }

    /**
     * Same as {@link #signOut()} but blocks up to {@code timeout} for the server-side revoke.
     * Use before the JVM exits, otherwise the request would be killed before it is sent.
     */
    public void signOutAndWait(Duration timeout) {
        CompletableFuture<Void> revoke = signOut();
        try {
            revoke.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Timeout / network failure: local session is already cleared.
        }
    }

    private SupabaseSession clearLocalSession() {
        cancelScheduledRefresh();
        return currentSession.getAndSet(null);
    }

    private CompletableFuture<Void> revokeAsync(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.url() + "/auth/v1/logout?scope=local"))
                .timeout(LOGOUT_TIMEOUT)
                .header("apikey", config.anonKey())
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .handle((response, error) -> null);
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Cancels any scheduled token refresh and terminates the background scheduler cleanly. */
    @Override
    public void close() {
        cancelScheduledRefresh();
        refreshScheduler.shutdownNow();
    }

    public boolean isClosed() {
        return refreshScheduler.isShutdown();
    }

    /**
     * Performs a lightweight connectivity probe against the configured Supabase Auth endpoint.
     * Any HTTP response proves the server is reachable; network/IO failures return {@code false}.
     */
    public boolean isServerReachable() {
        return isServerReachable(Duration.ofSeconds(3));
    }

    public boolean isServerReachable(Duration timeout) {
        Duration effectiveTimeout = (timeout == null || timeout.isNegative() || timeout.isZero())
                ? Duration.ofSeconds(3)
                : timeout;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.url() + "/auth/v1/health"))
                .timeout(effectiveTimeout)
                .header("apikey", config.anonKey())
                .GET()
                .build();
        try {
            http.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Session accessors
    // ---------------------------------------------------------------------

    public SupabaseSession getCurrentSession() {
        return currentSession.get();
    }

    public boolean isAuthenticated() {
        SupabaseSession session = currentSession.get();
        return session != null && session.accessToken() != null && !session.accessToken().isBlank()
                && (!session.isExpired() || session.hasRefreshToken());
    }

    /**
     * Returns a valid access token, transparently refreshing it if it is about to expire.
     *
     * @throws SupabaseException if not signed in or the session can no longer be renewed
     */
    public String getAccessToken() {
        SupabaseSession session = currentSession.get();
        if (session == null || session.accessToken() == null || session.accessToken().isBlank()) {
            throw new SupabaseException("You are not signed in. Please sign in again.");
        }
        if (session.expiresWithin(REFRESH_MARGIN) && session.hasRefreshToken()) {
            try {
                session = refreshFrom(session, false);
            } catch (SupabaseAuthException error) {
                SupabaseSession latest = currentSession.get();
                if (latest == null || latest.isExpired()) {
                    throw new SupabaseException(error.getMessage(), error);
                }
                session = latest; // refresh failed transiently but the token is still valid
            }
        }
        if (session.isExpired()) {
            throw new SupabaseException("Your session has expired. Please sign in again.");
        }
        return session.accessToken();
    }

    public String getUserId() {
        SupabaseSession session = currentSession.get();
        if (session == null || session.userId() == null || session.userId().isBlank()) {
            throw new SupabaseException("Active user session is missing a user id. Please sign in again.");
        }
        return session.userId();
    }

    public String getEmail() {
        SupabaseSession session = currentSession.get();
        return session == null ? null : session.email();
    }

    /** When the current user signed in, or {@code null} if signed out. */
    public Instant getSignedInAt() {
        SupabaseSession session = currentSession.get();
        return session == null ? null : session.signedInAt();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void installSession(SupabaseSession session) {
        currentSession.set(session);
        scheduleRefresh(session);
    }

    private HttpRequest jsonPost(String path, JsonObject payload) {
        return HttpRequest.newBuilder()
                .uri(URI.create(config.url() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("apikey", config.anonKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request, String fallbackMessage) throws SupabaseAuthException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SupabaseAuthException(fallbackMessage + ": request was interrupted.", error);
        } catch (IOException error) {
            throw new SupabaseAuthException(
                    fallbackMessage + ": could not reach Supabase (" + error.getMessage() + ").",
                    error);
        }
    }

    private static SupabaseSession parseSession(JsonObject body, String fallbackEmail, String fallbackUserId,
                                                Instant signedInAt) throws SupabaseAuthException {
        String accessToken = stringField(body, "access_token");
        String refreshToken = stringField(body, "refresh_token");
        JsonObject user = body.has("user") && body.get("user").isJsonObject()
                ? body.getAsJsonObject("user")
                : null;
        String userId = user == null ? null : stringField(user, "id");
        if (userId == null || userId.isBlank()) {
            userId = fallbackUserId;
        }
        String email = extractEmail(body, fallbackEmail);
        if (accessToken == null || accessToken.isBlank() || userId == null || userId.isBlank()) {
            throw new SupabaseAuthException("Supabase returned an incomplete authentication session.");
        }
        return new SupabaseSession(accessToken, refreshToken, userId, email,
                signedInAt == null ? Instant.now() : signedInAt, parseExpiry(body));
    }

    /** Reads {@code expires_at} (epoch seconds) or falls back to {@code expires_in} (seconds). */
    static Instant parseExpiry(JsonObject body) {
        try {
            if (body.has("expires_at") && !body.get("expires_at").isJsonNull()) {
                return Instant.ofEpochSecond(body.get("expires_at").getAsLong());
            }
            if (body.has("expires_in") && !body.get("expires_in").isJsonNull()) {
                return Instant.now().plusSeconds(body.get("expires_in").getAsLong());
            }
        } catch (RuntimeException ignored) {
            // Unknown format: treat expiry as unknown.
        }
        return null;
    }

    private static String extractEmail(JsonObject body, String fallbackEmail) {
        if (body.has("user") && body.get("user").isJsonObject()) {
            String fromUser = stringField(body.getAsJsonObject("user"), "email");
            if (fromUser != null && !fromUser.isBlank()) {
                return fromUser;
            }
        }
        String topLevel = stringField(body, "email");
        return topLevel != null && !topLevel.isBlank() ? topLevel : fallbackEmail;
    }

    private static JsonObject parseObject(String json) throws SupabaseAuthException {
        if (json == null || json.isBlank()) {
            throw new SupabaseAuthException("Supabase returned an empty authentication response.");
        }
        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (RuntimeException error) {
            throw new SupabaseAuthException("Unable to parse Supabase authentication response.", error);
        }
        if (!element.isJsonObject()) {
            throw new SupabaseAuthException("Unexpected authentication response from Supabase.");
        }
        return element.getAsJsonObject();
    }

    private static String extractError(String body, String fallback) {
        if (body == null || body.isBlank()) {
            return fallback;
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                for (String key : new String[]{"error_description", "msg", "message", "error"}) {
                    String value = stringField(obj, key);
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to fallback
        }
        return fallback;
    }

    private static String stringField(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private static String requireEmail(String email) throws SupabaseAuthException {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new SupabaseAuthException("Please enter a valid email address.");
        }
        return email.trim();
    }

    private static void requirePassword(String password) throws SupabaseAuthException {
        if (password == null || password.isEmpty()) {
            throw new SupabaseAuthException("Password is required.");
        }
    }

    private static void requireStrongPassword(String password) throws SupabaseAuthException {
        EnumSet<PasswordPolicy.Rule> unmet = PasswordPolicy.unmet(password.toCharArray());
        if (!unmet.isEmpty()) {
            throw new SupabaseAuthException(PasswordPolicy.describe(unmet));
        }
    }

    private static String requireOtpToken(String token) throws SupabaseAuthException {
        if (token == null || token.isBlank()) {
            throw new SupabaseAuthException("Please enter the verification code from your email.");
        }
        return token.trim();
    }
}
