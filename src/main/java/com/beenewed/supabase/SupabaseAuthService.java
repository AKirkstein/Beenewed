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
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

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
    private static final int MAX_ERROR_MESSAGE_LENGTH = 240;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

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

    /**
     * Requests a password recovery email via Supabase Auth ({@code POST /auth/v1/recover}).
     * Does not create or alter any local session.
     */
    public void sendPasswordResetEmail(String email) throws SupabaseAuthException {
        JsonObject payload = buildPasswordRecoveryPayload(email);

        HttpResponse<String> response = send(
                jsonPost("/auth/v1/recover", payload),
                "Unable to send password reset email");
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new SupabaseAuthException(friendlyRecoveryError(status, response.body()));
        }
    }

    /**
     * Builds the JSON payload sent to Supabase Auth's {@code /auth/v1/recover} endpoint.
     */
    public static JsonObject buildPasswordRecoveryPayload(String email) throws SupabaseAuthException {
        String cleanEmail = requireEmail(email);
        JsonObject payload = new JsonObject();
        payload.addProperty("email", cleanEmail);
        return payload;
    }

    /**
     * Builds the JSON payload sent to {@code POST /auth/v1/verify} for recovery OTP verification.
     */
    public static JsonObject buildRecoveryVerifyPayload(String email, String token) throws SupabaseAuthException {
        String cleanEmail = requireEmail(email);
        String cleanToken = requireRecoveryToken(token);
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "recovery");
        payload.addProperty("email", cleanEmail);
        payload.addProperty("token", cleanToken);
        return payload;
    }

    /**
     * Builds the JSON payload sent to {@code PUT /auth/v1/user} to set a new password.
     */
    public static JsonObject buildPasswordUpdatePayload(String newPassword) throws SupabaseAuthException {
        requirePassword(newPassword);
        requireStrongPassword(newPassword);
        JsonObject payload = new JsonObject();
        payload.addProperty("password", newPassword);
        return payload;
    }

    /**
     * Completes an in-app password reset using a recovery OTP code:
     * <ol>
     *   <li>Verifies the recovery code via {@code POST /auth/v1/verify} ({@code type=recovery})
     *       to obtain a temporary authenticated recovery access token.</li>
     *   <li>Updates the user's password via {@code PUT /auth/v1/user} using that token.</li>
     *   <li>Revokes the temporary recovery session best-effort without modifying the local
     *       app session so the user signs in cleanly with their new password.</li>
     * </ol>
     */
    public void resetPasswordWithRecoveryCode(String email, String token, String newPassword)
            throws SupabaseAuthException {
        // Validate all inputs up front so invalid passwords never consume a single-use OTP.
        JsonObject verifyPayload = buildRecoveryVerifyPayload(email, token);
        JsonObject updatePayload = buildPasswordUpdatePayload(newPassword);

        HttpResponse<String> verifyResponse = send(
                jsonPost("/auth/v1/verify", verifyPayload),
                "Unable to verify recovery code");
        int verifyStatus = verifyResponse.statusCode();
        if (verifyStatus < 200 || verifyStatus >= 300) {
            throw new SupabaseAuthException(friendlyRecoveryVerifyError(verifyStatus, verifyResponse.body()));
        }

        JsonObject verifyBody = parseObject(verifyResponse.body());
        String recoveryAccessToken = stringField(verifyBody, "access_token");
        if (recoveryAccessToken == null || recoveryAccessToken.isBlank()) {
            throw new SupabaseAuthException(
                    "Unable to verify recovery code. Please request a new code and try again.");
        }

        try {
            HttpResponse<String> updateResponse = send(
                    authenticatedJsonPut("/auth/v1/user", recoveryAccessToken, updatePayload),
                    "Unable to update password");
            int updateStatus = updateResponse.statusCode();
            if (updateStatus < 200 || updateStatus >= 300) {
                throw new SupabaseAuthException(friendlyPasswordUpdateError(updateStatus, updateResponse.body()));
            }
        } finally {
            revokeAsync(recoveryAccessToken);
        }
    }

    /**
     * Validates basic email format: non-empty, valid local part and domain with a TLD label.
     */
    public static boolean isValidEmail(String email) {
        if (email == null) {
            return false;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty() || trimmed.length() > 254) {
            return false;
        }
        int at = trimmed.indexOf('@');
        if (at <= 0 || at > 64) {
            return false;
        }
        String local = trimmed.substring(0, at);
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..")) {
            return false;
        }
        return EMAIL_PATTERN.matcher(trimmed).matches();
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

    private HttpRequest authenticatedJsonPut(String path, String accessToken, JsonObject payload) {
        return HttpRequest.newBuilder()
                .uri(URI.create(config.url() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("apikey", config.anonKey())
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
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
                    fallbackMessage + ": could not reach Supabase. Please check your internet connection and try again.",
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

    private static String friendlyRecoveryError(int status, String body) {
        if (status == 401 || status == 403) {
            return "Password reset is currently unavailable. Please check your configuration or try again later.";
        }
        if (status == 429) {
            return sanitizeErrorMessage(
                    extractError(body, null),
                    "Too many password reset requests. Please wait a few minutes and try again.");
        }
        if (status >= 500) {
            return "The authentication service is temporarily unavailable. Please try again later.";
        }
        if (status == 400 || status == 422) {
            return sanitizeErrorMessage(
                    extractError(body, null),
                    "Please enter a valid email address and try again.");
        }
        return sanitizeErrorMessage(
                extractError(body, null),
                "Unable to send password reset email. Please try again.");
    }

    /**
     * Filters out raw HTML, stack traces, excessively long strings, or accidental token/key
     * substrings so user-facing error messages stay readable and never leak secrets.
     */
    public static String sanitizeErrorMessage(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        String trimmed = message.trim();
        if (trimmed.length() > MAX_ERROR_MESSAGE_LENGTH) {
            return fallback;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("<html")
                || lower.contains("<!doctype")
                || lower.contains("bearer ")
                || lower.contains("apikey")
                || lower.contains("api_key")
                || lower.contains("access_token")
                || lower.contains("refresh_token")
                || trimmed.contains("eyJ")
                || trimmed.contains("\n\tat ")
                || trimmed.contains("Exception:")) {
            return fallback;
        }
        return trimmed;
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
        if (email == null || email.isBlank()) {
            throw new SupabaseAuthException("Please enter your email address.");
        }
        String trimmed = email.trim();
        if (!isValidEmail(trimmed)) {
            throw new SupabaseAuthException("Please enter a valid email address.");
        }
        return trimmed;
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

    private static String friendlyRecoveryVerifyError(int status, String body) {
        if (status == 429) {
            return sanitizeErrorMessage(
                    extractError(body, null),
                    "Too many verification attempts. Please wait a few minutes and try again.");
        }
        if (status >= 500) {
            return "The authentication service is temporarily unavailable. Please try again later.";
        }
        return sanitizeErrorMessage(
                extractError(body, null),
                "Invalid or expired recovery code. Please check the code or request a new one.");
    }

    private static String friendlyPasswordUpdateError(int status, String body) {
        if (status == 401 || status == 403) {
            return "Your recovery session has expired. Please request a new recovery code and try again.";
        }
        if (status == 429) {
            return sanitizeErrorMessage(
                    extractError(body, null),
                    "Too many requests. Please wait a few minutes and try again.");
        }
        if (status >= 500) {
            return "The authentication service is temporarily unavailable. Please try again later.";
        }
        return sanitizeErrorMessage(
                extractError(body, null),
                "Unable to update password. Please check your password and try again.");
    }

    private static String requireRecoveryToken(String token) throws SupabaseAuthException {
        if (token == null || token.isBlank()) {
            throw new SupabaseAuthException("Please enter the recovery code from your email.");
        }
        return token.trim();
    }

    private static String requireOtpToken(String token) throws SupabaseAuthException {
        if (token == null || token.isBlank()) {
            throw new SupabaseAuthException("Please enter the verification code from your email.");
        }
        return token.trim();
    }
}
