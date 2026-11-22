package com.beenewed.backend.model;

import java.time.LocalDateTime;

public record Member(
        Long id,
        Long subscriptionId,
        String name,
        String roleNotes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
