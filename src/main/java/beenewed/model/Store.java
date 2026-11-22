package beenewed.model;

import beenewed.model.Models.*;
import beenewed.ui.Icons;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.services.DashboardService;
import com.beenewed.backend.services.ReportService;
import com.beenewed.supabase.SupabaseException;
import com.beenewed.supabase.SupabaseRestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
 * UI-facing model adapter with an in-memory cache backed by Supabase PostgREST.
 * All UI reads (tabs, filters, details, reminders, reports, dashboard stats) execute
 * purely in memory; Supabase is contacted once on login, on manual refresh, and on
 * create/update/delete mutations.
 */
public final class Store {
    private static final int RECENT_PAYMENT_LIMIT = 5;
    private static Store instance;

    public static synchronized Store initialize(SupabaseRestClient supabase) {
        instance = new Store(supabase);
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    public static Store get() {
        if (instance == null) {
            throw new IllegalStateException("Store must be initialized with SupabaseRestClient before use");
        }
        return instance;
    }

    public static LocalDate today() {
        return LocalDate.now();
    }

    public static long daysUntil(LocalDate date) {
        return ChronoUnit.DAYS.between(today(), date);
    }

    public final List<Subscription> subscriptions = new ArrayList<>();
    public final List<Payment> payments = new ArrayList<>();
    public final List<Member> members = new ArrayList<>();
    public final List<Contribution> contributions = new ArrayList<>();
    public final List<Usage> usages = new ArrayList<>();
    public final List<PriceChange> priceChanges = new ArrayList<>();

    public int remindDays = 7;
    public boolean showOverdue = true;

    private String currency = "PHP";
    private com.beenewed.backend.model.Theme theme = com.beenewed.backend.model.Theme.LIGHT;

    private final Object cacheLock = new Object();
    private final SupabaseRestClient supabase;
    private final List<Runnable> listeners = new ArrayList<>();

    private Store(SupabaseRestClient supabase) {
        this.supabase = Objects.requireNonNull(supabase, "supabase must not be null");
        reload();
    }

    public void onChange(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    public void changed() {
        listeners.forEach(Runnable::run);
    }

    /** Re-fetches all user tables from Supabase in parallel and updates the in-memory cache. */
    public void refresh() {
        withBackend(() -> {
            reload();
            return null;
        });
    }

    // =====================================================================
    // Mutations (hit Supabase once, then update the in-memory cache directly)
    // =====================================================================

    public void addSubscription(Subscription subscription) {
        withBackend(() -> {
            com.beenewed.backend.model.Subscription created =
                    supabase.createSubscription(toBackendSubscription(subscription, null));
            synchronized (cacheLock) {
                Subscription mapped = mapSubscription(created, indexSubscriptions());
                subscriptions.add(mapped);
                sortSubscriptions();
            }
            return null;
        });
    }

    public void updateSubscription(Subscription subscription) {
        withBackend(() -> {
            synchronized (cacheLock) {
                Subscription existing = findCachedSubscription(subscription.id);
                if (existing == null) {
                    throw new NotFoundException("Subscription not found: " + subscription.id);
                }
                if (existing.status != subscription.status) {
                    throw new ValidationException("Use a status transition method to change subscription status");
                }
            }
            com.beenewed.backend.model.Subscription updated =
                    supabase.updateSubscription(toBackendSubscription(subscription, subscription.id));
            synchronized (cacheLock) {
                Subscription cached = findCachedSubscription(subscription.id);
                if (cached != null) {
                    applySubscription(cached, updated);
                } else {
                    subscriptions.add(mapSubscription(updated, indexSubscriptions()));
                }
                sortSubscriptions();
            }
            return null;
        });
    }

    public void deleteSubscription(Subscription subscription) {
        withBackend(() -> {
            supabase.deleteSubscription((long) subscription.id);
            synchronized (cacheLock) {
                subscriptions.removeIf(s -> s.id == subscription.id);
                members.removeIf(m -> m.sub.id == subscription.id);
                usages.removeIf(u -> u.sub.id == subscription.id);
                priceChanges.removeIf(pc -> pc.sub.id == subscription.id);
            }
            return null;
        });
    }

    public void changeStatus(Subscription subscription, Status targetStatus) {
        Objects.requireNonNull(targetStatus, "status must not be null");
        withBackend(() -> {
            LocalDate renewalDate;
            synchronized (cacheLock) {
                Subscription existing = findCachedSubscription(subscription.id);
                if (existing == null) {
                    throw new NotFoundException("Subscription not found: " + subscription.id);
                }
                if (targetStatus == Status.PAUSED && existing.status != Status.ACTIVE) {
                    throw new ValidationException("Only active subscriptions can be paused");
                }
                if (targetStatus == Status.CANCELLED && existing.status == Status.CANCELLED) {
                    throw new ValidationException("Subscription is already cancelled");
                }
                if (targetStatus == Status.ACTIVE && existing.status == Status.ACTIVE) {
                    throw new ValidationException("Subscription is already active");
                }
                renewalDate = existing.nextRenewal;
            }
            com.beenewed.backend.model.Subscription updated = supabase.updateSubscriptionStatus(
                    (long) subscription.id,
                    com.beenewed.backend.model.SubscriptionStatus.valueOf(targetStatus.name()),
                    renewalDate);
            synchronized (cacheLock) {
                Subscription cached = findCachedSubscription(subscription.id);
                if (cached != null) {
                    applySubscription(cached, updated);
                }
            }
            return null;
        });
    }

    public void recordPayment(Payment payment) {
        withBackend(() -> {
            com.beenewed.backend.model.Payment created =
                    supabase.recordPayment(toBackendPayment(payment, null));
            synchronized (cacheLock) {
                Subscription sub = requireMapped(
                        findCachedSubscription(Math.toIntExact(created.subscriptionId())),
                        "Subscription for payment " + created.id());
                Payment mapped = new Payment(
                        Math.toIntExact(created.id()),
                        sub,
                        created.paymentDate(),
                        created.amountPaid().doubleValue(),
                        created.renewalPeriod(),
                        created.paymentMethod(),
                        created.notes());
                payments.add(mapped);
                sortPayments();
            }
            return null;
        });
    }

    public void updatePayment(Payment payment) {
        withBackend(() -> {
            BigDecimal newAmount = money(payment.amount);
            synchronized (cacheLock) {
                Payment existing = findCachedPayment(payment.id);
                if (existing == null) {
                    throw new NotFoundException("Payment not found: " + payment.id);
                }
                BigDecimal contributionTotal = sumContributionsForPayment(payment.id);
                if (existing.sub.id != payment.sub.id && contributionTotal.signum() > 0) {
                    throw new ValidationException("A payment with contributions cannot be moved to another subscription");
                }
                if (contributionTotal.compareTo(newAmount) > 0) {
                    throw new ValidationException("Payment amount cannot be less than its total contributions");
                }
            }
            com.beenewed.backend.model.Payment updated =
                    supabase.updatePayment(toBackendPayment(payment, payment.id));
            synchronized (cacheLock) {
                Subscription sub = requireMapped(
                        findCachedSubscription(Math.toIntExact(updated.subscriptionId())),
                        "Subscription for payment " + updated.id());
                Payment cached = findCachedPayment(payment.id);
                if (cached != null) {
                    cached.sub = sub;
                    cached.date = updated.paymentDate();
                    cached.amount = updated.amountPaid().doubleValue();
                    cached.period = updated.renewalPeriod();
                    cached.method = updated.paymentMethod();
                    cached.notes = updated.notes();
                }
                sortPayments();
            }
            return null;
        });
    }

    public void deletePayment(Payment payment) {
        withBackend(() -> {
            supabase.deletePayment((long) payment.id);
            synchronized (cacheLock) {
                payments.removeIf(p -> p.id == payment.id);
            }
            return null;
        });
    }

    public void addMember(Member member) {
        withBackend(() -> {
            com.beenewed.backend.model.Member created =
                    supabase.addMember((long) member.sub.id, toBackendMember(member, null));
            synchronized (cacheLock) {
                Subscription sub = requireMapped(
                        findCachedSubscription(Math.toIntExact(created.subscriptionId())),
                        "Subscription for member " + created.id());
                members.add(new Member(
                        Math.toIntExact(created.id()),
                        sub,
                        created.name(),
                        created.roleNotes()));
                sortMembers();
            }
            return null;
        });
    }

    public void updateMember(Member member) {
        withBackend(() -> {
            com.beenewed.backend.model.Member updated =
                    supabase.updateMember((long) member.sub.id, toBackendMember(member, member.id));
            synchronized (cacheLock) {
                Member cached = findCachedMember(member.id);
                if (cached != null) {
                    cached.name = updated.name();
                    cached.notes = updated.roleNotes();
                }
                sortMembers();
            }
            return null;
        });
    }

    public void deleteMember(Member member) {
        withBackend(() -> {
            supabase.deleteMember((long) member.sub.id, (long) member.id);
            synchronized (cacheLock) {
                members.removeIf(m -> m.id == member.id);
            }
            return null;
        });
    }

    public void recordContribution(Contribution contribution) {
        withBackend(() -> {
            BigDecimal amount = money(contribution.amount);
            synchronized (cacheLock) {
                Payment payment = findCachedPayment(contribution.payment.id);
                if (payment == null) {
                    throw new NotFoundException("Payment not found: " + contribution.payment.id);
                }
                Member member = findCachedMember(contribution.member.id);
                if (member == null || member.sub.id != payment.sub.id) {
                    throw new ValidationException("Contribution member must belong to the payment subscription");
                }
                BigDecimal prospective = sumContributionsForPayment(payment.id).add(amount);
                if (prospective.compareTo(money(payment.amount)) > 0) {
                    throw new ValidationException("Total contributions must not exceed the payment amount");
                }
            }
            com.beenewed.backend.model.Contribution created =
                    supabase.recordContribution((long) contribution.payment.id, toBackendContribution(contribution, null));
            synchronized (cacheLock) {
                Payment payment = requireMapped(
                        findCachedPayment(Math.toIntExact(created.paymentId())),
                        "Payment for contribution " + created.id());
                Member member = requireMapped(
                        findCachedMember(Math.toIntExact(created.memberId())),
                        "Member for contribution " + created.id());
                contributions.add(new Contribution(
                        Math.toIntExact(created.id()),
                        payment.sub,
                        member,
                        payment,
                        created.amountReceived().doubleValue(),
                        created.dateReceived()));
                sortContributions();
            }
            return null;
        });
    }

    public void updateContribution(Contribution contribution) {
        withBackend(() -> {
            BigDecimal amount = money(contribution.amount);
            synchronized (cacheLock) {
                Contribution existing = findCachedContribution(contribution.id);
                if (existing == null) {
                    throw new NotFoundException("Contribution not found: " + contribution.id);
                }
                Payment payment = findCachedPayment(contribution.payment.id);
                if (payment == null) {
                    throw new NotFoundException("Payment not found: " + contribution.payment.id);
                }
                Member member = findCachedMember(contribution.member.id);
                if (member == null || member.sub.id != payment.sub.id) {
                    throw new ValidationException("Contribution member must belong to the payment subscription");
                }
                BigDecimal prospective = sumContributionsForPayment(payment.id)
                        .subtract(money(existing.amount))
                        .add(amount);
                if (prospective.compareTo(money(payment.amount)) > 0) {
                    throw new ValidationException("Total contributions must not exceed the payment amount");
                }
            }
            com.beenewed.backend.model.Contribution updated =
                    supabase.updateContribution((long) contribution.payment.id, toBackendContribution(contribution, contribution.id));
            synchronized (cacheLock) {
                Payment payment = requireMapped(
                        findCachedPayment(Math.toIntExact(updated.paymentId())),
                        "Payment for contribution " + updated.id());
                Member member = requireMapped(
                        findCachedMember(Math.toIntExact(updated.memberId())),
                        "Member for contribution " + updated.id());
                Contribution cached = findCachedContribution(contribution.id);
                if (cached != null) {
                    cached.sub = payment.sub;
                    cached.member = member;
                    cached.payment = payment;
                    cached.amount = updated.amountReceived().doubleValue();
                    cached.date = updated.dateReceived();
                }
                sortContributions();
            }
            return null;
        });
    }

    public void deleteContribution(Contribution contribution) {
        withBackend(() -> {
            supabase.deleteContribution((long) contribution.payment.id, (long) contribution.id);
            synchronized (cacheLock) {
                contributions.removeIf(c -> c.id == contribution.id);
            }
            return null;
        });
    }

    public void addUsage(Usage usage) {
        withBackend(() -> {
            com.beenewed.backend.model.UsageLog created =
                    supabase.addUsageLog((long) usage.sub.id, toBackendUsage(usage, null));
            synchronized (cacheLock) {
                Subscription sub = requireMapped(
                        findCachedSubscription(Math.toIntExact(created.subscriptionId())),
                        "Subscription for usage log " + created.id());
                usages.add(new Usage(
                        Math.toIntExact(created.id()),
                        sub,
                        created.usageDate(),
                        created.quantity(),
                        created.unit(),
                        created.notes()));
                sortUsages();
            }
            return null;
        });
    }

    public void updateUsage(Usage usage) {
        withBackend(() -> {
            com.beenewed.backend.model.UsageLog updated =
                    supabase.updateUsageLog((long) usage.sub.id, toBackendUsage(usage, usage.id));
            synchronized (cacheLock) {
                Usage cached = findCachedUsage(usage.id);
                if (cached != null) {
                    cached.date = updated.usageDate();
                    cached.quantity = updated.quantity();
                    cached.unit = updated.unit();
                    cached.notes = updated.notes();
                }
                sortUsages();
            }
            return null;
        });
    }

    public void deleteUsage(Usage usage) {
        withBackend(() -> {
            supabase.deleteUsageLog((long) usage.sub.id, (long) usage.id);
            synchronized (cacheLock) {
                usages.removeIf(u -> u.id == usage.id);
            }
            return null;
        });
    }

    public void recordPriceChange(Subscription subscription, LocalDate date, double newPrice, String reason) {
        withBackend(() -> {
            BigDecimal prevPrice;
            synchronized (cacheLock) {
                Subscription existing = findCachedSubscription(subscription.id);
                if (existing == null) {
                    throw new NotFoundException("Subscription not found: " + subscription.id);
                }
                prevPrice = money(existing.price);
            }
            BigDecimal updatedPrice = money(newPrice);
            com.beenewed.backend.model.PriceHistory created = supabase.recordPriceChange(
                    (long) subscription.id,
                    prevPrice,
                    updatedPrice,
                    date,
                    reason);
            synchronized (cacheLock) {
                Subscription cached = requireMapped(
                        findCachedSubscription(subscription.id),
                        "Subscription for price history " + created.id());
                cached.price = created.newPrice().doubleValue();
                priceChanges.add(new PriceChange(
                        Math.toIntExact(created.id()),
                        cached,
                        created.effectiveDate(),
                        created.previousPrice().doubleValue(),
                        created.newPrice().doubleValue(),
                        created.reason()));
                sortPriceChanges();
            }
            return null;
        });
    }

    public void savePrefs(int days, boolean overdue) {
        withBackend(() -> {
            String currentCurrency;
            com.beenewed.backend.model.Theme currentTheme;
            synchronized (cacheLock) {
                currentCurrency = currency;
                currentTheme = theme;
            }
            com.beenewed.backend.model.AppPreferences saved = supabase.updatePreferences(
                    new com.beenewed.backend.model.AppPreferences(
                            1L,
                            currentCurrency,
                            currentTheme,
                            days,
                            overdue));
            synchronized (cacheLock) {
                currency = saved.currency();
                theme = saved.theme();
                remindDays = saved.reminderLeadDays();
                showOverdue = saved.showOverdueReminders();
            }
            return null;
        });
    }

    public com.beenewed.backend.model.Theme getTheme() {
        synchronized (cacheLock) {
            return theme;
        }
    }

    public void saveTheme(boolean dark) {
        withBackend(() -> {
            com.beenewed.backend.model.Theme nextTheme = dark
                    ? com.beenewed.backend.model.Theme.DARK
                    : com.beenewed.backend.model.Theme.LIGHT;
            String currentCurrency;
            int currentLeadDays;
            boolean currentOverdue;
            synchronized (cacheLock) {
                currentCurrency = currency;
                currentLeadDays = remindDays;
                currentOverdue = showOverdue;
            }
            com.beenewed.backend.model.AppPreferences saved = supabase.updatePreferences(
                    new com.beenewed.backend.model.AppPreferences(
                            1L,
                            currentCurrency,
                            nextTheme,
                            currentLeadDays,
                            currentOverdue));
            synchronized (cacheLock) {
                currency = saved.currency();
                theme = saved.theme();
                remindDays = saved.reminderLeadDays();
                showOverdue = saved.showOverdueReminders();
            }
            return null;
        });
    }

    // =====================================================================
    // In-Memory Reads (0 HTTP requests — instant UI response)
    // =====================================================================

    public List<Subscription> findSubscriptions(String search, String category, Status status) {
        String normalizedCategory = category == null || category.startsWith("All") ? null : category;
        String needle = normalize(search);
        String lowerNeedle = needle == null ? null : needle.toLowerCase(Locale.ROOT);
        synchronized (cacheLock) {
            List<Subscription> result = new ArrayList<>();
            for (Subscription s : subscriptions) {
                if (status != null && s.status != status) {
                    continue;
                }
                if (normalizedCategory != null && !normalizedCategory.equals(s.category)) {
                    continue;
                }
                if (lowerNeedle != null
                        && !s.name.toLowerCase(Locale.ROOT).contains(lowerNeedle)
                        && !s.category.toLowerCase(Locale.ROOT).contains(lowerNeedle)) {
                    continue;
                }
                result.add(s);
            }
            result.sort(Comparator.comparing(s -> s.name.toLowerCase(Locale.ROOT)));
            return result;
        }
    }

    public List<Subscription> active() {
        return findSubscriptions(null, null, Status.ACTIVE);
    }

    public List<Subscription> findRenewals(LocalDate startDate, LocalDate endDate, Status status) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new ValidationException("Renewal end date must not be before start date");
        }
        synchronized (cacheLock) {
            List<Subscription> result = new ArrayList<>();
            for (Subscription s : subscriptions) {
                if (status != null && s.status != status) {
                    continue;
                }
                if (startDate != null && s.nextRenewal.isBefore(startDate)) {
                    continue;
                }
                if (endDate != null && s.nextRenewal.isAfter(endDate)) {
                    continue;
                }
                result.add(s);
            }
            result.sort(Comparator.comparing((Subscription s) -> s.nextRenewal)
                    .thenComparing(s -> s.name, String.CASE_INSENSITIVE_ORDER));
            return result;
        }
    }

