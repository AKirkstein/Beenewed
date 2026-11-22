package com.beenewed.backend.services;

import com.beenewed.backend.dao.PaymentDao;
import com.beenewed.backend.dao.SubscriptionDao;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.model.Payment;
import com.beenewed.backend.model.Subscription;

import java.math.BigDecimal;
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
        Map<String, BigDecimal> categoryTotals = new HashMap<>();
        Map<Long, SubscriptionTotal> subscriptionTotals = new HashMap<>();
        for (Payment payment : paymentDao.findByDateRange(startDate, endDate)) {
            Subscription subscription = subscriptions.get(payment.subscriptionId());
            if (subscription == null) {
                throw new NotFoundException(
                        "Subscription not found for payment: " + payment.subscriptionId());
            }
            BigDecimal amount = payment.amountPaid();
            totalPaid = totalPaid.add(amount);
            categoryTotals.merge(subscription.category(), amount, BigDecimal::add);
            subscriptionTotals.compute(payment.subscriptionId(), (id, current) -> current == null
                    ? new SubscriptionTotal(id, subscription.name(), amount)
                    : new SubscriptionTotal(id, current.name(), current.amountPaid().add(amount)));
        }

        List<CategoryBreakdown> byCategory = new ArrayList<>();
        categoryTotals.forEach((category, amount) -> byCategory.add(new CategoryBreakdown(category, amount)));
        byCategory.sort(Comparator.comparing(CategoryBreakdown::category, String.CASE_INSENSITIVE_ORDER));

        List<SubscriptionBreakdown> bySubscription = new ArrayList<>();
        subscriptionTotals.forEach((id, value) ->
                bySubscription.add(new SubscriptionBreakdown(id, value.name(), value.amountPaid())));
        bySubscription.sort(Comparator.comparing(
                        SubscriptionBreakdown::subscriptionName,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(SubscriptionBreakdown::subscriptionId));

        return new ActualPaidReport(
                startDate,
                endDate,
                totalPaid,
                byCategory,
                bySubscription);
    }

    private record SubscriptionTotal(Long id, String name, BigDecimal amountPaid) {
    }

    public record CategoryBreakdown(String category, BigDecimal amountPaid) {
    }

    public record SubscriptionBreakdown(
            Long subscriptionId,
            String subscriptionName,
            BigDecimal amountPaid) {
    }

    public record ActualPaidReport(
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal totalPaid,
            List<CategoryBreakdown> byCategory,
            List<SubscriptionBreakdown> bySubscription) {
        public ActualPaidReport {
            byCategory = List.copyOf(byCategory);
            bySubscription = List.copyOf(bySubscription);
        }
    }
}
