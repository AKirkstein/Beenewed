package com.beenewed.backend.services;

import com.beenewed.backend.dao.PriceHistoryDao;
import com.beenewed.backend.dao.SubscriptionDao;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.model.PriceHistory;
import com.beenewed.backend.model.Subscription;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class PriceHistoryService {
    private final Connection connection;
    private final PriceHistoryDao priceHistoryDao;
    private final SubscriptionDao subscriptionDao;

    public PriceHistoryService(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.priceHistoryDao = new PriceHistoryDao(connection);
        this.subscriptionDao = new SubscriptionDao(connection);
    }

    public PriceHistory recordPriceChange(
            Long subscriptionId,
            BigDecimal newPrice,
            LocalDate effectiveDate,
            String reason) throws SQLException {
        validate(subscriptionId, newPrice, effectiveDate);

        boolean ownsTransaction = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (ownsTransaction) {
            connection.setAutoCommit(false);
        } else {
            savepoint = connection.setSavepoint();
        }

        PriceHistory history;
        try {
            Subscription subscription = subscriptionDao.findById(subscriptionId)
                    .orElseThrow(() -> new NotFoundException("Subscription not found: " + subscriptionId));
            if (newPrice.compareTo(subscription.currentPrice()) == 0) {
                throw new ValidationException("New price must differ from the current price");
            }

            history = priceHistoryDao.insert(new PriceHistory(
                    null,
                    subscriptionId,
                    subscription.currentPrice(),
                    newPrice,
                    effectiveDate,
                    reason,
                    null));
            Subscription updated = new Subscription(
                    subscription.id(),
                    subscription.name(),
                    subscription.category(),
                    subscription.iconKey(),
                    newPrice,
                    subscription.billingCycle(),
                    subscription.nextRenewalDate(),
                    subscription.status(),
                    subscription.createdAt(),
                    subscription.updatedAt());
            if (!subscriptionDao.update(updated)) {
                throw new NotFoundException("Subscription not found: " + subscriptionId);
            }

            if (ownsTransaction) {
                connection.commit();
            } else {
                connection.releaseSavepoint(savepoint);
            }
        } catch (SQLException | RuntimeException exception) {
            rollback(exception, ownsTransaction, savepoint);
            if (ownsTransaction) {
                restoreAutoCommit(exception);
            }
            throw exception;
        }
        if (ownsTransaction) {
            connection.setAutoCommit(true);
        }
        return history;
    }

    public List<PriceHistory> findBySubscriptionId(Long subscriptionId) throws SQLException {
        if (subscriptionId == null || subscriptionId <= 0) {
            throw new ValidationException("subscriptionId must be a positive id");
        }
        if (subscriptionDao.findById(subscriptionId).isEmpty()) {
            throw new NotFoundException("Subscription not found: " + subscriptionId);
        }
        return priceHistoryDao.findBySubscriptionId(subscriptionId);
    }

    private static void validate(Long subscriptionId, BigDecimal newPrice, LocalDate effectiveDate) {
        if (subscriptionId == null || subscriptionId <= 0) {
            throw new ValidationException("subscriptionId must be a positive id");
        }
        if (newPrice == null || newPrice.signum() <= 0) {
            throw new ValidationException("New price must be greater than zero");
        }
        if (effectiveDate == null) {
            throw new ValidationException("Price effective date is required");
        }
    }

    private void rollback(Exception original, boolean ownsTransaction, Savepoint savepoint) {
        try {
            if (ownsTransaction) {
                connection.rollback();
            } else {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            }
        } catch (SQLException rollbackException) {
            original.addSuppressed(rollbackException);
        }
    }

    private void restoreAutoCommit(Exception original) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException restoreException) {
            original.addSuppressed(restoreException);
        }
    }
}
