package com.beenewed.backend.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record UsageLog(
        Long id,
        Long subscriptionId,
        LocalDate usageDate,
        Double quantity,
        String unit,
        String notes,
        LocalDateTime createdAt) {
}
