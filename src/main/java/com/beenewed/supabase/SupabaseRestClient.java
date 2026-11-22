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
import com.beenewed.backend.services.DashboardService;
import com.beenewed.backend.services.ReportService;
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
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * PostgREST client for Beenewed's Supabase PostgreSQL tables ({@code /rest/v1/<table>}).
 * All requests authenticate with {@code apikey} and the active user's Bearer JWT so
 * PostgreSQL Row Level Security scopes every operation to {@code auth.uid() = user_id}.
 */
public final class SupabaseRestClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int RECENT_PAYMENT_LIMIT = 5;

    private final SupabaseConfig config;
    private final SupabaseAuthService authService;
    private final HttpClient http;
    private final Gson gson = new Gson();
    private final Clock clock;

    public SupabaseRestClient(SupabaseConfig config, SupabaseAuthService authService) {
        this(
                config,
                authService,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                Clock.systemDefaultZone());
    }

    public SupabaseRestClient(
            SupabaseConfig config,
            SupabaseAuthService authService,
            HttpClient http,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.http = Objects.requireNonNull(http, "http must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // =====================================================================
    // Subscriptions
    // =====================================================================

    public List<Subscription> findAllSubscriptions() {
        JsonArray rows = getArray("subscriptions", "select=*&order=name.asc,id.asc");
        return parseSubscriptions(rows);
    }

    public List<Subscription> findActiveSubscriptions() {
        JsonArray rows = getArray("subscriptions", "select=*&status=eq.ACTIVE&order=next_renewal_date.asc,id.asc");
        return parseSubscriptions(rows);
    }

    public List<Subscription> findFilteredSubscriptions(String search, String category, SubscriptionStatus status) {
        List<String> params = new ArrayList<>();
        params.add("select=*");
        if (category != null && !category.isBlank()) {
            params.add("category=eq." + encode(category.trim()));
        }
        if (status != null) {
            params.add("status=eq." + encode(status.name()));
        }
        if (search != null && !search.isBlank()) {
            String term = sanitizePostgrestPattern(search.trim());
            params.add("or=(name.ilike.*" + encode(term) + "*,category.ilike.*" + encode(term) + "*)");
        }
        params.add("order=name.asc,id.asc");
        return parseSubscriptions(getArray("subscriptions", String.join("&", params)));
    }

    public List<Subscription> findSubscriptionsByRenewalRange(
            LocalDate startDate,
            LocalDate endDate,
            SubscriptionStatus status) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new ValidationException("Renewal end date must not be before start date");
        }
        List<String> params = new ArrayList<>();
        params.add("select=*");
        if (startDate != null) {
            params.add("next_renewal_date=gte." + encode(startDate.toString()));
        }
        if (endDate != null) {
            params.add("next_renewal_date=lte." + encode(endDate.toString()));
        }
        if (status != null) {
            params.add("status=eq." + encode(status.name()));
        }
        params.add("order=next_renewal_date.asc,id.asc");
        return parseSubscriptions(getArray("subscriptions", String.join("&", params)));
    }

    public Subscription getSubscriptionById(Long id) {
        requireId(id, "subscriptionId");
        JsonArray rows = getArray("subscriptions", "select=*&id=eq." + id);
        if (rows.isEmpty()) {
            throw new NotFoundException("Subscription not found: " + id);
        }
        return parseSubscription(rows.get(0).getAsJsonObject());
    }

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
        Subscription existing = getSubscriptionById(subscription.id());
        if (subscription.status() != existing.status()) {
            throw new ValidationException("Use a status transition method to change subscription status");
        }
        JsonObject payload = subscriptionPayload(subscription, false);
        JsonArray rows = patchArray("subscriptions", "id=eq." + subscription.id(), payload);
        if (rows.isEmpty()) {
            throw new NotFoundException("Subscription not found: " + subscription.id());
        }
        return parseSubscription(rows.get(0).getAsJsonObject());
    }

    public Subscription pauseSubscription(Long subscriptionId) {
        Subscription subscription = getSubscriptionById(subscriptionId);
        if (subscription.status() != SubscriptionStatus.ACTIVE) {
            throw new ValidationException("Only active subscriptions can be paused");
        }
        return patchSubscriptionStatus(subscriptionId, SubscriptionStatus.PAUSED, subscription.nextRenewalDate());
    }

    public Subscription cancelSubscription(Long subscriptionId) {
        Subscription subscription = getSubscriptionById(subscriptionId);
        if (subscription.status() == SubscriptionStatus.CANCELLED) {
            throw new ValidationException("Subscription is already cancelled");
        }
        return patchSubscriptionStatus(subscriptionId, SubscriptionStatus.CANCELLED, subscription.nextRenewalDate());
    }

    public Subscription reactivateSubscription(Long subscriptionId, LocalDate nextRenewalDate) {
        Subscription subscription = getSubscriptionById(subscriptionId);
        if (subscription.status() == SubscriptionStatus.ACTIVE) {
            throw new ValidationException("Subscription is already active");
        }
        LocalDate renewal = nextRenewalDate == null ? subscription.nextRenewalDate() : nextRenewalDate;
        return patchSubscriptionStatus(subscriptionId, SubscriptionStatus.ACTIVE, renewal);
    }

    public boolean deleteSubscription(Long subscriptionId) {
        getSubscriptionById(subscriptionId);
        JsonArray deleted = deleteArray("subscriptions", "id=eq." + subscriptionId);
        if (deleted.isEmpty()) {
            throw new NotFoundException("Subscription not found: " + subscriptionId);
        }
        return true;
    }

    private Subscription patchSubscriptionStatus(Long subscriptionId, SubscriptionStatus status, LocalDate nextRenewalDate) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", status.name());
        payload.addProperty("next_renewal_date", nextRenewalDate.toString());
        JsonArray rows = patchArray("subscriptions", "id=eq." + subscriptionId, payload);
        if (rows.isEmpty()) {
            throw new NotFoundException("Subscription not found: " + subscriptionId);
        }
        return parseSubscription(rows.get(0).getAsJsonObject());
    }

    // =====================================================================
    // Payments
    // =====================================================================

    public List<Payment> findAllPayments() {
        JsonArray rows = getArray("payments", "select=*&order=payment_date.desc,id.desc");
        return parsePayments(rows);
    }

    public Payment getPaymentById(Long paymentId) {
        requireId(paymentId, "paymentId");
        JsonArray rows = getArray("payments", "select=*&id=eq." + paymentId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Payment not found: " + paymentId);
        }
        return parsePayment(rows.get(0).getAsJsonObject());
    }

    public List<Payment> findPaymentsBySubscriptionId(Long subscriptionId) {
        getSubscriptionById(subscriptionId);
        JsonArray rows = getArray(
                "payments",
                "select=*&subscription_id=eq." + subscriptionId + "&order=payment_date.desc,id.desc");
        return parsePayments(rows);
    }

    public List<Payment> findPaymentsByDateRange(LocalDate startDate, LocalDate endDate) {
        List<String> params = new ArrayList<>();
        params.add("select=*");
        if (startDate != null) {
            params.add("payment_date=gte." + encode(startDate.toString()));
        }
        if (endDate != null) {
            params.add("payment_date=lte." + encode(endDate.toString()));
        }
        params.add("order=payment_date.desc,id.desc");
        return parsePayments(getArray("payments", String.join("&", params)));
    }

    public List<Payment> findFilteredPayments(
            Long subscriptionId,
            LocalDate startDate,
            LocalDate endDate,
            SubscriptionStatus subscriptionStatus,
            String search) {
        List<String> params = new ArrayList<>();
        params.add("select=*");
        if (subscriptionId != null) {
            getSubscriptionById(subscriptionId);
            params.add("subscription_id=eq." + subscriptionId);
        }
        if (startDate != null) {
            params.add("payment_date=gte." + encode(startDate.toString()));
        }
        if (endDate != null) {
            params.add("payment_date=lte." + encode(endDate.toString()));
        }
        params.add("order=payment_date.desc,id.desc");
        List<Payment> payments = parsePayments(getArray("payments", String.join("&", params)));

        if (subscriptionStatus == null && (search == null || search.isBlank())) {
            return payments;
        }
        Map<Long, Subscription> subscriptionsById = new HashMap<>();
        for (Subscription subscription : findAllSubscriptions()) {
            subscriptionsById.put(subscription.id(), subscription);
        }
        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(Locale.ROOT);
        List<Payment> filtered = new ArrayList<>();
        for (Payment payment : payments) {
            Subscription subscription = subscriptionsById.get(payment.subscriptionId());
            if (subscription == null) {
                continue;
            }
            if (subscriptionStatus != null && subscription.status() != subscriptionStatus) {
                continue;
            }
            if (needle != null) {
                boolean matches = containsIgnoreCase(subscription.name(), needle)
                        || containsIgnoreCase(subscription.category(), needle)
                        || containsIgnoreCase(payment.renewalPeriod(), needle)
                        || containsIgnoreCase(payment.paymentMethod(), needle)
                        || containsIgnoreCase(payment.notes(), needle);
                if (!matches) {
                    continue;
                }
            }
            filtered.add(payment);
        }
        return filtered;
    }

    public Payment recordPayment(Payment payment) {
        validatePayment(payment);
        if (payment.id() != null) {
            throw new ValidationException("A new payment must not have an id");
        }
        getSubscriptionById(payment.subscriptionId());
        JsonObject payload = paymentPayload(payment, true);
        JsonArray rows = postArray("payments", null, payload, "return=representation");
        return parsePayment(requireSingleRow(rows, "Failed to record payment"));
    }

    public Payment updatePayment(Payment payment) {
        validatePayment(payment);
        if (payment.id() == null || payment.id() <= 0) {
            throw new ValidationException("An updated payment must have a positive id");
        }
        Payment existing = getPaymentById(payment.id());
        getSubscriptionById(payment.subscriptionId());
        BigDecimal contributionTotal = sumContributionsForPayment(payment.id());
        if (!existing.subscriptionId().equals(payment.subscriptionId()) && contributionTotal.signum() > 0) {
            throw new ValidationException("A payment with contributions cannot be moved to another subscription");
        }
        if (contributionTotal.compareTo(payment.amountPaid()) > 0) {
            throw new ValidationException("Payment amount cannot be less than its total contributions");
        }
        JsonObject payload = paymentPayload(payment, false);
        JsonArray rows = patchArray("payments", "id=eq." + payment.id(), payload);
        if (rows.isEmpty()) {
            throw new NotFoundException("Payment not found: " + payment.id());
        }
        return parsePayment(rows.get(0).getAsJsonObject());
    }

    public boolean deletePayment(Long paymentId) {
        getPaymentById(paymentId);
        JsonArray deleted = deleteArray("payments", "id=eq." + paymentId);
        if (deleted.isEmpty()) {
            throw new NotFoundException("Payment not found: " + paymentId);
        }
        return true;
    }

    // =====================================================================
    // Members
    // =====================================================================

    public List<Member> findAllMembers() {
        JsonArray rows = getArray("members", "select=*&order=name.asc,id.asc");
        return parseMembers(rows);
    }

    public List<Member> findMembersBySubscriptionId(Long subscriptionId) {
        getSubscriptionById(subscriptionId);
        JsonArray rows = getArray(
                "members",
                "select=*&subscription_id=eq." + subscriptionId + "&order=name.asc,id.asc");
        return parseMembers(rows);
    }

    public Member getMember(Long subscriptionId, Long memberId) {
        requireId(subscriptionId, "subscriptionId");
        requireId(memberId, "memberId");
        JsonArray rows = getArray(
                "members",
                "select=*&subscription_id=eq." + subscriptionId + "&id=eq." + memberId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Member not found: " + memberId);
        }
        return parseMember(rows.get(0).getAsJsonObject());
    }

    public Member addMember(Long subscriptionId, Member member) {
        validateMember(subscriptionId, member);
        if (member.id() != null) {
            throw new ValidationException("A new member must not have an id");
        }
        getSubscriptionById(subscriptionId);
        JsonObject payload = memberPayload(member, true);
        JsonArray rows = postArray("members", null, payload, "return=representation");
        return parseMember(requireSingleRow(rows, "Failed to add member"));
    }

    public Member updateMember(Long subscriptionId, Member member) {
        validateMember(subscriptionId, member);
        if (member.id() == null || member.id() <= 0) {
            throw new ValidationException("An updated member must have a positive id");
        }
        getMember(subscriptionId, member.id());
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
        getMember(subscriptionId, memberId);
        JsonArray deleted = deleteArray(
                "members",
                "subscription_id=eq." + subscriptionId + "&id=eq." + memberId);
        if (deleted.isEmpty()) {
            throw new NotFoundException("Member not found: " + memberId);
        }
        return true;
    }

    // =====================================================================
    // Contributions
    // =====================================================================

    public List<Contribution> findAllContributions() {
        JsonArray rows = getArray("contributions", "select=*&order=date_received.desc,id.desc");
        return parseContributions(rows);
    }

    public List<Contribution> findContributionsByPaymentId(Long paymentId) {
        getPaymentById(paymentId);
        JsonArray rows = getArray(
                "contributions",
                "select=*&payment_id=eq." + paymentId + "&order=date_received.desc,id.desc");
        return parseContributions(rows);
    }

    public Contribution getContribution(Long paymentId, Long contributionId) {
        requireId(paymentId, "paymentId");
        requireId(contributionId, "contributionId");
        JsonArray rows = getArray(
                "contributions",
                "select=*&payment_id=eq." + paymentId + "&id=eq." + contributionId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Contribution not found: " + contributionId);
        }
        return parseContribution(rows.get(0).getAsJsonObject());
    }

    public Contribution recordContribution(Long paymentId, Contribution contribution) {
        validateContribution(paymentId, contribution);
        if (contribution.id() != null) {
            throw new ValidationException("A new contribution must not have an id");
        }
        Payment payment = getPaymentById(paymentId);
        requireMemberForPayment(payment, contribution.memberId());
        BigDecimal prospective = sumContributionsForPayment(paymentId).add(contribution.amountReceived());
        if (prospective.compareTo(payment.amountPaid()) > 0) {
            throw new ValidationException("Total contributions must not exceed the payment amount");
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
        Payment payment = getPaymentById(paymentId);
        Contribution existing = getContribution(paymentId, contribution.id());
        requireMemberForPayment(payment, contribution.memberId());
        BigDecimal prospective = sumContributionsForPayment(paymentId)
                .subtract(existing.amountReceived())
                .add(contribution.amountReceived());
        if (prospective.compareTo(payment.amountPaid()) > 0) {
            throw new ValidationException("Total contributions must not exceed the payment amount");
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
        getContribution(paymentId, contributionId);
        JsonArray deleted = deleteArray(
                "contributions",
                "payment_id=eq." + paymentId + "&id=eq." + contributionId);
        if (deleted.isEmpty()) {
            throw new NotFoundException("Contribution not found: " + contributionId);
        }
        return true;
    }

    private BigDecimal sumContributionsForPayment(Long paymentId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Contribution contribution : parseContributions(
                getArray("contributions", "select=*&payment_id=eq." + paymentId))) {
            total = total.add(contribution.amountReceived());
        }
        return total;
    }

    private void requireMemberForPayment(Payment payment, Long memberId) {
        requireId(memberId, "memberId");
        JsonArray rows = getArray(
                "members",
                "select=*&subscription_id=eq." + payment.subscriptionId() + "&id=eq." + memberId);
        if (rows.isEmpty()) {
            throw new ValidationException("Contribution member must belong to the payment subscription");
        }
    }

    // =====================================================================
    // Usage Logs
    // =====================================================================

    public List<UsageLog> findAllUsageLogs() {
        JsonArray rows = getArray("usage_logs", "select=*&order=usage_date.desc,id.desc");
        return parseUsageLogs(rows);
    }

    public List<UsageLog> findUsageLogsBySubscriptionId(Long subscriptionId) {
        getSubscriptionById(subscriptionId);
        JsonArray rows = getArray(
                "usage_logs",
                "select=*&subscription_id=eq." + subscriptionId + "&order=usage_date.desc,id.desc");
        return parseUsageLogs(rows);
    }

    public UsageLog addUsageLog(Long subscriptionId, UsageLog usageLog) {
        validateUsageLog(subscriptionId, usageLog);
        if (usageLog.id() != null) {
            throw new ValidationException("A new usage log must not have an id");
        }
        getSubscriptionById(subscriptionId);
        JsonObject payload = usageLogPayload(usageLog, true);
        JsonArray rows = postArray("usage_logs", null, payload, "return=representation");
        return parseUsageLog(requireSingleRow(rows, "Failed to add usage log"));
    }

    public UsageLog updateUsageLog(Long subscriptionId, UsageLog usageLog) {
        validateUsageLog(subscriptionId, usageLog);
        if (usageLog.id() == null || usageLog.id() <= 0) {
            throw new ValidationException("An updated usage log must have a positive id");
        }
        getSubscriptionById(subscriptionId);
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
    // Price History
    // =====================================================================

    public List<PriceHistory> findAllPriceHistory() {
        JsonArray rows = getArray("price_history", "select=*&order=effective_date.desc,id.desc");
        return parsePriceHistoryList(rows);
    }

    public List<PriceHistory> findPriceHistoryBySubscriptionId(Long subscriptionId) {
        getSubscriptionById(subscriptionId);
        JsonArray rows = getArray(
                "price_history",
                "select=*&subscription_id=eq." + subscriptionId + "&order=effective_date.desc,id.desc");
        return parsePriceHistoryList(rows);
    }

    public PriceHistory recordPriceChange(
            Long subscriptionId,
            BigDecimal newPrice,
            LocalDate effectiveDate,
            String reason) {
        requireId(subscriptionId, "subscriptionId");
        if (newPrice == null || newPrice.signum() <= 0) {
            throw new ValidationException("New price must be greater than zero");
        }
        if (effectiveDate == null) {
            throw new ValidationException("Price effective date is required");
        }
        Subscription subscription = getSubscriptionById(subscriptionId);
        if (newPrice.compareTo(subscription.currentPrice()) == 0) {
            throw new ValidationException("New price must differ from the current price");
        }

        JsonObject historyPayload = new JsonObject();
        historyPayload.addProperty("user_id", authService.getUserId());
        historyPayload.addProperty("subscription_id", subscriptionId);
        historyPayload.addProperty("previous_price", subscription.currentPrice());
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
        // Auto-initialize default preferences for newly registered users
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
    // Reports & Dashboard Aggregations
    // =====================================================================

    public ReportService.ActualPaidReport actualPaidForMonth(YearMonth month) {
        if (month == null) {
            throw new ValidationException("Report month is required");
        }
        return actualPaidForRange(month.atDay(1), month.atEndOfMonth());
    }

    public ReportService.ActualPaidReport actualPaidForYear(Year year) {
        if (year == null) {
            throw new ValidationException("Report year is required");
        }
        return actualPaidForRange(year.atDay(1), year.atMonth(12).atEndOfMonth());
    }

    public BigDecimal allTimeActualPaid() {
        return findAllPayments().stream()
                .map(Payment::amountPaid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public ReportService.ActualPaidReport actualPaidForRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new ValidationException("Report start and end dates are required");
        }
        if (endDate.isBefore(startDate)) {
            throw new ValidationException("Report end date must not be before start date");
        }
        Map<Long, Subscription> subscriptions = new HashMap<>();
        for (Subscription subscription : findAllSubscriptions()) {
            subscriptions.put(subscription.id(), subscription);
        }

        BigDecimal totalPaid = BigDecimal.ZERO;
        int paymentCount = 0;
        Map<String, CategoryPaidTotal> categoryTotals = new HashMap<>();
        Map<Long, SubscriptionTotal> subscriptionTotals = new HashMap<>();
        for (Payment payment : findPaymentsByDateRange(startDate, endDate)) {
            Subscription subscription = subscriptions.get(payment.subscriptionId());
            if (subscription == null) {
                throw new NotFoundException("Subscription not found for payment: " + payment.subscriptionId());
            }
            BigDecimal amount = payment.amountPaid();
            totalPaid = totalPaid.add(amount);
            paymentCount++;
            categoryTotals.compute(subscription.category(), (category, current) -> current == null
                    ? new CategoryPaidTotal(amount, 1)
                    : new CategoryPaidTotal(current.amountPaid().add(amount), current.paymentCount() + 1));
            subscriptionTotals.compute(payment.subscriptionId(), (id, current) -> current == null
                    ? new SubscriptionTotal(id, subscription.name(), amount, 1)
                    : new SubscriptionTotal(id, current.name(), current.amountPaid().add(amount), current.paymentCount() + 1));
        }

        List<ReportService.CategoryBreakdown> byCategory = new ArrayList<>();
        categoryTotals.forEach((category, total) ->
                byCategory.add(new ReportService.CategoryBreakdown(category, total.amountPaid(), total.paymentCount())));
        byCategory.sort(Comparator.comparing(ReportService.CategoryBreakdown::category, String.CASE_INSENSITIVE_ORDER));

        List<ReportService.SubscriptionBreakdown> bySubscription = new ArrayList<>();
        subscriptionTotals.forEach((id, value) ->
                bySubscription.add(new ReportService.SubscriptionBreakdown(
                        id, value.name(), value.amountPaid(), value.paymentCount())));
        bySubscription.sort(Comparator.comparing(
                        ReportService.SubscriptionBreakdown::subscriptionName,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ReportService.SubscriptionBreakdown::subscriptionId));

        return new ReportService.ActualPaidReport(
                startDate, endDate, totalPaid, paymentCount, byCategory, bySubscription);
    }

    public ReportService.EstimatedRecurringReport currentEstimatedRecurringCost() {
        return computeEstimatedRecurringCost(findActiveSubscriptions());
    }

    public DashboardService.DashboardSummary getDashboardSummary() {
        LocalDate today = LocalDate.now(clock);
        List<Subscription> activeSubscriptions = findActiveSubscriptions();
        AppPreferences preferences = getPreferences();
        ReportService.EstimatedRecurringReport recurringCosts = computeEstimatedRecurringCost(activeSubscriptions);

        YearMonth currentMonth = YearMonth.from(today);
        List<Payment> allPayments = findAllPayments();
        LocalDate monthStart = currentMonth.atDay(1);
        LocalDate monthEnd = currentMonth.atEndOfMonth();
        BigDecimal currentMonthActualPaid = allPayments.stream()
                .filter(p -> !p.paymentDate().isBefore(monthStart) && !p.paymentDate().isAfter(monthEnd))
                .map(Payment::amountPaid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Payment> recentPayments = allPayments.stream()
                .limit(RECENT_PAYMENT_LIMIT)
                .toList();

        List<DashboardService.RenewalReminder> upcomingRenewals = new ArrayList<>();
        List<DashboardService.RenewalReminder> overdueRenewals = new ArrayList<>();
        for (Subscription subscription : activeSubscriptions) {
            LocalDate renewalDate = subscription.nextRenewalDate();
            long daysUntilRenewal = ChronoUnit.DAYS.between(today, renewalDate);
            if (daysUntilRenewal < 0) {
                if (preferences.showOverdueReminders()) {
                    overdueRenewals.add(toReminder(subscription, daysUntilRenewal));
                }
            } else if (daysUntilRenewal <= preferences.reminderLeadDays()) {
                upcomingRenewals.add(toReminder(subscription, daysUntilRenewal));
            }
        }
        upcomingRenewals.sort(Comparator.comparing(DashboardService.RenewalReminder::nextRenewalDate)
                .thenComparing(DashboardService.RenewalReminder::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DashboardService.RenewalReminder::subscriptionId));
        overdueRenewals.sort(Comparator.comparing(DashboardService.RenewalReminder::nextRenewalDate).reversed()
                .thenComparing(DashboardService.RenewalReminder::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DashboardService.RenewalReminder::subscriptionId));

        return new DashboardService.DashboardSummary(
                activeSubscriptions.size(),
                recurringCosts.monthlyTotal(),
                recurringCosts.annualTotal(),
                currentMonthActualPaid,
                recentPayments,
                upcomingRenewals,
                overdueRenewals);
    }

    private static ReportService.EstimatedRecurringReport computeEstimatedRecurringCost(
            List<Subscription> activeSubscriptions) {
        BigDecimal monthlyTotal = BigDecimal.ZERO;
        BigDecimal annualTotal = BigDecimal.ZERO;
        Map<String, CategoryEstimateTotal> categoryTotals = new HashMap<>();
        List<ReportService.SubscriptionEstimate> subscriptionEstimates = new ArrayList<>();

        for (Subscription subscription : activeSubscriptions) {
            BigDecimal monthly = subscription.billingCycle() == BillingCycle.MONTHLY
                    ? subscription.currentPrice().setScale(2, RoundingMode.HALF_UP)
                    : subscription.currentPrice().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            BigDecimal annual = subscription.billingCycle() == BillingCycle.MONTHLY
                    ? subscription.currentPrice().multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP)
                    : subscription.currentPrice().setScale(2, RoundingMode.HALF_UP);
            monthlyTotal = monthlyTotal.add(monthly);
            annualTotal = annualTotal.add(annual);
            categoryTotals.compute(subscription.category(), (category, current) -> current == null
                    ? new CategoryEstimateTotal(1, monthly, annual)
                    : new CategoryEstimateTotal(
                            current.subscriptionCount() + 1,
                            current.monthlyEquivalent().add(monthly),
                            current.annualEquivalent().add(annual)));
            subscriptionEstimates.add(new ReportService.SubscriptionEstimate(
                    subscription.id(),
                    subscription.name(),
                    subscription.category(),
                    subscription.billingCycle(),
                    subscription.currentPrice(),
                    monthly,
                    annual));
        }

        List<ReportService.CategoryEstimate> byCategory = new ArrayList<>();
        categoryTotals.forEach((category, total) ->
                byCategory.add(new ReportService.CategoryEstimate(
                        category,
                        total.subscriptionCount(),
                        total.monthlyEquivalent(),
                        total.annualEquivalent())));
        byCategory.sort(Comparator.comparing(ReportService.CategoryEstimate::category, String.CASE_INSENSITIVE_ORDER));
        subscriptionEstimates.sort(Comparator.comparing(
                        ReportService.SubscriptionEstimate::name,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ReportService.SubscriptionEstimate::subscriptionId));

        return new ReportService.EstimatedRecurringReport(
                activeSubscriptions.size(),
                monthlyTotal,
                annualTotal,
                byCategory,
                subscriptionEstimates);
    }

    private static DashboardService.RenewalReminder toReminder(Subscription subscription, long daysUntilRenewal) {
        return new DashboardService.RenewalReminder(
                subscription.id(),
                subscription.name(),
                subscription.category(),
                subscription.nextRenewalDate(),
                subscription.currentPrice(),
                subscription.billingCycle(),
                daysUntilRenewal);
    }

    private record CategoryPaidTotal(BigDecimal amountPaid, int paymentCount) {}
    private record SubscriptionTotal(Long id, String name, BigDecimal amountPaid, int paymentCount) {}
    private record CategoryEstimateTotal(int subscriptionCount, BigDecimal monthlyEquivalent, BigDecimal annualEquivalent) {}

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

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String sanitizePostgrestPattern(String input) {
        return input.replace(",", " ").replace("(", " ").replace(")", " ").replace("*", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
