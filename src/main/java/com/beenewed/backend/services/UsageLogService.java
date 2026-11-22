package com.beenewed.backend.services;

import com.beenewed.backend.dao.SubscriptionDao;
import com.beenewed.backend.dao.UsageLogDao;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.model.UsageLog;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class UsageLogService {
    private final UsageLogDao usageLogDao;
    private final SubscriptionDao subscriptionDao;

    public UsageLogService(UsageLogDao usageLogDao, SubscriptionDao subscriptionDao) {
        this.usageLogDao = Objects.requireNonNull(usageLogDao, "usageLogDao must not be null");
        this.subscriptionDao = Objects.requireNonNull(subscriptionDao, "subscriptionDao must not be null");
    }

    public UsageLog addUsageLog(Long subscriptionId, UsageLog usageLog) throws SQLException {
        validate(subscriptionId, usageLog);
        if (usageLog.id() != null) {
            throw new ValidationException("A new usage log must not have an id");
        }
        requireSubscription(subscriptionId);
        return usageLogDao.insert(usageLog);
    }

    public UsageLog getUsageLog(Long subscriptionId, Long usageLogId) throws SQLException {
        validateIds(subscriptionId, usageLogId);
        return usageLogDao.findById(subscriptionId, usageLogId)
                .orElseThrow(() -> new NotFoundException("Usage log not found: " + usageLogId));
    }

    public List<UsageLog> findBySubscriptionId(Long subscriptionId) throws SQLException {
        requireSubscription(subscriptionId);
        return usageLogDao.findBySubscriptionId(subscriptionId);
    }

    public UsageLog updateUsageLog(Long subscriptionId, UsageLog usageLog) throws SQLException {
        validate(subscriptionId, usageLog);
        if (usageLog.id() == null || usageLog.id() <= 0) {
            throw new ValidationException("An updated usage log must have a positive id");
        }
        requireSubscription(subscriptionId);
        getUsageLog(subscriptionId, usageLog.id());
        if (!usageLogDao.update(subscriptionId, usageLog)) {
            throw new NotFoundException("Usage log not found: " + usageLog.id());
        }
        return getUsageLog(subscriptionId, usageLog.id());
    }

    public boolean deleteUsageLog(Long subscriptionId, Long usageLogId) throws SQLException {
        getUsageLog(subscriptionId, usageLogId);
        if (!usageLogDao.deleteById(subscriptionId, usageLogId)) {
            throw new NotFoundException("Usage log not found: " + usageLogId);
        }
        return true;
    }

    private void requireSubscription(Long subscriptionId) throws SQLException {
        if (subscriptionId == null || subscriptionId <= 0) {
            throw new ValidationException("subscriptionId must be a positive id");
        }
        if (subscriptionDao.findById(subscriptionId).isEmpty()) {
            throw new NotFoundException("Subscription not found: " + subscriptionId);
        }
    }

    private static void validate(Long subscriptionId, UsageLog usageLog) {
        if (usageLog == null) {
            throw new ValidationException("Usage log is required");
        }
        if (subscriptionId == null || subscriptionId <= 0) {
            throw new ValidationException("subscriptionId must be a positive id");
        }
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
            if (usageLog.unit() == null || usageLog.unit().isBlank()) {
                throw new ValidationException("Usage unit is required when quantity is provided");
            }
        }
    }

    private static void validateIds(Long subscriptionId, Long usageLogId) {
        if (subscriptionId == null || subscriptionId <= 0 || usageLogId == null || usageLogId <= 0) {
            throw new ValidationException("subscriptionId and usageLogId must be positive ids");
        }
    }
}
