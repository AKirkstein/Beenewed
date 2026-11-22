package com.beenewed.backend.dao;

import com.beenewed.backend.model.BillingCycle;
import com.beenewed.backend.model.Subscription;
import com.beenewed.backend.model.SubscriptionStatus;

import java.math.BigDecimal;
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

public final class SubscriptionDao {
    private final Connection connection;

    public SubscriptionDao(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    public Subscription insert(Subscription subscription) throws SQLException {
        String sql = """
                INSERT INTO subscriptions (
                    name, category, icon_key, current_price, billing_cycle,
                    next_renewal_date, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, subscription.name());
            statement.setString(2, subscription.category());
            statement.setString(3, subscription.iconKey());
            statement.setString(4, subscription.currentPrice().toPlainString());
            statement.setString(5, subscription.billingCycle().name());
            statement.setString(6, subscription.nextRenewalDate().toString());
            statement.setString(7, subscription.status().name());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Inserting subscription did not return a row");
                }
                return map(resultSet);
            }
        }
    }

    public Optional<Subscription> findById(Long id) throws SQLException {
        String sql = """
                SELECT id, name, category, icon_key, current_price, billing_cycle,
                       next_renewal_date, status, created_at, updated_at
                FROM subscriptions
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(id, "id must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    public List<Subscription> findAll() throws SQLException {
        String sql = """
                SELECT id, name, category, icon_key, current_price, billing_cycle,
                       next_renewal_date, status, created_at, updated_at
                FROM subscriptions
                ORDER BY name COLLATE NOCASE, id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return mapAll(resultSet);
        }
    }

    public List<Subscription> findFiltered(
            String search,
            String category,
            SubscriptionStatus status) throws SQLException {
        String sql = """
                SELECT s.id, s.name, s.category, s.icon_key, s.current_price,
                       s.billing_cycle, s.next_renewal_date, s.status,
                       s.created_at, s.updated_at
                FROM subscriptions s
                WHERE (? IS NULL OR lower(s.name) LIKE lower(?) ESCAPE '\\')
                  AND (? IS NULL OR s.category = ?)
                  AND (? IS NULL OR s.status = ?)
                ORDER BY s.name COLLATE NOCASE, s.id
                """;
        String normalizedSearch = normalize(search);
        String normalizedCategory = normalize(category);
        String statusValue = status == null ? null : status.name();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setNullableString(statement, 1, normalizedSearch);
            setNullableString(statement, 2, containsPattern(normalizedSearch));
            setNullableString(statement, 3, normalizedCategory);
            setNullableString(statement, 4, normalizedCategory);
            setNullableString(statement, 5, statusValue);
            setNullableString(statement, 6, statusValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        }
    }

    public List<Subscription> findActiveSubscriptions() throws SQLException {
        String sql = """
                SELECT id, name, category, icon_key, current_price, billing_cycle,
                       next_renewal_date, status, created_at, updated_at
                FROM subscriptions
                WHERE status = ?
                ORDER BY next_renewal_date, name COLLATE NOCASE, id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SubscriptionStatus.ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        }
    }

    public boolean update(Subscription subscription) throws SQLException {
        String sql = """
                UPDATE subscriptions
                SET name = ?, category = ?, icon_key = ?, current_price = ?,
                    billing_cycle = ?, next_renewal_date = ?, status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, subscription.name());
            statement.setString(2, subscription.category());
            statement.setString(3, subscription.iconKey());
            statement.setString(4, subscription.currentPrice().toPlainString());
            statement.setString(5, subscription.billingCycle().name());
            statement.setString(6, subscription.nextRenewalDate().toString());
            statement.setString(7, subscription.status().name());
            statement.setLong(8, Objects.requireNonNull(subscription.id(), "id must not be null"));
            return statement.executeUpdate() == 1;
        }
    }

    public boolean deleteById(Long id) throws SQLException {
        String sql = "DELETE FROM subscriptions WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(id, "id must not be null"));
            return statement.executeUpdate() == 1;
        }
    }

    private static Subscription map(ResultSet resultSet) throws SQLException {
        return new Subscription(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("category"),
                resultSet.getString("icon_key"),
                new BigDecimal(resultSet.getString("current_price")),
                BillingCycle.valueOf(resultSet.getString("billing_cycle")),
                LocalDate.parse(resultSet.getString("next_renewal_date")),
                SubscriptionStatus.valueOf(resultSet.getString("status")),
                Timestamp.valueOf(resultSet.getString("created_at")).toLocalDateTime(),
                Timestamp.valueOf(resultSet.getString("updated_at")).toLocalDateTime());
    }

    private static List<Subscription> mapAll(ResultSet resultSet) throws SQLException {
        List<Subscription> subscriptions = new ArrayList<>();
        while (resultSet.next()) {
            subscriptions.add(map(resultSet));
        }
        return subscriptions;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String containsPattern(String value) {
        if (value == null) {
            return null;
        }
        String escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static void setNullableString(
            PreparedStatement statement,
            int index,
            String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