    public List<Subscription> byRenewal(List<Subscription> list) {
        List<Subscription> ordered = new ArrayList<>(list);
        ordered.sort(Comparator.comparing(subscription -> subscription.nextRenewal));
        return ordered;
    }

    public List<Payment> findPayments(
            LocalDate startDate,
            LocalDate endDate,
            Status subscriptionStatus,
            String search) {
        String needle = normalize(search);
        String lowerNeedle = needle == null ? null : needle.toLowerCase(Locale.ROOT);
        synchronized (cacheLock) {
            List<Payment> result = new ArrayList<>();
            for (Payment p : payments) {
                if (startDate != null && p.date.isBefore(startDate)) {
                    continue;
                }
                if (endDate != null && p.date.isAfter(endDate)) {
                    continue;
                }
                if (subscriptionStatus != null && p.sub.status != subscriptionStatus) {
                    continue;
                }
                if (lowerNeedle != null) {
                    boolean matches = containsIgnoreCase(p.sub.name, lowerNeedle)
                            || containsIgnoreCase(p.sub.category, lowerNeedle)
                            || containsIgnoreCase(p.period, lowerNeedle)
                            || containsIgnoreCase(p.method, lowerNeedle)
                            || containsIgnoreCase(p.notes, lowerNeedle);
                    if (!matches) {
                        continue;
                    }
                }
                result.add(p);
            }
            result.sort(PAYMENT_NEWEST_FIRST);
            return result;
        }
    }

