package com.beenewed.backend.dao;

import com.beenewed.backend.model.AppPreferences;
import com.beenewed.backend.model.Theme;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class AppPreferencesDao {
    private final Connection connection;

    public AppPreferencesDao(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    public Optional<AppPreferences> get() throws SQLException {
        String sql = """
                SELECT id, currency, theme, reminder_lead_days, show_overdue_reminders
                FROM app_preferences
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, 1L);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AppPreferences(
                        resultSet.getLong("id"),
                        resultSet.getString("currency"),
                        Theme.valueOf(resultSet.getString("theme")),
                        resultSet.getInt("reminder_lead_days"),
                        resultSet.getInt("show_overdue_reminders") != 0));
            }
        }
    }

    public boolean update(AppPreferences preferences) throws SQLException {
        String sql = """
                UPDATE app_preferences
                SET currency = ?, theme = ?, reminder_lead_days = ?,
                    show_overdue_reminders = ?
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, preferences.currency());
            statement.setString(2, preferences.theme().name());
            statement.setInt(3, preferences.reminderLeadDays());
            statement.setInt(4, preferences.showOverdueReminders() ? 1 : 0);
            statement.setLong(5, 1L);
            return statement.executeUpdate() == 1;
        }
    }
}
