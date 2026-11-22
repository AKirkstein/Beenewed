package com.beenewed.backend.services;

import com.beenewed.backend.dao.ContributionDao;
import com.beenewed.backend.dao.PaymentDao;
import com.beenewed.backend.dao.SubscriptionDao;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.model.Payment;
import com.beenewed.backend.model.Subscription;
import com.beenewed.backend.model.SubscriptionStatus;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class PaymentService {
    private final PaymentDao paymentDao;
    private final SubscriptionDao subscriptionDao;
    private final ContributionDao contributionDao;

    public PaymentService(
            PaymentDao paymentDao,
            SubscriptionDao subscriptionDao,
            ContributionDao contributionDao) {
        this.paymentDao = Objects.requireNonNull(paymentDao, "paymentDao must not be null");
        this.subscriptionDao = Objects.requireNonNull(subscriptionDao, "subscriptionDao must not be null");
        this.contributionDao = Objects.requireNonNull(contributionDao, "contributionDao must not be null");
    }

    public Payment recordPayment(Payment payment) throws SQLException {
        validate(payment);
        if (payment.id() != null) {
            throw new ValidationException("A new payment must not have an id");
        }
        requireSubscription(payment.subscriptionId());
        return paymentDao.insert(payment);
    }

    public Payment updatePayment(Payment payment) throws SQLException {
        validate(payment);
        if (payment.id() == null || payment.id() <= 0) {
            throw new ValidationException("An updated payment must have a positive id");
        }
        Payment existing = getById(payment.id());
        requireSubscription(payment.subscriptionId());
        BigDecimal contributionTotal = contributionDao.sumForPayment(payment.id());
        if (!existing.subscriptionId().equals(payment.subscriptionId())
                && contributionTotal.signum() > 0) {
            throw new ValidationException("A payment with contributions cannot be moved to another subscription");
        }
        if (contributionTotal.compareTo(payment.amountPaid()) > 0) {
            throw new ValidationException("Payment amount cannot be less than its total contributions");
        }
        if (!paymentDao.update(payment)) {
            throw new NotFoundException("Payment not found: " + payment.id());
        }
        return getById(payment.id());
    }

    public Payment getById(Long paymentId) throws SQLException {
        requireId(paymentId, "paymentId");
        return paymentDao.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + paymentId));
    }

    public List<Payment> findAll() throws SQLException {
        return paymentDao.findAll();
    }

    public List<Payment> findBySubscriptionId(Long subscriptionId) throws SQLException {
        requireSubscription(subscriptionId);
        return paymentDao.findBySubscriptionId(subscriptionId);
    }

    public List<Payment> findByDateRange(LocalDate startDate, LocalDate endDate) throws SQLException {
        return paymentDao.findByDateRange(startDate, endDate);
    }

    public List<Payment> findFiltered(
            Long subscriptionId,
            LocalDate startDate,
            LocalDate endDate,
            SubscriptionStatus subscriptionStatus,
            String subscriptionSearch) throws SQLException {
        if (subscriptionId != null) {
            requireSubscription(subscriptionId);
        }
        return paymentDao.findFiltered(
                subscriptionId,
                startDate,
                endDate,
                subscriptionStatus,
                subscriptionSearch);
    }

    public boolean deletePayment(Long paymentId) throws SQLException {
        getById(paymentId);
        if (!paymentDao.deleteById(paymentId)) {
            throw new NotFoundException("Payment not found: " + paymentId);
        }
        return true;
    }

    private Subscription requireSubscription(Long subscriptionId) throws SQLException {
        requireId(subscriptionId, "subscriptionId");
        return subscriptionDao.findById(subscriptionId)
                .orElseThrow(() -> new NotFoundException("Subscription not found: " + subscriptionId));
    }

    private static void validate(Payment payment) {
        if (payment == null) {
            throw new ValidationException("Payment is required");
        }
        if (payment.subscriptionId() == null || payment.subscriptionId() <= 0) {
            throw new ValidationException("Payment subscription id must be positive");
        }
        if (payment.paymentDate() == null) {
            throw new ValidationException("Payment date is required");
        }
        if (payment.amountPaid() == null || payment.amountPaid().signum() <= 0) {
            throw new ValidationException("Payment amount must be greater than zero");
        }
        if (payment.renewalPeriod() == null || payment.renewalPeriod().isBlank()) {
            throw new ValidationException("Renewal period must not be blank");
        }
    }

    private static void requireId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new ValidationException(name + " must be a positive id");
        }
    }
}
