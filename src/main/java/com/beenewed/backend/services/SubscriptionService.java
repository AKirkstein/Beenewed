package com.beenewed.backend.services;

import com.beenewed.backend.dao.SubscriptionDao;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.model.BillingCycle;
import com.beenewed.backend.model.Subscription;
import com.beenewed.backend.model.SubscriptionStatus;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class SubscriptionService {
    private final SubscriptionDao subscriptionDao;

    public SubscriptionService(SubscriptionDao subscriptionDao) {
        this.subscriptionDao = Objects.requireNonNull(subscriptionDao, "subscriptionDao must not be null");
    }

    public Subscription create(Subscription subscription) throws SQLException {
        validate(subscription);
        if (subscription.id() != null) {
            throw new ValidationException("A new subscription must not have an id");
        }
        return subscriptionDao.insert(subscription);
    }

    public Subscription getById(Long id) throws SQLException {
        return subscriptionDao.findById(requireId(id, "subscriptionId"))
                .orElseThrow(() -> new NotFoundException("Subscription not found: " + id));
    }

    public List<Subscription> findAll() throws SQLException {
        return subscriptionDao.findAll();
    }

    public List<Subscription> findFiltered(
            String search,
            String category,
            SubscriptionStatus status) throws SQLException {
        return subscriptionDao.findFiltered(search, category, status);
    }

    public List<Subscription> findByRenewalDateRange(
            LocalDate startDate,
            LocalDate endDate,
            SubscriptionStatus status) throws SQLException {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new ValidationException("Renewal end date must not be before start date");
        }
        return subscriptionDao.findByRenewalDateRange(startDate, endDate, status);
    }

    public Subscription update(Subscription subscription) throws SQLException {
        validate(subscription);
        if (subscription.id() == null) {
            throw new ValidationException("An updated subscription must have an id");
        }
        Subscription existing = getById(subscription.id());
        if (subscription.status() != existing.status()) {
            throw new ValidationException("Use a status transition method to change subscription status");
        }
        if (!subscriptionDao.update(subscription)) {
            throw new NotFoundException("Subscription not found: " + subscription.id());
        }
        return getById(subscription.id());
    }

    public Subscription pause(Long subscriptionId) throws SQLException {
        Subscription subscription = getById(subscriptionId);
        if (subscription.status() != SubscriptionStatus.ACTIVE) {
            throw new ValidationException("Only active subscriptions can be paused");
        }
        return saveStatus(subscription, SubscriptionStatus.PAUSED, subscription.nextRenewalDate());
    }

    public Subscription cancel(Long subscriptionId) throws SQLException {
        Subscription subscription = getById(subscriptionId);
        if (subscription.status() == SubscriptionStatus.CANCELLED) {
            throw new ValidationException("Subscription is already cancelled");
        }
        return saveStatus(subscription, SubscriptionStatus.CANCELLED, subscription.nextRenewalDate());
    }

    public Subscription reactivate(Long subscriptionId) throws SQLException {
        return reactivate(subscriptionId, null);
    }

    public Subscription reactivate(Long subscriptionId, LocalDate nextRenewalDate) throws SQLException {
        Subscription subscription = getById(subscriptionId);
        if (subscription.status() == SubscriptionStatus.ACTIVE) {
            throw new ValidationException("Subscription is already active");
        }
        LocalDate renewalDate = nextRenewalDate == null
                ? subscription.nextRenewalDate()
                : nextRenewalDate;
        return saveStatus(subscription, SubscriptionStatus.ACTIVE, renewalDate);
    }

    public boolean delete(Long subscriptionId) throws SQLException {
        getById(subscriptionId);
        if (!subscriptionDao.deleteById(subscriptionId)) {
            throw new NotFoundException("Subscription not found: " + subscriptionId);
        }
        return true;
    }

    public static LocalDate calculateNextRenewalDate(LocalDate currentDate, BillingCycle billingCycle) {
        if (currentDate == null || billingCycle == null) {
            throw new ValidationException("Current date and billing cycle are required");
        }
        return switch (billingCycle) {
            case MONTHLY -> currentDate.plusMonths(1);
            case YEARLY -> currentDate.plusYears(1);
        };
    }

    private Subscription saveStatus(
            Subscription subscription,
            SubscriptionStatus status,
            LocalDate nextRenewalDate) throws SQLException {
        Subscription updated = new Subscription(
                subscription.id(),
                subscription.name(),
                subscription.category(),
                subscription.iconKey(),
                subscription.currentPrice(),
                subscription.billingCycle(),
                nextRenewalDate,
                status,
                subscription.createdAt(),
                subscription.updatedAt());
        if (!subscriptionDao.update(updated)) {
            throw new NotFoundException("Subscription not found: " + subscription.id());
        }
        return getById(subscription.id());
    }

    private static void validate(Subscription subscription) {
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
        BigDecimal price = subscription.currentPrice();
        if (price == null || price.signum() <= 0) {
            throw new ValidationException("Subscription price must be greater than zero");
        }
        if (subscription.billingCycle() == null
                || subscription.nextRenewalDate() == null
                || subscription.status() == null) {
            throw new ValidationException("Billing cycle, renewal date, and status are required");
        }
    }

    private static Long requireId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new ValidationException(name + " must be a positive id");
        }
        return id;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