    public List<Payment> findPayments(LocalDate startDate, LocalDate endDate) {
        return findPayments(startDate, endDate, null, null);
    }

    public List<Payment> paymentsNewestFirst() {
        synchronized (cacheLock) {
            List<Payment> result = new ArrayList<>(payments);
            result.sort(PAYMENT_NEWEST_FIRST);
            return result;
        }
    }

    public List<Payment> paymentsFor(Subscription subscription) {
        synchronized (cacheLock) {
            List<Payment> result = new ArrayList<>();
            for (Payment p : payments) {
                if (p.sub.id == subscription.id) {
                    result.add(p);
                }
            }
            result.sort(PAYMENT_NEWEST_FIRST);
            return result;
        }
    }

    public List<Member> membersFor(Subscription subscription) {
        synchronized (cacheLock) {
            List<Member> result = new ArrayList<>();
            for (Member m : members) {
                if (m.sub.id == subscription.id) {
                    result.add(m);
                }
            }
            result.sort(Comparator.comparing((Member m) -> m.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(m -> m.id));
            return result;
        }
    }

    public List<Contribution> contributionsFor(Subscription subscription) {
        synchronized (cacheLock) {
            List<Contribution> result = new ArrayList<>();
            for (Contribution c : contributions) {
                if (c.sub.id == subscription.id) {
                    result.add(c);
                }
            }
            result.sort(Comparator.comparing(c -> c.date));
            return result;
        }
    }

    public List<Usage> usagesFor(Subscription subscription) {
        synchronized (cacheLock) {
            List<Usage> result = new ArrayList<>();
            for (Usage u : usages) {
                if (u.sub.id == subscription.id) {
                    result.add(u);
                }
            }
            result.sort(Comparator.comparing((Usage u) -> u.date).reversed()
                    .thenComparing(Comparator.comparingInt((Usage u) -> u.id).reversed()));
            return result;
        }
    }

    public List<PriceChange> priceChangesFor(Subscription subscription) {
        synchronized (cacheLock) {
            List<PriceChange> result = new ArrayList<>();
            for (PriceChange pc : priceChanges) {
                if (pc.sub.id == subscription.id) {
                    result.add(pc);
                }
            }
            result.sort(Comparator.comparing((PriceChange pc) -> pc.date).reversed()
                    .thenComparing(Comparator.comparingInt((PriceChange pc) -> pc.id).reversed()));
            return result;
        }
    }

    public double monthlyTotal() {
        return estimatedRecurringCost().monthlyTotal().doubleValue();
    }

    public double annualTotal() {
        return estimatedRecurringCost().annualTotal().doubleValue();
    }

    public DashboardService.DashboardSummary dashboardSummary() {
        LocalDate today = today();
        YearMonth currentMonth = YearMonth.from(today);
        LocalDate monthStart = currentMonth.atDay(1);
        LocalDate monthEnd = currentMonth.atEndOfMonth();

        synchronized (cacheLock) {
            ReportService.EstimatedRecurringReport recurring = computeEstimatedRecurringLocked();

            BigDecimal currentMonthActualPaid = BigDecimal.ZERO;
            List<Payment> sortedPayments = new ArrayList<>(payments);
            sortedPayments.sort(PAYMENT_NEWEST_FIRST);
            for (Payment p : sortedPayments) {
                if (!p.date.isBefore(monthStart) && !p.date.isAfter(monthEnd)) {
                    currentMonthActualPaid = currentMonthActualPaid.add(money(p.amount));
                }
            }

            List<com.beenewed.backend.model.Payment> recentPayments = sortedPayments.stream()
                    .limit(RECENT_PAYMENT_LIMIT)
                    .map(p -> toBackendPayment(p, p.id))
                    .toList();

            List<DashboardService.RenewalReminder> upcomingRenewals = new ArrayList<>();
            List<DashboardService.RenewalReminder> overdueRenewals = new ArrayList<>();
            int activeCount = 0;
            for (Subscription s : subscriptions) {
                if (s.status != Status.ACTIVE) {
                    continue;
                }
                activeCount++;
                long daysUntilRenewal = ChronoUnit.DAYS.between(today, s.nextRenewal);
                if (daysUntilRenewal < 0) {
                    if (showOverdue) {
                        overdueRenewals.add(toReminder(s, daysUntilRenewal));
                    }
                } else if (daysUntilRenewal <= remindDays) {
                    upcomingRenewals.add(toReminder(s, daysUntilRenewal));
                }
            }
            upcomingRenewals.sort(Comparator.comparing(DashboardService.RenewalReminder::nextRenewalDate)
                    .thenComparing(DashboardService.RenewalReminder::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(DashboardService.RenewalReminder::subscriptionId));
            overdueRenewals.sort(Comparator.comparing(DashboardService.RenewalReminder::nextRenewalDate).reversed()
                    .thenComparing(DashboardService.RenewalReminder::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(DashboardService.RenewalReminder::subscriptionId));

            return new DashboardService.DashboardSummary(
                    activeCount,
                    recurring.monthlyTotal(),
                    recurring.annualTotal(),
                    currentMonthActualPaid,
                    recentPayments,
                    upcomingRenewals,
                    overdueRenewals);
        }
    }

    public List<Subscription> upcomingReminders() {
        return upcomingReminders(dashboardSummary());
    }

    public List<Subscription> upcomingReminders(DashboardService.DashboardSummary summary) {
        return reminders(summary.upcomingRenewals());
    }

    public List<Subscription> overdue() {
        return reminders(dashboardSummary().overdueRenewals());
    }

    public List<Subscription> overdue(DashboardService.DashboardSummary summary) {
        return reminders(summary.overdueRenewals());
    }

    public ReportService.ActualPaidReport actualPaidForRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new ValidationException("Report start and end dates are required");
        }
        if (endDate.isBefore(startDate)) {
            throw new ValidationException("Report end date must not be before start date");
        }
        synchronized (cacheLock) {
            BigDecimal totalPaid = BigDecimal.ZERO;
            int paymentCount = 0;
            Map<String, CategoryPaidTotal> categoryTotals = new HashMap<>();
            Map<Long, SubscriptionTotal> subscriptionTotals = new HashMap<>();
            for (Payment p : payments) {
                if (p.date.isBefore(startDate) || p.date.isAfter(endDate)) {
                    continue;
                }
                BigDecimal amount = money(p.amount);
                totalPaid = totalPaid.add(amount);
                paymentCount++;
                categoryTotals.compute(p.sub.category, (cat, cur) -> cur == null
                        ? new CategoryPaidTotal(amount, 1)
                        : new CategoryPaidTotal(cur.amountPaid().add(amount), cur.paymentCount() + 1));
                long subId = p.sub.id;
                subscriptionTotals.compute(subId, (id, cur) -> cur == null
                        ? new SubscriptionTotal(id, p.sub.name, amount, 1)
                        : new SubscriptionTotal(id, cur.name(), cur.amountPaid().add(amount), cur.paymentCount() + 1));
            }
            List<ReportService.CategoryBreakdown> byCategory = new ArrayList<>();
            categoryTotals.forEach((cat, total) ->
                    byCategory.add(new ReportService.CategoryBreakdown(cat, total.amountPaid(), total.paymentCount())));
            byCategory.sort(Comparator.comparing(ReportService.CategoryBreakdown::category, String.CASE_INSENSITIVE_ORDER));

            List<ReportService.SubscriptionBreakdown> bySubscription = new ArrayList<>();
            subscriptionTotals.forEach((id, val) ->
                    bySubscription.add(new ReportService.SubscriptionBreakdown(
                            id, val.name(), val.amountPaid(), val.paymentCount())));
            bySubscription.sort(Comparator.comparing(
                            ReportService.SubscriptionBreakdown::subscriptionName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ReportService.SubscriptionBreakdown::subscriptionId));

            return new ReportService.ActualPaidReport(
                    startDate, endDate, totalPaid, paymentCount, byCategory, bySubscription);
        }
    }

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

    public ReportService.EstimatedRecurringReport estimatedRecurringCost() {
        synchronized (cacheLock) {
            return computeEstimatedRecurringLocked();
        }
    }

    public BigDecimal allTimeActualPaid() {
        synchronized (cacheLock) {
            BigDecimal total = BigDecimal.ZERO;
            for (Payment p : payments) {
                total = total.add(money(p.amount));
            }
            return total;
        }
    }

    public Subscription getSubscription(int id) {
        synchronized (cacheLock) {
            Subscription sub = findCachedSubscription(id);
            if (sub == null) {
                throw new NotFoundException("Subscription not found: " + id);
            }
            return sub;
        }
    }

    public boolean containsSubscription(Subscription subscription) {
        synchronized (cacheLock) {
            return subscription != null && findCachedSubscription(subscription.id) != null;
        }
    }

    // =====================================================================
    // Snapshot Reload & Cache Assembly
    // =====================================================================

    private void reload() {
        // Run all 7 table GET requests in parallel outside the cache lock
        SupabaseRestClient.UserDataSnapshot snapshot = supabase.fetchAllUserData();

        synchronized (cacheLock) {
            Map<Integer, Subscription> oldSubscriptions = indexSubscriptions();
            List<Subscription> loadedSubscriptions = mapSubscriptions(snapshot.subscriptions(), oldSubscriptions);
            Map<Integer, Subscription> subscriptionsById = index(loadedSubscriptions, s -> s.id);

            Map<Integer, Payment> oldPayments = index(payments, p -> p.id);
            List<Payment> loadedPayments = mapPayments(snapshot.payments(), subscriptionsById, oldPayments);
            Map<Integer, Payment> paymentsById = index(loadedPayments, p -> p.id);

            Map<Integer, Member> oldMembers = index(members, m -> m.id);
            List<Member> loadedMembers = mapMembers(snapshot.members(), subscriptionsById, oldMembers);
            Map<Integer, Member> membersById = index(loadedMembers, m -> m.id);

            Map<Integer, Contribution> oldContributions = index(contributions, c -> c.id);
            List<Contribution> loadedContributions = mapContributions(
                    snapshot.contributions(), subscriptionsById, membersById, paymentsById, oldContributions);
            List<Usage> loadedUsages = mapUsages(
                    snapshot.usageLogs(), subscriptionsById, index(usages, u -> u.id));
            List<PriceChange> loadedPriceChanges = mapPriceChanges(
                    snapshot.priceHistory(), subscriptionsById, index(priceChanges, pc -> pc.id));

            replace(subscriptions, loadedSubscriptions);
            replace(payments, loadedPayments);
            replace(members, loadedMembers);
            replace(contributions, loadedContributions);
            replace(usages, loadedUsages);
            replace(priceChanges, loadedPriceChanges);

            sortSubscriptions();
            sortPayments();
            sortMembers();
            sortContributions();
            sortUsages();
            sortPriceChanges();

            com.beenewed.backend.model.AppPreferences prefs = snapshot.preferences();
            currency = prefs.currency();
            theme = prefs.theme();
            remindDays = prefs.reminderLeadDays();
            showOverdue = prefs.showOverdueReminders();
        }
    }

    private ReportService.EstimatedRecurringReport computeEstimatedRecurringLocked() {
        BigDecimal monthlyTotal = BigDecimal.ZERO;
        BigDecimal annualTotal = BigDecimal.ZERO;
        Map<String, CategoryEstimateTotal> categoryTotals = new HashMap<>();
        List<ReportService.SubscriptionEstimate> subscriptionEstimates = new ArrayList<>();
        int activeCount = 0;

        for (Subscription s : subscriptions) {
            if (s.status != Status.ACTIVE) {
                continue;
            }
            activeCount++;
            BigDecimal currentPrice = money(s.price);
            BigDecimal monthly = s.cycle == Cycle.MONTHLY
                    ? currentPrice.setScale(2, RoundingMode.HALF_UP)
                    : currentPrice.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            BigDecimal annual = s.cycle == Cycle.MONTHLY
                    ? currentPrice.multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP)
                    : currentPrice.setScale(2, RoundingMode.HALF_UP);
            monthlyTotal = monthlyTotal.add(monthly);
            annualTotal = annualTotal.add(annual);
            categoryTotals.compute(s.category, (cat, cur) -> cur == null
                    ? new CategoryEstimateTotal(1, monthly, annual)
                    : new CategoryEstimateTotal(
                            cur.subscriptionCount() + 1,
                            cur.monthlyEquivalent().add(monthly),
                            cur.annualEquivalent().add(annual)));
            subscriptionEstimates.add(new ReportService.SubscriptionEstimate(
                    (long) s.id,
                    s.name,
                    s.category,
                    com.beenewed.backend.model.BillingCycle.valueOf(s.cycle.name()),
                    currentPrice,
                    monthly,
                    annual));
        }

        List<ReportService.CategoryEstimate> byCategory = new ArrayList<>();
        categoryTotals.forEach((cat, total) ->
                byCategory.add(new ReportService.CategoryEstimate(
                        cat, total.subscriptionCount(), total.monthlyEquivalent(), total.annualEquivalent())));
        byCategory.sort(Comparator.comparing(ReportService.CategoryEstimate::category, String.CASE_INSENSITIVE_ORDER));
        subscriptionEstimates.sort(Comparator.comparing(
                        ReportService.SubscriptionEstimate::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ReportService.SubscriptionEstimate::subscriptionId));

        return new ReportService.EstimatedRecurringReport(
                activeCount, monthlyTotal, annualTotal, byCategory, subscriptionEstimates);
    }

