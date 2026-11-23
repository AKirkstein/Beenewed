package com.beenewed.supabase;

import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.model.AppPreferences;
import com.beenewed.backend.model.BillingCycle;
import com.beenewed.backend.model.Contribution;
import com.beenewed.backend.model.Member;
import com.beenewed.backend.model.Payment;
import com.beenewed.backend.model.PriceHistory;
import com.beenewed.backend.model.Subscription;
import com.beenewed.backend.model.SubscriptionStatus;
import com.beenewed.backend.model.Theme;
import com.beenewed.backend.model.UsageLog;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PostgREST client for Beenewed's Supabase PostgreSQL tables ({@code /rest/v1/<table>}).
 * All requests authenticate with {@code apikey} and the active user's Bearer JWT so
 * PostgreSQL Row Level Security scopes every operation to {@code auth.uid() = user_id}.
 */
public final class SupabaseRestClient implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final SupabaseConfig config;
    private final SupabaseAuthService authService;
    private final HttpClient http;
    private final ExecutorService fetchPool;
    private final Gson gson = new Gson();

    public record UserDataSnapshot(
            List<Subscription> subscriptions,
            List<Payment> payments,
            List<Member> members,
            List<Contribution> contributions,
            List<UsageLog> usageLogs,
            List<PriceHistory> priceHistory,
            AppPreferences preferences) {
    }

    public SupabaseRestClient(SupabaseConfig config, SupabaseAuthService authService) {
        this(
                config,
                authService,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    public SupabaseRestClient(
            SupabaseConfig config,
            SupabaseAuthService authService,
            HttpClient http) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.fetchPool = Executors.newFixedThreadPool(7, runnable -> {
            Thread thread = new Thread(runnable, "beenewed-supabase-fetch");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Shuts down the parallel fetch executor cleanly when the application exits. */
    @Override
    public void close() {
        fetchPool.shutdownNow();
    }

    public boolean isClosed() {
        return fetchPool.isShutdown();
    }

    /** True when the failure reflects a network/connectivity outage rather than an auth or validation error. */
    public static boolean isConnectivityFailure(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof IOException) {
                return true;
            }
            String msg = cursor.getMessage();
            if (msg != null && (msg.contains("Could not reach Supabase") || msg.contains("could not reach Supabase"))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * Fetches all 7 user-scoped tables concurrently in a single parallel batch so
     * initial login and manual refreshes avoid sequential round-trips and N+1 queries.
     */
    public UserDataSnapshot fetchAllUserData() {
        CompletableFuture<List<Subscription>> subsFuture =
                CompletableFuture.supplyAsync(this::findAllSubscriptions, fetchPool);
        CompletableFuture<List<Payment>> paysFuture =
                CompletableFuture.supplyAsync(this::findAllPayments, fetchPool);
        CompletableFuture<List<Member>> membersFuture =
                CompletableFuture.supplyAsync(this::findAllMembers, fetchPool);
        CompletableFuture<List<Contribution>> contribsFuture =
                CompletableFuture.supplyAsync(this::findAllContributions, fetchPool);
        CompletableFuture<List<UsageLog>> usagesFuture =
                CompletableFuture.supplyAsync(this::findAllUsageLogs, fetchPool);
        CompletableFuture<List<PriceHistory>> pricesFuture =
                CompletableFuture.supplyAsync(this::findAllPriceHistory, fetchPool);
        CompletableFuture<AppPreferences> prefsFuture =
                CompletableFuture.supplyAsync(this::getPreferences, fetchPool);

        try {
            CompletableFuture.allOf(
                    subsFuture, paysFuture, membersFuture, contribsFuture,
                    usagesFuture, pricesFuture, prefsFuture).join();
            return new UserDataSnapshot(
                    subsFuture.join(),
                    paysFuture.join(),
                    membersFuture.join(),
                    contribsFuture.join(),
                    usagesFuture.join(),
                    pricesFuture.join(),
                    prefsFuture.join());
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new SupabaseException("Unable to load account data from Supabase.", cause);
        }
    }

    // =====================================================================
    // Bulk Table Reads
    // =====================================================================

    public List<Subscription> findAllSubscriptions() {
        return parseSubscriptions(getArray("subscriptions", "select=*&order=name.asc,id.asc"));
    }

    public List<Payment> findAllPayments() {
        return parsePayments(getArray("payments", "select=*&order=payment_date.desc,id.desc"));
    }

    public List<Member> findAllMembers() {
        return parseMembers(getArray("members", "select=*&order=name.asc,id.asc"));
    }

    public List<Contribution> findAllContributions() {
        return parseContributions(getArray("contributions", "select=*&order=date_received.desc,id.desc"));
    }

    public List<UsageLog> findAllUsageLogs() {
        return parseUsageLogs(getArray("usage_logs", "select=*&order=usage_date.desc,id.desc"));
    }

    public List<PriceHistory> findAllPriceHistory() {
        return parsePriceHistoryList(getArray("price_history", "select=*&order=effective_date.desc,id.desc"));
    }

    // =====================================================================
    // Subscriptions Mutations
    // =====================================================================

    public Subscription createSubscription(Subscription subscription) {
        validateSubscription(subscription);
        if (subscription.id() != null) {
            throw new ValidationException("A new subscription must not have an id");
        }
        JsonObject payload = subscriptionPayload(subscription, true);
        JsonArray rows = postArray("subscriptions", null, payload, "return=representation");
        return parseSubscription(requireSingleRow(rows, "Failed to create subscription"));
    }

    public Subscription updateSubscription(Subscription subscription) {
        validateSubscription(subscription);
        if (subscription.id() == null || subscription.id() <= 0) {
            throw new ValidationException("An updated subscription must have a positive id");
        }
        JsonObject payload = subscriptionPayload(subscription, false);
        JsonArray rows = patchArray("subscriptions", "id=eq." + subscription.id(), payload);
        if (rows.isEmpty()) {
            throw new NotFoundException("Subscription not found: " + subscription.id());
        }
        return parseSubscription(rows.get(0).getAsJsonObject());
    }

    public Subscription updateSubscriptionStatus(
            Long subscriptionId,
            SubscriptionStatus status,
            LocalDate nextRenewalDate) {
        requireId(subscriptionId, "subscriptionId");
        if (status == null || nextRenewalDate == null) {
            throw new ValidationException("Subscription status and renewal date are required");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("status", status.name());
        payload.addProperty("next_renewal_date", nextRenewalDate.toString());
        JsonArray rows = patchArray("subscriptions", "id=eq." + subscriptionId, payload);
        if (rows.isEmpty()) {
            throw new NotFoundException("Subscription not found: " + subscriptionId);
        }
        return parseSubscription(rows.get(0).getAsJsonObject());
    }

    public boolean deleteSubscription(Long subscriptionId) {
        requireId(subscriptionId, "subscriptionId");
        JsonArray deleted = deleteArray("subscriptions", "id=eq." + subscriptionId);
        if (deleted.isEmpty()) {
            throw new NotFoundException("Subscription not found: " + subscriptionId);
        }
        return true;
    }

    // =====================================================================
    // Payments Mutations
    // =====================================================================

    public Payment recordPayment(Payment payment) {
        validatePayment(payment);
        if (payment.id() != null) {
            throw new ValidationException("A new payment must not have an id");
        }
        JsonObject payload = paymentPayload(payment, true);
        JsonArray rows = postArray("payments", null, payload, "return=representation");
        return parsePayment(requireSingleRow(rows, "Failed to record payment"));
    }

    public Payment updatePayment(Payment payment) {
        validatePayment(payment);
        if (payment.id() == null || payment.id() <= 0) {
            throw new ValidationException("An updated payment must have a positive id");
        }
        JsonObject payload = paymentPayload(payment, false);
        JsonArray rows = patchArray("payments", "id=eq." + payment.id(), payload);
        if (rows.isEmpty()) {
            throw new NotFoundException("Payment not found: " + payment.id());
        }
        return parsePayment(rows.get(0).getAsJsonObject());
    }

    public boolean deletePayment(Long paymentId) {
        requireId(paymentId, "paymentId");
        JsonArray deleted = deleteArray("payments", "id=eq." + paymentId);
        if (deleted.isEmpty()) {
            throw new NotFoundException("Payment not found: " + paymentId);
        }
        return true;
    }

    // =====================================================================
    // Members Mutations
    // =====================================================================

    public Member addMember(Long subscriptionId, Member member) {
        validateMember(subscriptionId, member);
        if (member.id() != null) {
            throw new ValidationException("A new member must not have an id");
        }
        JsonObject payload = memberPayload(member, true);
        JsonArray rows = postArray("members", null, payload, "return=representation");
        return parseMember(requireSingleRow(rows, "Failed to add member"));
    }

    public Member updateMember(Long subscriptionId, Member member) {
        validateMember(subscriptionId, member);
        if (member.id() == null || member.id() <= 0) {
            throw new ValidationException("An updated member must have a positive id");
        }
        JsonObject payload = memberPayload(member, false);
        JsonArray rows = patchArray(
                "members",
                "subscription_id=eq." + subscriptionId + "&id=eq." + member.id(),
                payload);
        if (rows.isEmpty()) {
            throw new NotFoundException("Member not found: " + member.id());
        }
        return parseMember(rows.get(0).getAsJsonObject());
    }

    public boolean deleteMember(Long subscriptionId, Long memberId) {
        requireId(subscriptionId, "subscriptionId");
        requireId(memberId, "memberId");
        JsonArray deleted = deleteArray(
                "members",
                "subscription_id=eq." + subscriptionId + "&id=eq." + memberId);
        if (deleted.isEmpty()) {
            throw new NotFoundException("Member not found: " + memberId);
        }
        return true;
    }

    // =====================================================================
    // Contributions Mutations
    // =====================================================================

    public Contribution recordContribution(Long paymentId, Contribution contribution) {
        validateContribution(paymentId, contribution);
        if (contribution.id() != null) {
            throw new ValidationException("A new contribution must not have an id");
        }
        JsonObject payload = contributionPayload(contribution, true);
        JsonArray rows = postArray("contributions", null, payload, "return=representation");
        return parseContribution(requireSingleRow(rows, "Failed to record contribution"));
    }

    public Contribution updateContribution(Long paymentId, Contribution contribution) {
        validateContribution(paymentId, contribution);
        if (contribution.id() == null || contribution.id() <= 0) {
            throw new ValidationException("An updated contribution must have a positive id");
        }
        JsonObject payload = contributionPayload(contribution, false);
        JsonArray rows = patchArray(
                "contributions",
                "payment_id=eq." + paymentId + "&id=eq." + contribution.id(),
                payload);
        if (rows.isEmpty()) {
            throw new NotFoundException("Contribution not found: " + contribution.id());
        }
        return parseContribution(rows.get(0).getAsJsonObject());
    }

    public boolean deleteContribution(Long paymentId, Long contributionId) {
        requireId(paymentId, "paymentId");
        requireId(contributionId, "contributionId");
        JsonArray deleted = deleteArray(
                "contributions",
                "payment_id=eq." + paymentId + "&id=eq." + contributionId);
        if (deleted.isEmpty()) {
            throw new NotFoundException("Contribution not found: " + contributionId);
        }
        return true;
    }

    // =====================================================================
    // Usage Logs Mutations
    // =====================================================================

    public UsageLog addUsageLog(Long subscriptionId, UsageLog usageLog) {
        validateUsageLog(subscriptionId, usageLog);
        if (usageLog.id() != null) {
            throw new ValidationException("A new usage log must not have an id");
        }
        JsonObject payload = usageLogPayload(usageLog, true);
        JsonArray rows = postArray("usage_logs", null, payload, "return=representation");
        return parseUsageLog(requireSingleRow(rows, "Failed to add usage log"));
    }

    public UsageLog updateUsageLog(Long subscriptionId, UsageLog usageLog) {
        validateUsageLog(subscriptionId, usageLog);
        if (usageLog.id() == null || usageLog.id() <= 0) {
            throw new ValidationException("An updated usage log must have a positive id");
        }
        JsonObject payload = usageLogPayload(usageLog, false);
        JsonArray rows = patchArray(
                "usage_logs",
                "subscription_id=eq." + subscriptionId + "&id=eq." + usageLog.id(),
                payload);
        if (rows.isEmpty()) {
            throw new NotFoundException("Usage log not found: " + usageLog.id());
        }
        return parseUsageLog(rows.get(0).getAsJsonObject());
    }

    public boolean deleteUsageLog(Long subscriptionId, Long usageLogId) {
        requireId(subscriptionId, "subscriptionId");
        requireId(usageLogId, "usageLogId");
        JsonArray deleted = deleteArray(
                "usage_logs",
                "subscription_id=eq." + subscriptionId + "&id=eq." + usageLogId);
        if (deleted.isEmpty()) {
            throw new NotFoundException("Usage log not found: " + usageLogId);
        }
        return true;
    }

    // =====================================================================
    // Price History Mutations
    // =====================================================================

    public PriceHistory recordPriceChange(
            Long subscriptionId,
            BigDecimal previousPrice,
            BigDecimal newPrice,
            LocalDate effectiveDate,
            String reason) {
        requireId(subscriptionId, "subscriptionId");
        if (previousPrice == null || previousPrice.signum() <= 0) {
            throw new ValidationException("Previous price must be greater than zero");
        }
        if (newPrice == null || newPrice.signum() <= 0) {
            throw new ValidationException("New price must be greater than zero");
        }
        if (effectiveDate == null) {
            throw new ValidationException("Price effective date is required");
        }
        if (newPrice.compareTo(previousPrice) == 0) {
            throw new ValidationException("New price must differ from the current price");
        }

        JsonObject historyPayload = new JsonObject();
        historyPayload.addProperty("user_id", authService.getUserId());
        historyPayload.addProperty("subscription_id", subscriptionId);
        historyPayload.addProperty("previous_price", previousPrice);
        historyPayload.addProperty("new_price", newPrice);
        historyPayload.addProperty("effective_date", effectiveDate.toString());
        setNullableString(historyPayload, "reason", reason);

        JsonArray inserted = postArray("price_history", null, historyPayload, "return=representation");
        PriceHistory history = parsePriceHistory(requireSingleRow(inserted, "Failed to record price history"));

        JsonObject priceUpdate = new JsonObject();
        priceUpdate.addProperty("current_price", newPrice);
        JsonArray updated = patchArray("subscriptions", "id=eq." + subscriptionId, priceUpdate);
        if (updated.isEmpty()) {
            throw new NotFoundException("Subscription not found: " + subscriptionId);
        }
        return history;
    }

    // =====================================================================
    // App Preferences (per authenticated user)
    // =====================================================================

    public AppPreferences getPreferences() {
        String userId = authService.getUserId();
        JsonArray rows = getArray("app_preferences", "select=*&user_id=eq." + encode(userId));
        if (!rows.isEmpty()) {
            return parsePreferences(rows.get(0).getAsJsonObject());
        }
        AppPreferences defaults = new AppPreferences(1L, "PHP", Theme.LIGHT, 7, true);
        return updatePreferences(defaults);
    }

    public AppPreferences updatePreferences(AppPreferences preferences) {
        if (preferences == null) {
            throw new ValidationException("Application preferences are required");
        }
        if (preferences.currency() == null || preferences.currency().isBlank()) {
            throw new ValidationException("Currency must not be blank");
        }
        if (preferences.theme() == null) {
            throw new ValidationException("Theme is required");
        }
        if (preferences.reminderLeadDays() < 0) {
            throw new ValidationException("Reminder lead days must not be negative");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("user_id", authService.getUserId());
        payload.addProperty("currency", preferences.currency().trim());
        payload.addProperty("theme", preferences.theme().name());
        payload.addProperty("reminder_lead_days", preferences.reminderLeadDays());
        payload.addProperty("show_overdue_reminders", preferences.showOverdueReminders());

        JsonArray rows = postArray(
                "app_preferences",
                "on_conflict=user_id",
                payload,
                "resolution=merge-duplicates,return=representation");
        return parsePreferences(requireSingleRow(rows, "Failed to save application preferences"));
    }

    // =====================================================================
    // HTTP Transport Helpers
    // =====================================================================

    private JsonArray getArray(String table, String query) {
        HttpRequest request = authorizedBuilder(table, query)
                .GET()
                .build();
        return sendForArray(request, "GET " + table);
    }

    private JsonArray postArray(String table, String query, JsonObject body, String prefer) {
        HttpRequest.Builder builder = authorizedBuilder(table, query)
                .header("Content-Type", "application/json");
        if (prefer != null && !prefer.isBlank()) {
            builder.header("Prefer", prefer);
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        return sendForArray(request, "POST " + table);
    }

    private JsonArray patchArray(String table, String query, JsonObject body) {
        HttpRequest request = authorizedBuilder(table, query)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        return sendForArray(request, "PATCH " + table);
    }

    private JsonArray deleteArray(String table, String query) {
        HttpRequest request = authorizedBuilder(table, query)
                .header("Prefer", "return=representation")
                .DELETE()
                .build();
        return sendForArray(request, "DELETE " + table);
    }

    private HttpRequest.Builder authorizedBuilder(String table, String query) {
        String uri = config.url() + "/rest/v1/" + table + (query == null || query.isBlank() ? "" : "?" + query);
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(REQUEST_TIMEOUT)
                .header("apikey", config.anonKey())
                .header("Authorization", "Bearer " + authService.getAccessToken())
                .header("Accept", "application/json");
    }

    private JsonArray sendForArray(HttpRequest request, String operation) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SupabaseException("Supabase request interrupted during " + operation, error);
        } catch (IOException error) {
            throw new SupabaseException("Could not reach Supabase during " + operation + ": " + error.getMessage(), error);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw translateHttpError(status, response.body(), operation);
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            return new JsonArray();
        }
        JsonElement parsed = JsonParser.parseString(body);
        if (parsed.isJsonArray()) {
            return parsed.getAsJsonArray();
        }
        if (parsed.isJsonObject()) {
            JsonArray single = new JsonArray();
            single.add(parsed.getAsJsonObject());
            return single;
        }
        return new JsonArray();
    }

    private RuntimeException translateHttpError(int status, String body, String operation) {
        String code = null;
        String message = null;
        String details = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonElement element = JsonParser.parseString(body);
                if (element.isJsonObject()) {
                    JsonObject obj = element.getAsJsonObject();
                    code = nullableString(obj, "code");
                    message = nullableString(obj, "message");
                    details = nullableString(obj, "details");
                }
            } catch (RuntimeException ignored) {
                // Fall back to raw status message
            }
        }
        if (status == 401) {
            return new SupabaseException("Your Supabase session has expired. Please log out and sign in again.");
        }
        if ("23503".equals(code)) {
            if (operation.startsWith("DELETE subscriptions")) {
                return new ValidationException("Cannot delete this subscription while it has recorded payments.");
            }
            if (operation.startsWith("DELETE payments")) {
                return new ValidationException("Cannot delete this payment while it has recorded contributions.");
            }
            if (operation.startsWith("DELETE members")) {
                return new ValidationException("Cannot delete this member while they have recorded contributions.");
            }
            return new ValidationException(message != null ? message : "This record is referenced by other records.");
        }
        if ("23514".equals(code)) {
            return new ValidationException(message != null ? message : "Input failed database validation constraints.");
        }
        String combined = message != null ? message : "Supabase request failed (" + operation + ", HTTP " + status + ")";
        if (details != null && !details.isBlank()) {
            combined = combined + " (" + details + ")";
        }
        return new SupabaseException(combined);
    }

    // =====================================================================
    // JSON Serialization & Mapping
    // =====================================================================

    private JsonObject subscriptionPayload(Subscription subscription, boolean includeUserId) {
        JsonObject obj = new JsonObject();
        if (includeUserId) {
            obj.addProperty("user_id", authService.getUserId());
        }
        obj.addProperty("name", subscription.name().trim());
        obj.addProperty("category", subscription.category().trim());
        setNullableString(obj, "icon_key", subscription.iconKey());
        obj.addProperty("current_price", subscription.currentPrice());
        obj.addProperty("billing_cycle", subscription.billingCycle().name());
        obj.addProperty("next_renewal_date", subscription.nextRenewalDate().toString());
        obj.addProperty("status", subscription.status().name());
        return obj;
    }

    private JsonObject paymentPayload(Payment payment, boolean includeUserId) {
        JsonObject obj = new JsonObject();
        if (includeUserId) {
            obj.addProperty("user_id", authService.getUserId());
        }
        obj.addProperty("subscription_id", payment.subscriptionId());
        obj.addProperty("payment_date", payment.paymentDate().toString());
        obj.addProperty("amount_paid", payment.amountPaid());
        obj.addProperty("renewal_period", payment.renewalPeriod().trim());
        setNullableString(obj, "payment_method", payment.paymentMethod());
        setNullableString(obj, "notes", payment.notes());
        return obj;
    }

    private JsonObject memberPayload(Member member, boolean includeUserId) {
        JsonObject obj = new JsonObject();
        if (includeUserId) {
            obj.addProperty("user_id", authService.getUserId());
        }
        obj.addProperty("subscription_id", member.subscriptionId());
        obj.addProperty("name", member.name().trim());
        setNullableString(obj, "role_notes", member.roleNotes());
        return obj;
    }

    private JsonObject contributionPayload(Contribution contribution, boolean includeUserId) {
        JsonObject obj = new JsonObject();
        if (includeUserId) {
            obj.addProperty("user_id", authService.getUserId());
        }
        obj.addProperty("member_id", contribution.memberId());
        obj.addProperty("payment_id", contribution.paymentId());
        obj.addProperty("amount_received", contribution.amountReceived());
        obj.addProperty("date_received", contribution.dateReceived().toString());
        return obj;
    }

    private JsonObject usageLogPayload(UsageLog usageLog, boolean includeUserId) {
        JsonObject obj = new JsonObject();
        if (includeUserId) {
            obj.addProperty("user_id", authService.getUserId());
        }
        obj.addProperty("subscription_id", usageLog.subscriptionId());
        obj.addProperty("usage_date", usageLog.usageDate().toString());
        if (usageLog.quantity() == null) {
            obj.add("quantity", JsonNull.INSTANCE);
        } else {
            obj.addProperty("quantity", usageLog.quantity());
        }
        setNullableString(obj, "unit", usageLog.unit());
        setNullableString(obj, "notes", usageLog.notes());
        return obj;
    }

    private static List<Subscription> parseSubscriptions(JsonArray rows) {
        List<Subscription> list = new ArrayList<>(rows.size());
        for (JsonElement element : rows) {
            list.add(parseSubscription(element.getAsJsonObject()));
        }
        return list;
    }

    private static Subscription parseSubscription(JsonObject obj) {
        return new Subscription(
                obj.get("id").getAsLong(),
                obj.get("name").getAsString(),
                obj.get("category").getAsString(),
                nullableString(obj, "icon_key"),
                money(obj, "current_price"),
                BillingCycle.valueOf(obj.get("billing_cycle").getAsString()),
                LocalDate.parse(obj.get("next_renewal_date").getAsString()),
                SubscriptionStatus.valueOf(obj.get("status").getAsString()),
                timestamp(obj, "created_at"),
                timestamp(obj, "updated_at"));
    }

    private static List<Payment> parsePayments(JsonArray rows) {
        List<Payment> list = new ArrayList<>(rows.size());
        for (JsonElement element : rows) {
            list.add(parsePayment(element.getAsJsonObject()));
        }
        return list;
    }

    private static Payment parsePayment(JsonObject obj) {
        return new Payment(
                obj.get("id").getAsLong(),
                obj.get("subscription_id").getAsLong(),
                LocalDate.parse(obj.get("payment_date").getAsString()),
                money(obj, "amount_paid"),
                obj.get("renewal_period").getAsString(),
                nullableString(obj, "payment_method"),
                nullableString(obj, "notes"),
                timestamp(obj, "created_at"));
    }

    private static List<Member> parseMembers(JsonArray rows) {
        List<Member> list = new ArrayList<>(rows.size());
        for (JsonElement element : rows) {
            list.add(parseMember(element.getAsJsonObject()));
        }
        return list;
    }

    private static Member parseMember(JsonObject obj) {
        return new Member(
                obj.get("id").getAsLong(),
                obj.get("subscription_id").getAsLong(),
                obj.get("name").getAsString(),
                nullableString(obj, "role_notes"),
                timestamp(obj, "created_at"),
                timestamp(obj, "updated_at"));
    }

    private static List<Contribution> parseContributions(JsonArray rows) {
        List<Contribution> list = new ArrayList<>(rows.size());
        for (JsonElement element : rows) {
            list.add(parseContribution(element.getAsJsonObject()));
        }
        return list;
    }

    private static Contribution parseContribution(JsonObject obj) {
        return new Contribution(
                obj.get("id").getAsLong(),
                obj.get("member_id").getAsLong(),
                obj.get("payment_id").getAsLong(),
                money(obj, "amount_received"),
                LocalDate.parse(obj.get("date_received").getAsString()),
                timestamp(obj, "created_at"));
    }

    private static List<UsageLog> parseUsageLogs(JsonArray rows) {
        List<UsageLog> list = new ArrayList<>(rows.size());
        for (JsonElement element : rows) {
            list.add(parseUsageLog(element.getAsJsonObject()));
        }
        return list;
    }

    private static UsageLog parseUsageLog(JsonObject obj) {
        Double quantity = (!obj.has("quantity") || obj.get("quantity").isJsonNull())
                ? null
                : obj.get("quantity").getAsDouble();
        return new UsageLog(
                obj.get("id").getAsLong(),
                obj.get("subscription_id").getAsLong(),
                LocalDate.parse(obj.get("usage_date").getAsString()),
                quantity,
                nullableString(obj, "unit"),
                nullableString(obj, "notes"),
                timestamp(obj, "created_at"));
    }

    private static List<PriceHistory> parsePriceHistoryList(JsonArray rows) {
        List<PriceHistory> list = new ArrayList<>(rows.size());
        for (JsonElement element : rows) {
            list.add(parsePriceHistory(element.getAsJsonObject()));
        }
        return list;
    }

    private static PriceHistory parsePriceHistory(JsonObject obj) {
        return new PriceHistory(
                obj.get("id").getAsLong(),
                obj.get("subscription_id").getAsLong(),
                money(obj, "previous_price"),
                money(obj, "new_price"),
                LocalDate.parse(obj.get("effective_date").getAsString()),
                nullableString(obj, "reason"),
                timestamp(obj, "created_at"));
    }

    private static AppPreferences parsePreferences(JsonObject obj) {
        String currency = nullableString(obj, "currency");
        String themeRaw = nullableString(obj, "theme");
        int leadDays = obj.has("reminder_lead_days") && !obj.get("reminder_lead_days").isJsonNull()
                ? obj.get("reminder_lead_days").getAsInt()
                : 7;
        boolean showOverdue = !obj.has("show_overdue_reminders") || obj.get("show_overdue_reminders").isJsonNull()
                || obj.get("show_overdue_reminders").getAsBoolean();
        return new AppPreferences(
                1L,
                currency == null || currency.isBlank() ? "PHP" : currency,
                themeRaw == null || themeRaw.isBlank() ? Theme.LIGHT : Theme.valueOf(themeRaw),
                leadDays,
                showOverdue);
    }

    // =====================================================================
    // Validation & Small Utilities
    // =====================================================================

    private static void validateSubscription(Subscription subscription) {
        if (subscription == null) {
            throw new ValidationException("Subscription is required");
        }
        if (isBlank(subscription.name())) {
            throw new ValidationException("Subscription name must not be blank");
        }
        if (isBlank(subscription.category())) {
            throw new ValidationException("Subscription category must not be blank");
        }
        if (subscription.iconKey() != null && subscription.iconKey().isBlank()) {
            throw new ValidationException("Subscription icon key must not be blank");
        }
        if (subscription.currentPrice() == null || subscription.currentPrice().signum() <= 0) {
            throw new ValidationException("Subscription price must be greater than zero");
        }
        if (subscription.billingCycle() == null || subscription.nextRenewalDate() == null || subscription.status() == null) {
            throw new ValidationException("Billing cycle, renewal date, and status are required");
        }
    }

    private static void validatePayment(Payment payment) {
        if (payment == null) {
            throw new ValidationException("Payment is required");
        }
        requireId(payment.subscriptionId(), "Payment subscription id");
        if (payment.paymentDate() == null) {
            throw new ValidationException("Payment date is required");
        }
        if (payment.amountPaid() == null || payment.amountPaid().signum() <= 0) {
            throw new ValidationException("Payment amount must be greater than zero");
        }
        if (isBlank(payment.renewalPeriod())) {
            throw new ValidationException("Renewal period must not be blank");
        }
    }

    private static void validateMember(Long subscriptionId, Member member) {
        if (member == null) {
            throw new ValidationException("Member is required");
        }
        requireId(subscriptionId, "subscriptionId");
        if (!subscriptionId.equals(member.subscriptionId())) {
            throw new ValidationException("Member subscription does not match the requested scope");
        }
        if (isBlank(member.name())) {
            throw new ValidationException("Member name must not be blank");
        }
    }

    private static void validateContribution(Long paymentId, Contribution contribution) {
        if (contribution == null) {
            throw new ValidationException("Contribution is required");
        }
        requireId(paymentId, "paymentId");
        requireId(contribution.paymentId(), "Contribution payment id");
        if (!paymentId.equals(contribution.paymentId())) {
            throw new ValidationException("Contribution payment does not match the requested scope");
        }
        if (contribution.amountReceived() == null || contribution.amountReceived().signum() <= 0) {
            throw new ValidationException("Contribution amount must be greater than zero");
        }
        if (contribution.dateReceived() == null) {
            throw new ValidationException("Contribution date is required");
        }
    }

    private static void validateUsageLog(Long subscriptionId, UsageLog usageLog) {
        if (usageLog == null) {
            throw new ValidationException("Usage log is required");
        }
        requireId(subscriptionId, "subscriptionId");
        if (!subscriptionId.equals(usageLog.subscriptionId())) {
            throw new ValidationException("Usage log subscription does not match the requested scope");
        }
        if (usageLog.usageDate() == null) {
            throw new ValidationException("Usage date is required");
        }
        if (usageLog.quantity() != null) {
            if (!Double.isFinite(usageLog.quantity()) || usageLog.quantity() <= 0) {
                throw new ValidationException("Usage quantity must be greater than zero");
            }
            if (isBlank(usageLog.unit())) {
                throw new ValidationException("Usage unit is required when quantity is provided");
            }
        }
    }

    private static JsonObject requireSingleRow(JsonArray rows, String errorMessage) {
        if (rows == null || rows.isEmpty() || !rows.get(0).isJsonObject()) {
            throw new SupabaseException(errorMessage + ": Supabase returned no row.");
        }
        return rows.get(0).getAsJsonObject();
    }

    private static void setNullableString(JsonObject obj, String key, String value) {
        if (value == null || value.isBlank()) {
            obj.add(key, JsonNull.INSTANCE);
        } else {
            obj.addProperty(key, value.trim());
        }
    }

    private static String nullableString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        return obj.get(key).getAsString();
    }

    private static BigDecimal money(JsonObject obj, String key) {
        return obj.get(key).getAsBigDecimal().setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDateTime timestamp(JsonObject obj, String key) {
        String raw = nullableString(obj, key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(raw);
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
    }

    private static void requireId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new ValidationException(name + " must be a positive id");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
