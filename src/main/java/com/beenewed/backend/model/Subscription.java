package com.beenewed.backend.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record Subscription(
        Long id,
        String name,
        String category,
        String iconKey,
        BigDecimal currentPrice,
        BillingCycle billingCycle,
        LocalDate nextRenewalDate,
        SubscriptionStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