    private List<Subscription> reminders(List<DashboardService.RenewalReminder> reminders) {
        synchronized (cacheLock) {
            Map<Integer, Subscription> cached = indexSubscriptions();
            List<Subscription> result = new ArrayList<>();
            for (DashboardService.RenewalReminder reminder : reminders) {
                Subscription sub = cached.get(Math.toIntExact(reminder.subscriptionId()));
                if (sub != null) {
                    result.add(sub);
                }
            }
            return result;
        }
    }

    private static DashboardService.RenewalReminder toReminder(Subscription s, long daysUntilRenewal) {
        return new DashboardService.RenewalReminder(
                (long) s.id,
                s.name,
                s.category,
                s.nextRenewal,
                money(s.price),
                com.beenewed.backend.model.BillingCycle.valueOf(s.cycle.name()),
                daysUntilRenewal);
    }

    private Subscription findCachedSubscription(int id) {
        for (Subscription s : subscriptions) {
            if (s.id == id) return s;
        }
        return null;
    }

    private Payment findCachedPayment(int id) {
        for (Payment p : payments) {
            if (p.id == id) return p;
        }
        return null;
    }

    private Member findCachedMember(int id) {
        for (Member m : members) {
            if (m.id == id) return m;
        }
        return null;
    }

    private Contribution findCachedContribution(int id) {
        for (Contribution c : contributions) {
            if (c.id == id) return c;
        }
        return null;
    }

