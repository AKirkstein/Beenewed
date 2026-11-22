package com.beenewed.backend.services;

import com.beenewed.backend.dao.MemberDao;
import com.beenewed.backend.dao.SubscriptionDao;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.model.Member;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class MemberService {
    private final MemberDao memberDao;
    private final SubscriptionDao subscriptionDao;

    public MemberService(MemberDao memberDao, SubscriptionDao subscriptionDao) {
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao must not be null");
        this.subscriptionDao = Objects.requireNonNull(subscriptionDao, "subscriptionDao must not be null");
    }

    public Member addMember(Long subscriptionId, Member member) throws SQLException {
        validateScope(subscriptionId, member);
        if (member.id() != null) {
            throw new ValidationException("A new member must not have an id");
        }
        requireSubscription(subscriptionId);
        return memberDao.insert(member);
    }

    public Member getMember(Long subscriptionId, Long memberId) throws SQLException {
        validateIds(subscriptionId, memberId);
        return memberDao.findById(subscriptionId, memberId)
                .orElseThrow(() -> new NotFoundException("Member not found: " + memberId));
    }

    public List<Member> findBySubscriptionId(Long subscriptionId) throws SQLException {
        requireSubscription(subscriptionId);
        return memberDao.findBySubscriptionId(subscriptionId);
    }

    public Member updateMember(Long subscriptionId, Member member) throws SQLException {
        validateScope(subscriptionId, member);
        if (member.id() == null || member.id() <= 0) {
            throw new ValidationException("An updated member must have a positive id");
        }
        requireSubscription(subscriptionId);
        getMember(subscriptionId, member.id());
        if (!memberDao.update(subscriptionId, member)) {
            throw new NotFoundException("Member not found: " + member.id());
        }
        return getMember(subscriptionId, member.id());
    }

    public boolean deleteMember(Long subscriptionId, Long memberId) throws SQLException {
        getMember(subscriptionId, memberId);
        if (!memberDao.deleteById(subscriptionId, memberId)) {
            throw new NotFoundException("Member not found: " + memberId);
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

    private static void validateScope(Long subscriptionId, Member member) {
        if (member == null) {
            throw new ValidationException("Member is required");
        }
        if (subscriptionId == null || subscriptionId <= 0) {
            throw new ValidationException("subscriptionId must be a positive id");
        }
        if (!subscriptionId.equals(member.subscriptionId())) {
            throw new ValidationException("Member subscription does not match the requested scope");
        }
        if (member.name() == null || member.name().isBlank()) {
            throw new ValidationException("Member name must not be blank");
        }
    }

    private static void validateIds(Long subscriptionId, Long memberId) {
        if (subscriptionId == null || subscriptionId <= 0 || memberId == null || memberId <= 0) {
            throw new ValidationException("subscriptionId and memberId must be positive ids");
        }
    }
}
