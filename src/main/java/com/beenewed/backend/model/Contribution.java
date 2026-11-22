package com.beenewed.backend.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record Contribution(
        Long id,
        Long memberId,
        Long paymentId,
        BigDecimal amountReceived,
        LocalDate dateReceived,
        LocalDateTime createdAt) {
}
