package com.beenewed.backend.dao;

import com.beenewed.backend.model.Payment;
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

public final class PaymentDao {
    private final Connection connection;

    public PaymentDao(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    public Payment insert(Payment payment) throws SQLException {
        String sql = """
                INSERT INTO payments (
                    subscription_id, payment_date, amount_paid, renewal_period,
                    payment_method, notes
                )
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING *
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, payment.subscriptionId());
            statement.setString(2, payment.paymentDate().toString());
            statement.setString(3, payment.amountPaid().toPlainString());
            statement.setString(4, payment.renewalPeriod());
            statement.setString(5, payment.paymentMethod());
            statement.setString(6, payment.notes());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Inserting payment did not return a row");
                }
                return map(resultSet);
            }
        }
    }

    public Optional<Payment> findById(Long id) throws SQLException {
        String sql = "SELECT p.* FROM payments p WHERE p.id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(id, "id must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    public List<Payment> findAll() throws SQLException {
        String sql = "SELECT p.* FROM payments p ORDER BY p.payment_date DESC, p.id DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return mapAll(resultSet);
        }
    }

    public List<Payment> findBySubscriptionId(Long subscriptionId) throws SQLException {
        String sql = """
                SELECT p.* FROM payments p
                WHERE p.subscription_id = ?
                ORDER BY p.payment_date DESC, p.id DESC
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(subscriptionId, "subscriptionId must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        }
    }

    public List<Payment> findByDateRange(LocalDate startDate, LocalDate endDate) throws SQLException {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        String sql = """
                SELECT p.* FROM payments p
                WHERE p.payment_date >= ? AND p.payment_date <= ?
                ORDER BY p.payment_date DESC, p.id DESC
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, startDate.toString());
            statement.setString(2, endDate.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        }
    }

    public List<Payment> findFiltered(
            Long subscriptionId,
            LocalDate startDate,
            LocalDate endDate,
            SubscriptionStatus subscriptionStatus,
            String subscriptionSearch) throws SQLException {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        String sql = """
                SELECT p.*
                FROM payments p
                JOIN subscriptions s ON s.id = p.subscription_id
                WHERE (? IS NULL OR p.subscription_id = ?)
                  AND (? IS NULL OR p.payment_date >= ?)
                  AND (? IS NULL OR p.payment_date <= ?)
                  AND (? IS NULL OR s.status = ?)
                  AND (? IS NULL OR lower(s.name) LIKE lower(?) ESCAPE '\\')
                ORDER BY p.payment_date DESC, p.id DESC
                """;
        String status = subscriptionStatus == null ? null : subscriptionStatus.name();
        String search = normalize(subscriptionSearch);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setNullableLong(statement, 1, subscriptionId);
            setNullableLong(statement, 2, subscriptionId);
            setNullableDate(statement, 3, startDate);
            setNullableDate(statement, 4, startDate);
            setNullableDate(statement, 5, endDate);
            setNullableDate(statement, 6, endDate);
            setNullableString(statement, 7, status);
            setNullableString(statement, 8, status);
            setNullableString(statement, 9, search);
            setNullableString(statement, 10, containsPattern(search));
            try (ResultSet resultSet = statement.executeQuery()) {
                return mapAll(resultSet);
            }
        }
    }

    public boolean update(Payment payment) throws SQLException {
        String sql = """
                UPDATE payments
                SET subscription_id = ?, payment_date = ?, amount_paid = ?,
                    renewal_period = ?, payment_method = ?, notes = ?
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, payment.subscriptionId());
            statement.setString(2, payment.paymentDate().toString());
            statement.setString(3, payment.amountPaid().toPlainString());
            statement.setString(4, payment.renewalPeriod());
            statement.setString(5, payment.paymentMethod());
            statement.setString(6, payment.notes());
            statement.setLong(7, Objects.requireNonNull(payment.id(), "id must not be null"));
            return statement.executeUpdate() == 1;
        }
    }

    public boolean deleteById(Long id) throws SQLException {
        String sql = "DELETE FROM payments WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(id, "id must not be null"));
            return statement.executeUpdate() == 1;
        }
    }

    private static Payment map(ResultSet resultSet) throws SQLException {
        return new Payment(
                resultSet.getLong("id"),
                resultSet.getLong("subscription_id"),
                LocalDate.parse(resultSet.getString("payment_date")),
                new BigDecimal(resultSet.getString("amount_paid")),
                resultSet.getString("renewal_period"),
                resultSet.getString("payment_method"),
                resultSet.getString("notes"),
                Timestamp.valueOf(resultSet.getString("created_at")).toLocalDateTime());
    }

    private static List<Payment> mapAll(ResultSet resultSet) throws SQLException {
        List<Payment> payments = new ArrayList<>();
        while (resultSet.next()) {
            payments.add(map(resultSet));
        }
        return payments;
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

    private static void setNullableLong(
            PreparedStatement statement,
            int index,
            Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableDate(
            PreparedStatement statement,
            int index,
            LocalDate value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.toString());
        }
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
