package com.beenewed.backend.model;

public record AppPreferences(
        Long id,
        String currency,
        Theme theme,
        int reminderLeadDays,
        boolean showOverdueReminders) {
}
