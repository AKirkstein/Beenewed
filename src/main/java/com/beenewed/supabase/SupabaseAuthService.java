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
import java.util.Objects;

/**
 * Handles Supabase GoTrue authentication ({@code /auth/v1/*}) and keeps the active
 * access token and user id in memory.
 */
public final class SupabaseAuthService {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final SupabaseConfig config;
    private final HttpClient http;
    private final Gson gson = new Gson();
    private volatile SupabaseSession currentSession;

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

    public SignUpResult signUp(String email, String password) throws SupabaseAuthException {
        String cleanEmail = requireEmail(email);
        requirePassword(password);

        JsonObject payload = new JsonObject();
        payload.addProperty("email", cleanEmail);
        payload.addProperty("password", password);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.url() + "/auth/v1/signup"))
                .timeout(REQUEST_TIMEOUT)
                .header("apikey", config.anonKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request, "Unable to create account");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SupabaseAuthException(extractError(response.body(), "Unable to create account (HTTP " + response.statusCode() + ")"));
        }

        JsonObject body = parseObject(response.body());
        String accessToken = stringField(body, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            // Email confirmation is enabled on the Supabase project; user was created but has no session yet.
            this.currentSession = null;
            String createdEmail = extractEmail(body, cleanEmail);
            return new SignUpResult(null, true, createdEmail);
        }

        SupabaseSession session = parseSession(body, cleanEmail);
        this.currentSession = session;
        return new SignUpResult(session, false, session.email());
    }

    /**
     * Verifies a signup email OTP code via {@code /auth/v1/verify}. Does not store any returned
     * session in memory so the user completes a manual sign-in afterward.
     */
    public void verifySignupOtp(String email, String token) throws SupabaseAuthException {
        String cleanEmail = requireEmail(email);
        String cleanToken = requireOtpToken(token);

        JsonObject payload = new JsonObject();
        payload.addProperty("email", cleanEmail);
        payload.addProperty("token", cleanToken);
        payload.addProperty("type", "signup");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.url() + "/auth/v1/verify"))
                .timeout(REQUEST_TIMEOUT)
                .header("apikey", config.anonKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request, "Unable to verify code");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SupabaseAuthException(extractError(
                    response.body(),
                    "Invalid or expired verification code. Please check the code or request a new one."));
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.url() + "/auth/v1/resend"))
                .timeout(REQUEST_TIMEOUT)
                .header("apikey", config.anonKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request, "Unable to resend verification code");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SupabaseAuthException(extractError(
                    response.body(),
                    "Unable to resend verification code (HTTP " + response.statusCode() + ")"));
        }
    }

    public SupabaseSession signIn(String email, String password) throws SupabaseAuthException {
        String cleanEmail = requireEmail(email);
        requirePassword(password);

        JsonObject payload = new JsonObject();
        payload.addProperty("email", cleanEmail);
        payload.addProperty("password", password);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.url() + "/auth/v1/token?grant_type=password"))
                .timeout(REQUEST_TIMEOUT)
                .header("apikey", config.anonKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request, "Unable to sign in");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SupabaseAuthException(extractError(response.body(), "Invalid email or password."));
        }

        JsonObject body = parseObject(response.body());
        SupabaseSession session = parseSession(body, cleanEmail);
        this.currentSession = session;
        return session;
    }

    public void signOut() {
        SupabaseSession session = this.currentSession;
        this.currentSession = null;
        if (session == null || session.accessToken() == null || session.accessToken().isBlank()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.url() + "/auth/v1/logout"))
                .timeout(Duration.ofSeconds(10))
                .header("apikey", config.anonKey())
                .header("Authorization", "Bearer " + session.accessToken())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    public SupabaseSession getCurrentSession() {
        return currentSession;
    }

    public boolean isAuthenticated() {
        SupabaseSession session = currentSession;
        return session != null && session.accessToken() != null && !session.accessToken().isBlank();
    }

    public String getAccessToken() {
        SupabaseSession session = currentSession;
        if (session == null || session.accessToken() == null || session.accessToken().isBlank()) {
            throw new SupabaseException("You are not signed in. Please sign in again.");
        }
        return session.accessToken();
    }

    public String getUserId() {
        SupabaseSession session = currentSession;
        if (session == null || session.userId() == null || session.userId().isBlank()) {
            throw new SupabaseException("Active user session is missing a user id. Please sign in again.");
        }
        return session.userId();
    }

    public String getEmail() {
        SupabaseSession session = currentSession;
        return session == null ? null : session.email();
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

    private static SupabaseSession parseSession(JsonObject body, String fallbackEmail) throws SupabaseAuthException {
        String accessToken = stringField(body, "access_token");
        String refreshToken = stringField(body, "refresh_token");
        JsonObject user = body.has("user") && body.get("user").isJsonObject()
                ? body.getAsJsonObject("user")
                : null;
        String userId = user == null ? null : stringField(user, "id");
        String email = extractEmail(body, fallbackEmail);
        if (accessToken == null || accessToken.isBlank() || userId == null || userId.isBlank()) {
            throw new SupabaseAuthException("Supabase returned an incomplete authentication session.");
        }
        return new SupabaseSession(accessToken, refreshToken, userId, email);
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
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw new SupabaseAuthException("Unexpected authentication response from Supabase.");
            }
            return element.getAsJsonObject();
        } catch (RuntimeException error) {
            if (error.getCause() instanceof SupabaseAuthException authError) {
                throw authError;
            }
            throw new SupabaseAuthException("Unable to parse Supabase authentication response.", error);
        }
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

    private static String requireOtpToken(String token) throws SupabaseAuthException {
        if (token == null || token.isBlank()) {
            throw new SupabaseAuthException("Please enter the verification code from your email.");
        }
        return token.trim();
    }
}