    private Usage findCachedUsage(int id) {
        for (Usage u : usages) {
            if (u.id == id) return u;
        }
        return null;
    }

    private BigDecimal sumContributionsForPayment(int paymentId) {
        BigDecimal total = BigDecimal.ZERO;
        for (Contribution c : contributions) {
            if (c.payment.id == paymentId) {
                total = total.add(money(c.amount));
            }
        }
        return total;
    }

    private void sortSubscriptions() {
        subscriptions.sort(Comparator.comparing((Subscription s) -> s.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(s -> s.id));
    }

    private void sortPayments() {
        payments.sort(PAYMENT_NEWEST_FIRST);
    }

    private void sortMembers() {
        members.sort(Comparator.comparing((Member m) -> m.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(m -> m.id));
    }

    private void sortContributions() {
        contributions.sort(Comparator.comparing((Contribution c) -> c.date).reversed()
                .thenComparing(Comparator.comparingInt((Contribution c) -> c.id).reversed()));
    }

    private void sortUsages() {
        usages.sort(Comparator.comparing((Usage u) -> u.date).reversed()
                .thenComparing(Comparator.comparingInt((Usage u) -> u.id).reversed()));
    }

    private void sortPriceChanges() {
        priceChanges.sort(Comparator.comparing((PriceChange pc) -> pc.date).reversed()
                .thenComparing(Comparator.comparingInt((PriceChange pc) -> pc.id).reversed()));
    }

    private static final Comparator<Payment> PAYMENT_NEWEST_FIRST =
            Comparator.comparing((Payment p) -> p.date).reversed()
                    .thenComparing(Comparator.comparingInt((Payment p) -> p.id).reversed());

    // =====================================================================
    // Mapping Helpers
    // =====================================================================

    private List<Subscription> mapSubscriptions(
            List<com.beenewed.backend.model.Subscription> records,
            Map<Integer, Subscription> existing) {
        List<Subscription> result = new ArrayList<>(records.size());
        for (com.beenewed.backend.model.Subscription record : records) {
            result.add(mapSubscription(record, existing));
        }
        return result;
    }

    private Subscription mapSubscription(
            com.beenewed.backend.model.Subscription record,
            Map<Integer, Subscription> existing) {
        int id = Math.toIntExact(record.id());
        Subscription result = existing.get(id);
        if (result == null) {
            result = new Subscription(
                    id,
                    record.name(),
                    record.category(),
                    icon(record.iconKey(), record.category()),
                    record.currentPrice().doubleValue(),
                    Cycle.valueOf(record.billingCycle().name()),
                    record.nextRenewalDate());
        }
        applySubscription(result, record);
        return result;
    }

    private static void applySubscription(Subscription target, com.beenewed.backend.model.Subscription record) {
        target.name = record.name();
        target.category = record.category();
        target.icon = icon(record.iconKey(), record.category());
        target.price = record.currentPrice().doubleValue();
        target.cycle = Cycle.valueOf(record.billingCycle().name());
        target.nextRenewal = record.nextRenewalDate();
        target.status = Status.valueOf(record.status().name());
    }

    private List<Payment> mapPayments(
            List<com.beenewed.backend.model.Payment> records,
            Map<Integer, Subscription> subscriptionsById,
            Map<Integer, Payment> existing) {
        List<Payment> result = new ArrayList<>(records.size());
        for (com.beenewed.backend.model.Payment record : records) {
            int id = Math.toIntExact(record.id());
            Subscription subscription = requireMapped(
                    subscriptionsById.get(Math.toIntExact(record.subscriptionId())),
                    "Subscription for payment " + id);
            Payment payment = existing.get(id);
            if (payment == null) {
                payment = new Payment(
                        id,
                        subscription,
                        record.paymentDate(),
                        record.amountPaid().doubleValue(),
                        record.renewalPeriod(),
                        record.paymentMethod(),
                        record.notes());
            }
            payment.sub = subscription;
            payment.date = record.paymentDate();
            payment.amount = record.amountPaid().doubleValue();
            payment.period = record.renewalPeriod();
            payment.method = record.paymentMethod();
            payment.notes = record.notes();
            result.add(payment);
        }
        return result;
    }

    private List<Member> mapMembers(
            List<com.beenewed.backend.model.Member> records,
            Map<Integer, Subscription> subscriptionsById,
            Map<Integer, Member> existing) {
        List<Member> result = new ArrayList<>(records.size());
        for (com.beenewed.backend.model.Member record : records) {
            int id = Math.toIntExact(record.id());
            Subscription subscription = requireMapped(
                    subscriptionsById.get(Math.toIntExact(record.subscriptionId())),
                    "Subscription for member " + id);
            Member member = existing.get(id);
            if (member == null) {
                member = new Member(id, subscription, record.name(), record.roleNotes());
            }
            member.sub = subscription;
            member.name = record.name();
            member.notes = record.roleNotes();
            result.add(member);
        }
        return result;
    }

    private List<Contribution> mapContributions(
            List<com.beenewed.backend.model.Contribution> records,
            Map<Integer, Subscription> subscriptionsById,
            Map<Integer, Member> membersById,
            Map<Integer, Payment> paymentsById,
            Map<Integer, Contribution> existing) {
        List<Contribution> result = new ArrayList<>(records.size());
        for (com.beenewed.backend.model.Contribution record : records) {
            int id = Math.toIntExact(record.id());
            Payment payment = requireMapped(
                    paymentsById.get(Math.toIntExact(record.paymentId())),
                    "Payment for contribution " + id);
            Member member = requireMapped(
                    membersById.get(Math.toIntExact(record.memberId())),
                    "Member for contribution " + id);
            Subscription subscription = requireMapped(
                    subscriptionsById.get(payment.sub.id),
                    "Subscription for contribution " + id);
            Contribution contribution = existing.get(id);
            if (contribution == null) {
                contribution = new Contribution(
                        id,
                        subscription,
                        member,
                        payment,
                        record.amountReceived().doubleValue(),
                        record.dateReceived());
            }
            contribution.sub = subscription;
            contribution.member = member;
            contribution.payment = payment;
            contribution.amount = record.amountReceived().doubleValue();
            contribution.date = record.dateReceived();
            result.add(contribution);
        }
        return result;
    }

    private List<Usage> mapUsages(
            List<com.beenewed.backend.model.UsageLog> records,
            Map<Integer, Subscription> subscriptionsById,
            Map<Integer, Usage> existing) {
        List<Usage> result = new ArrayList<>(records.size());
        for (com.beenewed.backend.model.UsageLog record : records) {
            int id = Math.toIntExact(record.id());
            Subscription subscription = requireMapped(
                    subscriptionsById.get(Math.toIntExact(record.subscriptionId())),
                    "Subscription for usage log " + id);
            Usage usage = existing.get(id);
            if (usage == null) {
                usage = new Usage(
                        id,
                        subscription,
                        record.usageDate(),
                        record.quantity(),
                        record.unit(),
                        record.notes());
            }
            usage.sub = subscription;
            usage.date = record.usageDate();
            usage.quantity = record.quantity();
            usage.unit = record.unit();
            usage.notes = record.notes();
            result.add(usage);
        }
        return result;
    }

    private List<PriceChange> mapPriceChanges(
            List<com.beenewed.backend.model.PriceHistory> records,
            Map<Integer, Subscription> subscriptionsById,
            Map<Integer, PriceChange> existing) {
        List<PriceChange> result = new ArrayList<>(records.size());
        for (com.beenewed.backend.model.PriceHistory record : records) {
            int id = Math.toIntExact(record.id());
            Subscription subscription = requireMapped(
                    subscriptionsById.get(Math.toIntExact(record.subscriptionId())),
                    "Subscription for price history " + id);
            PriceChange price = existing.get(id);
            if (price == null) {
                price = new PriceChange(
                        id,
                        subscription,
                        record.effectiveDate(),
                        record.previousPrice().doubleValue(),
                        record.newPrice().doubleValue(),
                        record.reason());
            }
            price.sub = subscription;
            price.date = record.effectiveDate();
            price.oldPrice = record.previousPrice().doubleValue();
            price.newPrice = record.newPrice().doubleValue();
            price.notes = record.reason();
            result.add(price);
        }
        return result;
    }

    private Map<Integer, Subscription> indexSubscriptions() {
        return index(subscriptions, subscription -> subscription.id);
    }

    private static <T> Map<Integer, T> index(List<T> values, IdExtractor<T> idExtractor) {
        Map<Integer, T> indexed = new HashMap<>();
        for (T value : values) {
            indexed.put(idExtractor.id(value), value);
        }
        return indexed;
    }

    private static <T> T requireMapped(T value, String description) {
        if (value == null) {
            throw new SupabaseException(description + " is missing while mapping backend records");
        }
        return value;
    }

    private static com.beenewed.backend.model.Subscription toBackendSubscription(
            Subscription subscription,
            Integer id) {
        return new com.beenewed.backend.model.Subscription(
                id == null ? null : id.longValue(),
                subscription.name,
                subscription.category,
                subscription.icon == null ? null : subscription.icon.name().toLowerCase(Locale.ROOT),
                money(subscription.price),
                com.beenewed.backend.model.BillingCycle.valueOf(subscription.cycle.name()),
                subscription.nextRenewal,
                com.beenewed.backend.model.SubscriptionStatus.valueOf(subscription.status.name()),
                null,
                null);
    }

    private static com.beenewed.backend.model.Payment toBackendPayment(Payment payment, Integer id) {
        return new com.beenewed.backend.model.Payment(
                id == null ? null : id.longValue(),
                (long) payment.sub.id,
                payment.date,
                money(payment.amount),
                payment.period,
                "Not specified".equals(payment.method) ? null : payment.method,
                payment.notes,
                null);
    }

    private static com.beenewed.backend.model.Member toBackendMember(Member member, Integer id) {
        return new com.beenewed.backend.model.Member(
                id == null ? null : id.longValue(),
                (long) member.sub.id,
                member.name,
                member.notes,
                null,
                null);
    }

    private static com.beenewed.backend.model.Contribution toBackendContribution(
            Contribution contribution,
            Integer id) {
        return new com.beenewed.backend.model.Contribution(
                id == null ? null : id.longValue(),
                (long) contribution.member.id,
                (long) contribution.payment.id,
                money(contribution.amount),
                contribution.date,
                null);
    }

    private static com.beenewed.backend.model.UsageLog toBackendUsage(Usage usage, Integer id) {
        return new com.beenewed.backend.model.UsageLog(
                id == null ? null : id.longValue(),
                (long) usage.sub.id,
                usage.date,
                usage.quantity,
                usage.unit,
                usage.notes,
                null);
    }

    private static Icons.Type icon(String iconKey, String category) {
        if (iconKey == null || iconKey.isBlank()) {
            return Models.defaultIcon(category);
        }
        try {
            return Icons.Type.valueOf(iconKey.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownIcon) {
            return Models.defaultIcon(category);
        }
    }

    private static BigDecimal money(double amount) {
        if (!Double.isFinite(amount)) {
            throw new ValidationException("Money amount must be a finite number");
        }
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean containsIgnoreCase(String value, String lowerNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static <T> void replace(List<T> current, List<T> replacement) {
        current.clear();
        current.addAll(replacement);
    }

    private <T> T withBackend(BackendOperation<T> operation) {
        try {
            return operation.run();
        } catch (SupabaseException exception) {
            throw new BackendAccessException(exception);
        }
    }

    private record CategoryPaidTotal(BigDecimal amountPaid, int paymentCount) {}
    private record SubscriptionTotal(Long id, String name, BigDecimal amountPaid, int paymentCount) {}
    private record CategoryEstimateTotal(int subscriptionCount, BigDecimal monthlyEquivalent, BigDecimal annualEquivalent) {}

    @FunctionalInterface
    private interface BackendOperation<T> {
        T run();
    }

    @FunctionalInterface
    private interface IdExtractor<T> {
        int id(T value);
    }

    public static final class BackendAccessException extends RuntimeException {
        public BackendAccessException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }
}
