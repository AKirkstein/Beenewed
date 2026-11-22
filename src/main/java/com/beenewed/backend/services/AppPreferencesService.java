package com.beenewed.backend.services;

import com.beenewed.backend.dao.AppPreferencesDao;
import com.beenewed.backend.exception.NotFoundException;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.backend.model.AppPreferences;

import java.sql.SQLException;
import java.util.Objects;

public final class AppPreferencesService {
    private final AppPreferencesDao appPreferencesDao;

    public AppPreferencesService(AppPreferencesDao appPreferencesDao) {
        this.appPreferencesDao = Objects.requireNonNull(appPreferencesDao, "appPreferencesDao must not be null");
    }

    public AppPreferences get() throws SQLException {
        return appPreferencesDao.get()
                .orElseThrow(() -> new NotFoundException("Application preferences row is missing"));
    }

    public AppPreferences update(AppPreferences preferences) throws SQLException {
        validate(preferences);
        if (preferences.id() == null || preferences.id() != 1L) {
            throw new ValidationException("Application preferences id must be 1");
        }
        if (!appPreferencesDao.update(preferences)) {
            throw new NotFoundException("Application preferences row is missing");
        }
        return get();
    }

    private static void validate(AppPreferences preferences) {
        if (preferences == null) {
            throw new ValidationException("Application preferences are required");
        }
        if (preferences.currency() == null || preferences.currency().isBlank()) {
            throw new ValidationException("Currency must not be blank");
        }
        if (preferences.theme() == null) {
            throw new ValidationException("Theme is required");
        }
        if (preferences.reminderLeadDays() < 0) {
            throw new ValidationException("Reminder lead days must not be negative");
        }
    }
}
