package beenewed.model;

import beenewed.model.Models.*;
import beenewed.ui.Icons;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.supabase.SupabaseException;
import com.beenewed.supabase.SupabaseRestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** UI-facing model adapter backed by Supabase PostgREST services. */
public final class Store {
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
        return java.time.temporal.ChronoUnit.DAYS.between(today(), date);
    }

    public final List<Subscription> subscriptions = new ArrayList<>();
    public final List<Payment> payments = new ArrayList<>();
    public final List<Member> members = new ArrayList<>();
    public final List<Contribution> contributions = new ArrayList<>();
    public final List<Usage> usages = new ArrayList<>();
    public final List<PriceChange> priceChanges = new ArrayList<>();

    public int remindDays = 7;
    public boolean showOverdue = true;

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

    public void refresh() {
        withBackend(() -> {
            reload();
            return null;
        });
    }

    public void addSubscription(Subscription subscription) {
        withBackend(() -> supabase.createSubscription(toBackendSubscription(subscription, null)));
    }

    public void updateSubscription(Subscription subscription) {
        withBackend(() -> supabase.updateSubscription(toBackendSubscription(subscription, subscription.id)));
    }

    public void deleteSubscription(Subscription subscription) {
        withBackend(() -> supabase.deleteSubscription((long) subscription.id));
    }

    public void changeStatus(Subscription subscription, Status status) {
        Objects.requireNonNull(status, "status must not be null");
        withBackend(() -> {
            long id = subscription.id;
            switch (status) {
                case ACTIVE -> supabase.reactivateSubscription(id, null);
                case PAUSED -> supabase.pauseSubscription(id);
                case CANCELLED -> supabase.cancelSubscription(id);
            }
            return null;
        });
    }

    public void recordPayment(Payment payment) {
        withBackend(() -> supabase.recordPayment(toBackendPayment(payment, null)));
    }

    public void updatePayment(Payment payment) {
        withBackend(() -> supabase.updatePayment(toBackendPayment(payment, payment.id)));
    }

    public void deletePayment(Payment payment) {
        withBackend(() -> supabase.deletePayment((long) payment.id));
    }

    public void addMember(Member member) {
        withBackend(() -> supabase.addMember(
                (long) member.sub.id,
                toBackendMember(member, null)));
    }

    public void updateMember(Member member) {
        withBackend(() -> supabase.updateMember(
                (long) member.sub.id,
                toBackendMember(member, member.id)));
    }

    public void deleteMember(Member member) {
        withBackend(() -> supabase.deleteMember((long) member.sub.id, (long) member.id));
    }

    public void recordContribution(Contribution contribution) {
        withBackend(() -> supabase.recordContribution(
                (long) contribution.payment.id,
                toBackendContribution(contribution, null)));
    }

    public void updateContribution(Contribution contribution) {
        withBackend(() -> supabase.updateContribution(
                (long) contribution.payment.id,
                toBackendContribution(contribution, contribution.id)));
    }

    public void deleteContribution(Contribution contribution) {
        withBackend(() -> supabase.deleteContribution(
                (long) contribution.payment.id,
                (long) contribution.id));
    }

    public void addUsage(Usage usage) {
        withBackend(() -> supabase.addUsageLog(
                (long) usage.sub.id,
                toBackendUsage(usage, null)));
    }

    public void updateUsage(Usage usage) {
        withBackend(() -> supabase.updateUsageLog(
                (long) usage.sub.id,
                toBackendUsage(usage, usage.id)));
    }

    public void deleteUsage(Usage usage) {
        withBackend(() -> supabase.deleteUsageLog((long) usage.sub.id, (long) usage.id));
    }

    public void recordPriceChange(Subscription subscription, LocalDate date, double newPrice, String reason) {
        withBackend(() -> supabase.recordPriceChange(
                (long) subscription.id,
                money(newPrice),
                date,
                reason));
    }

    public void savePrefs(int days, boolean overdue) {
        withBackend(() -> {
            com.beenewed.backend.model.AppPreferences current = supabase.getPreferences();
            supabase.updatePreferences(new com.beenewed.backend.model.AppPreferences(
                    1L,
                    current.currency(),
                    current.theme(),
                    days,
                    overdue));
            remindDays = days;
            showOverdue = overdue;
            return null;
        });
    }

    public com.beenewed.backend.model.Theme getTheme() {
        return withBackend(() -> supabase.getPreferences().theme());
    }

    public void saveTheme(boolean dark) {
        withBackend(() -> {
            com.beenewed.backend.model.AppPreferences current = supabase.getPreferences();
            supabase.updatePreferences(new com.beenewed.backend.model.AppPreferences(
                    1L,
                    current.currency(),
                    dark
                            ? com.beenewed.backend.model.Theme.DARK
                            : com.beenewed.backend.model.Theme.LIGHT,
                    current.reminderLeadDays(),
                    current.showOverdueReminders()));
            return null;
        });
    }

    public List<Subscription> findSubscriptions(String search, String category, Status status) {
        return withBackend(() -> {
            com.beenewed.backend.model.SubscriptionStatus backendStatus = status == null
                    ? null
                    : com.beenewed.backend.model.SubscriptionStatus.valueOf(status.name());
            String normalizedCategory = category == null || category.startsWith("All") ? null : category;
            String normalizedSearch = normalize(search);

            List<Subscription> result = mapSubscriptionsAndCache(
                    supabase.findFilteredSubscriptions(
                            normalizedSearch,
                            normalizedCategory,
                            backendStatus));
            result.sort(Comparator.comparing(subscription -> subscription.name.toLowerCase(Locale.ROOT)));
            return result;
        });
    }

    public List<Subscription> active() {
        return findSubscriptions(null, null, Status.ACTIVE);
    }

    public List<Subscription> findRenewals(
            LocalDate startDate,
            LocalDate endDate,
            Status status) {
        return withBackend(() -> {
            com.beenewed.backend.model.SubscriptionStatus backendStatus = status == null
                    ? null
                    : com.beenewed.backend.model.SubscriptionStatus.valueOf(status.name());
            return mapSubscriptionsAndCache(supabase.findSubscriptionsByRenewalRange(startDate, endDate, backendStatus));
        });
    }

    public List<Subscription> byRenewal(List<Subscription> subscriptions) {
        List<Subscription> ordered = new ArrayList<>(subscriptions);
        ordered.sort(Comparator.comparing(subscription -> subscription.nextRenewal));
        return ordered;
    }

    public List<Payment> findPayments(
            LocalDate startDate,
            LocalDate endDate,
            Status subscriptionStatus,
            String search) {
        return withBackend(() -> {
            com.beenewed.backend.model.SubscriptionStatus backendStatus = subscriptionStatus == null
                    ? null
                    : com.beenewed.backend.model.SubscriptionStatus.valueOf(subscriptionStatus.name());
            List<com.beenewed.backend.model.Payment> records = supabase.findFilteredPayments(
                    null,
                    startDate,
                    endDate,
                    backendStatus,
                    normalize(search));
            return mapPaymentsAndCache(records);
        });
    }

    public List<Payment> findPayments(LocalDate startDate, LocalDate endDate) {
        return withBackend(() ->
                mapPaymentsAndCache(supabase.findPaymentsByDateRange(startDate, endDate)));
    }

    public List<Payment> paymentsNewestFirst() {
        return withBackend(() -> mapPaymentsAndCache(supabase.findAllPayments()));
    }

    public List<Payment> paymentsFor(Subscription subscription) {
        return withBackend(() -> mapPaymentsAndCache(
                supabase.findPaymentsBySubscriptionId((long) subscription.id)));
    }

    public List<Member> membersFor(Subscription subscription) {
        return withBackend(() -> mapMembersAndCache(
                supabase.findMembersBySubscriptionId((long) subscription.id)));
    }

    public List<Contribution> contributionsFor(Subscription subscription) {
        return withBackend(() -> {
            List<Contribution> result = new ArrayList<>();
            for (com.beenewed.backend.model.Payment payment :
                    supabase.findPaymentsBySubscriptionId((long) subscription.id)) {
                result.addAll(mapContributionsAndCache(
                        supabase.findContributionsByPaymentId(payment.id())));
            }
            result.sort(Comparator.comparing(contribution -> contribution.date));
            return result;
        });
    }

    public List<Usage> usagesFor(Subscription subscription) {
        return withBackend(() -> mapUsagesAndCache(
                supabase.findUsageLogsBySubscriptionId((long) subscription.id)));
    }

    public List<PriceChange> priceChangesFor(Subscription subscription) {
        return withBackend(() -> mapPriceChangesAndCache(
                supabase.findPriceHistoryBySubscriptionId((long) subscription.id)));
    }

    public double monthlyTotal() {
        return dashboardSummary().estimatedMonthlyCost().doubleValue();
    }

    public double annualTotal() {
        return dashboardSummary().estimatedAnnualCost().doubleValue();
    }

    public com.beenewed.backend.services.DashboardService.DashboardSummary dashboardSummary() {
        return withBackend(supabase::getDashboardSummary);
    }

    public List<Subscription> upcomingReminders() {
        return upcomingReminders(dashboardSummary());
    }

    public List<Subscription> upcomingReminders(
            com.beenewed.backend.services.DashboardService.DashboardSummary summary) {
        return reminders(summary.upcomingRenewals());
    }

    public List<Subscription> overdue() {
        return reminders(dashboardSummary().overdueRenewals());
    }

    public List<Subscription> overdue(
            com.beenewed.backend.services.DashboardService.DashboardSummary summary) {
        return reminders(summary.overdueRenewals());
    }

    public com.beenewed.backend.services.ReportService.ActualPaidReport actualPaidForRange(
            LocalDate startDate,
            LocalDate endDate) {
        return withBackend(() -> supabase.actualPaidForRange(startDate, endDate));
    }

    public com.beenewed.backend.services.ReportService.ActualPaidReport actualPaidForMonth(YearMonth month) {
        return withBackend(() -> supabase.actualPaidForMonth(month));
    }

    public com.beenewed.backend.services.ReportService.ActualPaidReport actualPaidForYear(Year year) {
        return withBackend(() -> supabase.actualPaidForYear(year));
    }

    public com.beenewed.backend.services.ReportService.EstimatedRecurringReport estimatedRecurringCost() {
        return withBackend(supabase::currentEstimatedRecurringCost);
    }

    public BigDecimal allTimeActualPaid() {
        return withBackend(supabase::allTimeActualPaid);
    }

    public Subscription getSubscription(int id) {
        return withBackend(() -> mapSubscriptionAndCache(supabase.getSubscriptionById((long) id)));
    }

    public boolean containsSubscription(Subscription subscription) {
        try {
            getSubscription(subscription.id);
            return true;
        } catch (NotFoundException missing) {
            return false;
        }
    }

    private List<Subscription> reminders(
            List<com.beenewed.backend.services.DashboardService.RenewalReminder> reminders) {
        Map<Integer, Subscription> cached = indexSubscriptions();
        List<Subscription> result = new ArrayList<>();
        for (com.beenewed.backend.services.DashboardService.RenewalReminder reminder : reminders) {
            int id = Math.toIntExact(reminder.subscriptionId());
            Subscription sub = cached.get(id);
            result.add(sub != null ? sub : getSubscription(id));
        }
        return result;
    }

    private void reload() {
        List<com.beenewed.backend.model.Subscription> subscriptionRecords = supabase.findAllSubscriptions();
        List<com.beenewed.backend.model.Payment> paymentRecords = supabase.findAllPayments();
        List<com.beenewed.backend.model.Member> memberRecords = supabase.findAllMembers();
        List<com.beenewed.backend.model.Contribution> contributionRecords = supabase.findAllContributions();
        List<com.beenewed.backend.model.UsageLog> usageRecords = supabase.findAllUsageLogs();
        List<com.beenewed.backend.model.PriceHistory> priceRecords = supabase.findAllPriceHistory();
        com.beenewed.backend.model.AppPreferences preferences = supabase.getPreferences();

        Map<Integer, Subscription> oldSubscriptions = indexSubscriptions();
        List<Subscription> loadedSubscriptions = mapSubscriptions(subscriptionRecords, oldSubscriptions);
        Map<Integer, Subscription> subscriptionsById = index(loadedSubscriptions, subscription -> subscription.id);

        Map<Integer, Payment> oldPayments = index(payments, payment -> payment.id);
        List<Payment> loadedPayments = mapPayments(paymentRecords, subscriptionsById, oldPayments);
        Map<Integer, Payment> paymentsById = index(loadedPayments, payment -> payment.id);

        Map<Integer, Member> oldMembers = index(members, member -> member.id);
        List<Member> loadedMembers = mapMembers(memberRecords, subscriptionsById, oldMembers);
        Map<Integer, Member> membersById = index(loadedMembers, member -> member.id);

        Map<Integer, Contribution> oldContributions = index(contributions, contribution -> contribution.id);
        List<Contribution> loadedContributions = mapContributions(
                contributionRecords, subscriptionsById, membersById, paymentsById, oldContributions);
        List<Usage> loadedUsages = mapUsages(
                usageRecords, subscriptionsById, index(usages, usage -> usage.id));
        List<PriceChange> loadedPriceChanges = mapPriceChanges(
                priceRecords, subscriptionsById, index(priceChanges, change -> change.id));

        replace(subscriptions, loadedSubscriptions);
        replace(payments, loadedPayments);
        replace(members, loadedMembers);
        replace(contributions, loadedContributions);
        replace(usages, loadedUsages);
        replace(priceChanges, loadedPriceChanges);
        remindDays = preferences.reminderLeadDays();
        showOverdue = preferences.showOverdueReminders();
    }

    private List<Subscription> mapSubscriptions(
            List<com.beenewed.backend.model.Subscription> records) {
        return mapSubscriptions(records, indexSubscriptions());
    }

    private List<Subscription> mapSubscriptions(
            List<com.beenewed.backend.model.Subscription> records,
            Map<Integer, Subscription> existing) {
        List<Subscription> result = new ArrayList<>();
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
        result.name = record.name();
        result.category = record.category();
        result.icon = icon(record.iconKey(), record.category());
        result.price = record.currentPrice().doubleValue();
        result.cycle = Cycle.valueOf(record.billingCycle().name());
        result.nextRenewal = record.nextRenewalDate();
        result.status = Status.valueOf(record.status().name());
        return result;
    }

    private List<Payment> mapPayments(List<com.beenewed.backend.model.Payment> records) {
        return mapPayments(records, indexSubscriptions(), index(payments, payment -> payment.id));
    }

    private List<Payment> mapPayments(
            List<com.beenewed.backend.model.Payment> records,
            Map<Integer, Subscription> subscriptionsById,
            Map<Integer, Payment> existing) {
        List<Payment> result = new ArrayList<>();
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

    private List<Member> mapMembers(List<com.beenewed.backend.model.Member> records) {
        return mapMembers(records, indexSubscriptions(), index(members, member -> member.id));
    }

    private List<Member> mapMembers(
            List<com.beenewed.backend.model.Member> records,
            Map<Integer, Subscription> subscriptionsById,
            Map<Integer, Member> existing) {
        List<Member> result = new ArrayList<>();
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
            List<com.beenewed.backend.model.Contribution> records) {
        return mapContributions(
                records,
                indexSubscriptions(),
                index(members, member -> member.id),
                index(payments, payment -> payment.id),
                index(contributions, contribution -> contribution.id));
    }

    private List<Contribution> mapContributions(
            List<com.beenewed.backend.model.Contribution> records,
            Map<Integer, Subscription> subscriptionsById,
            Map<Integer, Member> membersById,
            Map<Integer, Payment> paymentsById,
            Map<Integer, Contribution> existing) {
        List<Contribution> result = new ArrayList<>();
        for (com.beenewed.backend.model.Contribution record : records) {
            int id = Math.toIntExact(record.id());
            Payment payment = requireMapped(
                    paymentsById.get(Math.toIntExact(record.paymentId())),
                    "Payment for contribution " + id);
            Member member = requireMapped(
                    membersById.get(Math.toIntExact(record.memberId())),
                    "Member for contribution " + id);
            Subscription subscription = requireMapped(
                    subscriptionsById.get(Math.toIntExact(payment.sub.id)),
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

    private List<Usage> mapUsages(List<com.beenewed.backend.model.UsageLog> records) {
        return mapUsages(records, indexSubscriptions(), index(usages, usage -> usage.id));
    }

    private List<Usage> mapUsages(
            List<com.beenewed.backend.model.UsageLog> records,
            Map<Integer, Subscription> subscriptionsById,
            Map<Integer, Usage> existing) {
        List<Usage> result = new ArrayList<>();
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
            List<com.beenewed.backend.model.PriceHistory> records) {
        return mapPriceChanges(records, indexSubscriptions(), index(priceChanges, price -> price.id));
    }

    private List<PriceChange> mapPriceChanges(
            List<com.beenewed.backend.model.PriceHistory> records,
            Map<Integer, Subscription> subscriptionsById,
            Map<Integer, PriceChange> existing) {
        List<PriceChange> result = new ArrayList<>();
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

    private List<Subscription> mapSubscriptionsAndCache(
            List<com.beenewed.backend.model.Subscription> records) {
        List<Subscription> result = mapSubscriptions(records);
        for (Subscription subscription : result) {
            if (subscriptions.stream().noneMatch(existing -> existing.id == subscription.id)) {
                subscriptions.add(subscription);
            }
        }
        return result;
    }

    private Subscription mapSubscriptionAndCache(com.beenewed.backend.model.Subscription record) {
        Map<Integer, Subscription> existing = indexSubscriptions();
        Subscription subscription = mapSubscription(record, existing);
        if (!existing.containsKey(subscription.id)) {
            subscriptions.add(subscription);
        }
        return subscription;
    }

    private List<Payment> mapPaymentsAndCache(
            List<com.beenewed.backend.model.Payment> records) {
        List<Payment> result = mapPayments(records);
        for (Payment payment : result) {
            if (payments.stream().noneMatch(existing -> existing.id == payment.id)) {
                payments.add(payment);
            }
        }
        return result;
    }

    private List<Member> mapMembersAndCache(List<com.beenewed.backend.model.Member> records) {
        List<Member> result = mapMembers(records);
        for (Member member : result) {
            if (members.stream().noneMatch(existing -> existing.id == member.id)) {
                members.add(member);
            }
        }
        return result;
    }

    private List<Contribution> mapContributionsAndCache(
            List<com.beenewed.backend.model.Contribution> records) {
        List<Contribution> result = mapContributions(records);
        for (Contribution contribution : result) {
            if (contributions.stream().noneMatch(existing -> existing.id == contribution.id)) {
                contributions.add(contribution);
            }
        }
        return result;
    }

    private List<Usage> mapUsagesAndCache(List<com.beenewed.backend.model.UsageLog> records) {
        List<Usage> result = mapUsages(records);
        for (Usage usage : result) {
            if (usages.stream().noneMatch(existing -> existing.id == usage.id)) {
                usages.add(usage);
            }
        }
        return result;
    }

    private List<PriceChange> mapPriceChangesAndCache(
            List<com.beenewed.backend.model.PriceHistory> records) {
        List<PriceChange> result = mapPriceChanges(records);
        for (PriceChange price : result) {
            if (priceChanges.stream().noneMatch(existing -> existing.id == price.id)) {
                priceChanges.add(price);
            }
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
