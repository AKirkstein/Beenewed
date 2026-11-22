package com.beenewed.backend.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record Payment(
        Long id,
        Long subscriptionId,
        LocalDate paymentDate,
        BigDecimal amountPaid,
        String renewalPeriod,
        String paymentMethod,
        String notes,
        LocalDateTime createdAt) {
}
