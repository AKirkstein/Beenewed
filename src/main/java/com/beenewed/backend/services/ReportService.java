package com.beenewed.backend.services;

import com.beenewed.backend.dao.PaymentDao;
import com.beenewed.backend.dao.SubscriptionDao;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.model.BillingCycle;
import com.beenewed.backend.model.Payment;
import com.beenewed.backend.model.Subscription;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReportService {
    private final PaymentDao paymentDao;
    private final SubscriptionDao subscriptionDao;

    public ReportService(PaymentDao paymentDao, SubscriptionDao subscriptionDao) {
        this.paymentDao = Objects.requireNonNull(paymentDao, "paymentDao must not be null");
        this.subscriptionDao = Objects.requireNonNull(subscriptionDao, "subscriptionDao must not be null");
    }

    public ActualPaidReport actualPaidForMonth(YearMonth month) throws SQLException {
        if (month == null) {
            throw new ValidationException("Report month is required");
        }
        return actualPaidForRange(month.atDay(1), month.atEndOfMonth());
    }

    public ActualPaidReport actualPaidForYear(Year year) throws SQLException {
        if (year == null) {
            throw new ValidationException("Report year is required");
        }
        return actualPaidForRange(year.atDay(1), year.atMonth(12).atEndOfMonth());
    }

    public BigDecimal allTimeActualPaid() throws SQLException {
        return paymentDao.findAll().stream()
                .map(Payment::amountPaid)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public ActualPaidReport actualPaidForRange(LocalDate startDate, LocalDate endDate) throws SQLException {
        if (startDate == null || endDate == null) {
            throw new ValidationException("Report start and end dates are required");
        }
        if (endDate.isBefore(startDate)) {
            throw new ValidationException("Report end date must not be before start date");
        }

        Map<Long, Subscription> subscriptions = new HashMap<>();
        for (Subscription subscription : subscriptionDao.findAll()) {
            subscriptions.put(subscription.id(), subscription);
        }

        BigDecimal totalPaid = BigDecimal.ZERO;
        int paymentCount = 0;
        Map<String, CategoryPaidTotal> categoryTotals = new HashMap<>();
        Map<Long, SubscriptionTotal> subscriptionTotals = new HashMap<>();
        for (Payment payment : paymentDao.findByDateRange(startDate, endDate)) {
            Subscription subscription = subscriptions.get(payment.subscriptionId());
            if (subscription == null) {
                throw new NotFoundException(
                        "Subscription not found for payment: " + payment.subscriptionId());
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

        List<CategoryBreakdown> byCategory = new ArrayList<>();
        categoryTotals.forEach((category, total) ->
                byCategory.add(new CategoryBreakdown(category, total.amountPaid(), total.paymentCount())));
        byCategory.sort(Comparator.comparing(CategoryBreakdown::category, String.CASE_INSENSITIVE_ORDER));

        List<SubscriptionBreakdown> bySubscription = new ArrayList<>();
        subscriptionTotals.forEach((id, value) ->
                bySubscription.add(new SubscriptionBreakdown(
                        id, value.name(), value.amountPaid(), value.paymentCount())));
        bySubscription.sort(Comparator.comparing(
                        SubscriptionBreakdown::subscriptionName,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SubscriptionBreakdown::subscriptionId));

        return new ActualPaidReport(
                startDate,
                endDate,
                totalPaid,
                paymentCount,
                byCategory,
                bySubscription);
    }

    public EstimatedRecurringReport currentEstimatedRecurringCost() throws SQLException {
        List<Subscription> activeSubscriptions = subscriptionDao.findActiveSubscriptions();
        BigDecimal monthlyTotal = BigDecimal.ZERO;
        BigDecimal annualTotal = BigDecimal.ZERO;
        Map<String, CategoryEstimateTotal> categoryTotals = new HashMap<>();
        List<SubscriptionEstimate> subscriptionEstimates = new ArrayList<>();

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
            subscriptionEstimates.add(new SubscriptionEstimate(
                    subscription.id(),
                    subscription.name(),
                    subscription.category(),
                    subscription.billingCycle(),
                    subscription.currentPrice(),
                    monthly,
                    annual));
        }

        List<CategoryEstimate> byCategory = new ArrayList<>();
        categoryTotals.forEach((category, total) ->
                byCategory.add(new CategoryEstimate(
                        category,
                        total.subscriptionCount(),
                        total.monthlyEquivalent(),
                        total.annualEquivalent())));
        byCategory.sort(Comparator.comparing(CategoryEstimate::category, String.CASE_INSENSITIVE_ORDER));
        subscriptionEstimates.sort(Comparator.comparing(
                        SubscriptionEstimate::name,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SubscriptionEstimate::subscriptionId));

        return new EstimatedRecurringReport(
                activeSubscriptions.size(),
                monthlyTotal,
                annualTotal,
                byCategory,
                subscriptionEstimates);
    }

    private record CategoryPaidTotal(BigDecimal amountPaid, int paymentCount) {
    }

    private record SubscriptionTotal(Long id, String name, BigDecimal amountPaid, int paymentCount) {
    }

    private record CategoryEstimateTotal(
            int subscriptionCount,
            BigDecimal monthlyEquivalent,
            BigDecimal annualEquivalent) {
    }

    public record CategoryBreakdown(String category, BigDecimal amountPaid, int paymentCount) {
    }

    public record SubscriptionBreakdown(
            Long subscriptionId,
            String subscriptionName,
            BigDecimal amountPaid,
            int paymentCount) {
    }

    public record ActualPaidReport(
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal totalPaid,
            int paymentCount,
            List<CategoryBreakdown> byCategory,
            List<SubscriptionBreakdown> bySubscription) {
        public ActualPaidReport {
            byCategory = List.copyOf(byCategory);
            bySubscription = List.copyOf(bySubscription);
        }
    }

    public record CategoryEstimate(
            String category,
            int subscriptionCount,
            BigDecimal monthlyEquivalent,
            BigDecimal annualEquivalent) {
    }

    public record SubscriptionEstimate(
            Long subscriptionId,
            String name,
            String category,
            BillingCycle billingCycle,
            BigDecimal currentPrice,
            BigDecimal monthlyEquivalent,
            BigDecimal annualEquivalent) {
    }

    public record EstimatedRecurringReport(
            int activeSubscriptionCount,
            BigDecimal monthlyTotal,
            BigDecimal annualTotal,
            List<CategoryEstimate> byCategory,
            List<SubscriptionEstimate> bySubscription) {
        public EstimatedRecurringReport {
            byCategory = List.copyOf(byCategory);
            bySubscription = List.copyOf(bySubscription);
        }
    }
}
