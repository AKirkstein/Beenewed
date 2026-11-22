package com.beenewed.backend.services;

import com.beenewed.backend.dao.PaymentDao;
import com.beenewed.backend.dao.SubscriptionDao;
import com.beenewed.backend.model.AppPreferences;
import com.beenewed.backend.model.BillingCycle;
import com.beenewed.backend.model.Payment;
import com.beenewed.backend.model.Subscription;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class DashboardService {
    private static final int RECENT_PAYMENT_LIMIT = 5;
    private static final int CURRENCY_SCALE = 2;

    private final Clock clock;
    private final SubscriptionDao subscriptionDao;
    private final PaymentDao paymentDao;
    private final ReportService reportService;
    private final AppPreferencesService appPreferencesService;

    public DashboardService(
            Clock clock,
            SubscriptionDao subscriptionDao,
            PaymentDao paymentDao,
            ReportService reportService,
            AppPreferencesService appPreferencesService) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.subscriptionDao = Objects.requireNonNull(subscriptionDao, "subscriptionDao must not be null");
        this.paymentDao = Objects.requireNonNull(paymentDao, "paymentDao must not be null");
        this.reportService = Objects.requireNonNull(reportService, "reportService must not be null");
        this.appPreferencesService = Objects.requireNonNull(
                appPreferencesService, "appPreferencesService must not be null");
    }

    public DashboardSummary getSummary() throws SQLException {
        LocalDate today = LocalDate.now(clock);
        List<Subscription> activeSubscriptions = subscriptionDao.findActiveSubscriptions();
        AppPreferences preferences = appPreferencesService.get();

        BigDecimal monthlyEstimate = BigDecimal.ZERO;
        BigDecimal annualEstimate = BigDecimal.ZERO;
        for (Subscription subscription : activeSubscriptions) {
            if (subscription.billingCycle() == BillingCycle.MONTHLY) {
                monthlyEstimate = monthlyEstimate.add(subscription.currentPrice());
                annualEstimate = annualEstimate.add(subscription.currentPrice().multiply(BigDecimal.valueOf(12)));
            } else {
                monthlyEstimate = monthlyEstimate.add(
                        subscription.currentPrice().divide(
                                BigDecimal.valueOf(12),
                                CURRENCY_SCALE,
                                RoundingMode.HALF_UP));
                annualEstimate = annualEstimate.add(subscription.currentPrice());
            }
        }

        YearMonth currentMonth = YearMonth.from(today);
        BigDecimal currentMonthActualPaid = reportService.actualPaidForRange(
                currentMonth.atDay(1),
                currentMonth.atEndOfMonth()).totalPaid();

        List<Payment> recentPayments = paymentDao.findAll().stream()
                .limit(RECENT_PAYMENT_LIMIT)
                .toList();

        List<RenewalReminder> upcomingRenewals = new ArrayList<>();
        List<RenewalReminder> overdueRenewals = new ArrayList<>();
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
        upcomingRenewals.sort(Comparator.comparing(RenewalReminder::nextRenewalDate)
                .thenComparing(RenewalReminder::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RenewalReminder::subscriptionId));
        overdueRenewals.sort(Comparator.comparing(RenewalReminder::nextRenewalDate).reversed()
                .thenComparing(RenewalReminder::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RenewalReminder::subscriptionId));

        return new DashboardSummary(
                activeSubscriptions.size(),
                monthlyEstimate.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                annualEstimate.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                currentMonthActualPaid,
                recentPayments,
                upcomingRenewals,
                overdueRenewals);
    }

    private static RenewalReminder toReminder(Subscription subscription, long daysUntilRenewal) {
        return new RenewalReminder(
                subscription.id(),
                subscription.name(),
                subscription.category(),
                subscription.nextRenewalDate(),
                subscription.currentPrice(),
                subscription.billingCycle(),
                daysUntilRenewal);
    }

    public record RenewalReminder(
            Long subscriptionId,
            String name,
            String category,
            LocalDate nextRenewalDate,
            BigDecimal currentPrice,
            BillingCycle billingCycle,
            long daysUntilRenewal) {
    }

    public record DashboardSummary(
            int activeSubscriptionCount,
            BigDecimal estimatedMonthlyCost,
            BigDecimal estimatedAnnualCost,
            BigDecimal currentMonthActualPaid,
            List<Payment> recentPayments,
            List<RenewalReminder> upcomingRenewals,
            List<RenewalReminder> overdueRenewals) {
        public DashboardSummary {
            recentPayments = List.copyOf(recentPayments);
            upcomingRenewals = List.copyOf(upcomingRenewals);
            overdueRenewals = List.copyOf(overdueRenewals);
        }
    }
}
