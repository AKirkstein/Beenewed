package com.beenewed.backend.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PriceHistory(
        Long id,
        Long subscriptionId,
        BigDecimal previousPrice,
        BigDecimal newPrice,
        LocalDate effectiveDate,
        String reason,
        LocalDateTime createdAt) {
}
