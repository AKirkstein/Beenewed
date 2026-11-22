package com.beenewed.backend.services;

import com.beenewed.backend.dao.ContributionDao;
import com.beenewed.backend.dao.MemberDao;
import com.beenewed.backend.dao.PaymentDao;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.model.Contribution;
import com.beenewed.backend.model.Member;
import com.beenewed.backend.model.Payment;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class ContributionService {
    private final ContributionDao contributionDao;
    private final PaymentDao paymentDao;
    private final MemberDao memberDao;

    public ContributionService(
            ContributionDao contributionDao,
            PaymentDao paymentDao,
            MemberDao memberDao) {
        this.contributionDao = Objects.requireNonNull(contributionDao, "contributionDao must not be null");
        this.paymentDao = Objects.requireNonNull(paymentDao, "paymentDao must not be null");
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao must not be null");
    }

    public Contribution recordContribution(Long paymentId, Contribution contribution) throws SQLException {
        validate(paymentId, contribution);
        if (!paymentId.equals(contribution.paymentId())) {
            throw new ValidationException("Contribution payment does not match the requested scope");
        }
        if (contribution.id() != null) {
            throw new ValidationException("A new contribution must not have an id");
        }
        Payment payment = getPayment(paymentId);
        requireMemberForPayment(payment, contribution.memberId());
        ensureWithinPaymentTotal(
                payment,
                contributionDao.sumForPayment(paymentId).add(contribution.amountReceived()));
        return contributionDao.insert(contribution);
    }

    public Contribution getContribution(Long paymentId, Long contributionId) throws SQLException {
        validateIds(paymentId, contributionId);
        return contributionDao.findById(paymentId, contributionId)
                .orElseThrow(() -> new NotFoundException("Contribution not found: " + contributionId));
    }

    public List<Contribution> findByPaymentId(Long paymentId) throws SQLException {
        getPayment(paymentId);
        return contributionDao.findByPaymentId(paymentId);
    }

    public Contribution updateContribution(Long paymentId, Contribution contribution) throws SQLException {
        validate(paymentId, contribution);
        if (contribution.id() == null || contribution.id() <= 0) {
            throw new ValidationException("An updated contribution must have a positive id");
        }
        if (!paymentId.equals(contribution.paymentId())) {
            throw new ValidationException("Contribution payment does not match the requested scope");
        }
        Payment payment = getPayment(paymentId);
        Contribution existing = getContribution(paymentId, contribution.id());
        requireMemberForPayment(payment, contribution.memberId());

        BigDecimal prospectiveTotal = contributionDao.sumForPayment(paymentId)
                .subtract(existing.amountReceived())
                .add(contribution.amountReceived());
        ensureWithinPaymentTotal(payment, prospectiveTotal);

        if (!contributionDao.update(paymentId, contribution)) {
            throw new NotFoundException("Contribution not found: " + contribution.id());
        }
        return getContribution(paymentId, contribution.id());
    }

    public boolean deleteContribution(Long paymentId, Long contributionId) throws SQLException {
        getContribution(paymentId, contributionId);
        if (!contributionDao.deleteById(paymentId, contributionId)) {
            throw new NotFoundException("Contribution not found: " + contributionId);
        }
        return true;
    }

    private Payment getPayment(Long paymentId) throws SQLException {
        if (paymentId == null || paymentId <= 0) {
            throw new ValidationException("paymentId must be a positive id");
        }
        return paymentDao.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + paymentId));
    }

    private void requireMemberForPayment(Payment payment, Long memberId) throws SQLException {
        if (memberId == null || memberId <= 0) {
            throw new ValidationException("memberId must be a positive id");
        }
        Member member = memberDao.findById(payment.subscriptionId(), memberId)
                .orElseThrow(() -> new ValidationException(
                        "Contribution member must belong to the payment subscription"));
        if (!payment.subscriptionId().equals(member.subscriptionId())) {
            throw new ValidationException("Contribution member must belong to the payment subscription");
        }
    }

    private static void ensureWithinPaymentTotal(Payment payment, BigDecimal total) {
        if (total.compareTo(payment.amountPaid()) > 0) {
            throw new ValidationException("Total contributions must not exceed the payment amount");
        }
    }

    private static void validate(Long paymentId, Contribution contribution) {
        if (contribution == null) {
            throw new ValidationException("Contribution is required");
        }
        if (paymentId == null || paymentId <= 0) {
            throw new ValidationException("paymentId must be a positive id");
        }
        if (contribution.paymentId() == null || contribution.paymentId() <= 0) {
            throw new ValidationException("Contribution payment id must be positive");
        }
        if (contribution.amountReceived() == null || contribution.amountReceived().signum() <= 0) {
            throw new ValidationException("Contribution amount must be greater than zero");
        }
        if (contribution.dateReceived() == null) {
            throw new ValidationException("Contribution date is required");
        }
    }

    private static void validateIds(Long paymentId, Long contributionId) {
        if (paymentId == null || paymentId <= 0 || contributionId == null || contributionId <= 0) {
            throw new ValidationException("paymentId and contributionId must be positive ids");
        }
    }
}
