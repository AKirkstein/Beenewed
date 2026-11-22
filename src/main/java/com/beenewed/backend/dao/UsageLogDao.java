package com.beenewed.backend.dao;

import com.beenewed.backend.model.UsageLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class UsageLogDao {
    private final Connection connection;

    public UsageLogDao(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    public UsageLog insert(UsageLog usageLog) throws SQLException {
        String sql = """
                INSERT INTO usage_logs (subscription_id, usage_date, quantity, unit, notes)
                VALUES (?, ?, ?, ?, ?)
                RETURNING *
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, usageLog.subscriptionId());
            statement.setString(2, usageLog.usageDate().toString());
            setNullableDouble(statement, 3, usageLog.quantity());
            statement.setString(4, usageLog.unit());
            statement.setString(5, usageLog.notes());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Inserting usage log did not return a row");
                }
                return map(resultSet);
            }
        }
    }

    public Optional<UsageLog> findById(Long subscriptionId, Long usageLogId) throws SQLException {
        String sql = """
                SELECT id, subscription_id, usage_date, quantity, unit, notes, created_at
                FROM usage_logs
                WHERE subscription_id = ? AND id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(subscriptionId, "subscriptionId must not be null"));
            statement.setLong(2, Objects.requireNonNull(usageLogId, "usageLogId must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    public List<UsageLog> findBySubscriptionId(Long subscriptionId) throws SQLException {
        String sql = """
                SELECT id, subscription_id, usage_date, quantity, unit, notes, created_at
                FROM usage_logs
                WHERE subscription_id = ?
                ORDER BY usage_date DESC, id DESC
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(subscriptionId, "subscriptionId must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<UsageLog> usageLogs = new ArrayList<>();
                while (resultSet.next()) {
                    usageLogs.add(map(resultSet));
                }
                return usageLogs;
            }
        }
    }

    public boolean update(Long subscriptionId, UsageLog usageLog) throws SQLException {
        String sql = """
                UPDATE usage_logs
                SET usage_date = ?, quantity = ?, unit = ?, notes = ?
                WHERE subscription_id = ? AND id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, usageLog.usageDate().toString());
            setNullableDouble(statement, 2, usageLog.quantity());
            statement.setString(3, usageLog.unit());
            statement.setString(4, usageLog.notes());
            statement.setLong(5, Objects.requireNonNull(subscriptionId, "subscriptionId must not be null"));
            statement.setLong(6, Objects.requireNonNull(usageLog.id(), "id must not be null"));
            return statement.executeUpdate() == 1;
        }
    }

    public boolean deleteById(Long subscriptionId, Long usageLogId) throws SQLException {
        String sql = "DELETE FROM usage_logs WHERE subscription_id = ? AND id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(subscriptionId, "subscriptionId must not be null"));
            statement.setLong(2, Objects.requireNonNull(usageLogId, "usageLogId must not be null"));
            return statement.executeUpdate() == 1;
        }
    }

    private static UsageLog map(ResultSet resultSet) throws SQLException {
        Object quantityValue = resultSet.getObject("quantity");
        Double quantity = quantityValue == null ? null : ((Number) quantityValue).doubleValue();
        return new UsageLog(
                resultSet.getLong("id"),
                resultSet.getLong("subscription_id"),
                LocalDate.parse(resultSet.getString("usage_date")),
                quantity,
                resultSet.getString("unit"),
                resultSet.getString("notes"),
                Timestamp.valueOf(resultSet.getString("created_at")).toLocalDateTime());
    }

    private static void setNullableDouble(
            PreparedStatement statement,
            int index,
            Double value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.REAL);
        } else {
            statement.setDouble(index, value);
        }
    }
}
