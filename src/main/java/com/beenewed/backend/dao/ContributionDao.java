package com.beenewed.backend.dao;

import com.beenewed.backend.model.Contribution;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ContributionDao {
    private final Connection connection;

    public ContributionDao(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    public Contribution insert(Contribution contribution) throws SQLException {
        String sql = """
                INSERT INTO contributions (member_id, payment_id, amount_received, date_received)
                VALUES (?, ?, ?, ?)
                RETURNING *
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, contribution.memberId());
            statement.setLong(2, contribution.paymentId());
            statement.setString(3, contribution.amountReceived().toPlainString());
            statement.setString(4, contribution.dateReceived().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Inserting contribution did not return a row");
                }
                return map(resultSet);
            }
        }
    }

    public Optional<Contribution> findById(Long paymentId, Long contributionId) throws SQLException {
        String sql = """
                SELECT id, member_id, payment_id, amount_received, date_received, created_at
                FROM contributions
                WHERE payment_id = ? AND id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(paymentId, "paymentId must not be null"));
            statement.setLong(2, Objects.requireNonNull(contributionId, "contributionId must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    public List<Contribution> findByPaymentId(Long paymentId) throws SQLException {
        String sql = """
                SELECT id, member_id, payment_id, amount_received, date_received, created_at
                FROM contributions
                WHERE payment_id = ?
                ORDER BY date_received, id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(paymentId, "paymentId must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Contribution> contributions = new ArrayList<>();
                while (resultSet.next()) {
                    contributions.add(map(resultSet));
                }
                return contributions;
            }
        }
    }

    public BigDecimal sumForPayment(Long paymentId) throws SQLException {
        String sql = "SELECT amount_received FROM contributions WHERE payment_id = ?";
        BigDecimal total = BigDecimal.ZERO;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(paymentId, "paymentId must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    total = total.add(new BigDecimal(resultSet.getString("amount_received")));
                }
            }
        }
        return total;
    }

    public boolean update(Long paymentId, Contribution contribution) throws SQLException {
        String sql = """
                UPDATE contributions
                SET member_id = ?, amount_received = ?, date_received = ?
                WHERE payment_id = ? AND id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, contribution.memberId());
            statement.setString(2, contribution.amountReceived().toPlainString());
            statement.setString(3, contribution.dateReceived().toString());
            statement.setLong(4, Objects.requireNonNull(paymentId, "paymentId must not be null"));
            statement.setLong(5, Objects.requireNonNull(contribution.id(), "id must not be null"));
            return statement.executeUpdate() == 1;
        }
    }

    public boolean deleteById(Long paymentId, Long contributionId) throws SQLException {
        String sql = "DELETE FROM contributions WHERE payment_id = ? AND id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(paymentId, "paymentId must not be null"));
            statement.setLong(2, Objects.requireNonNull(contributionId, "contributionId must not be null"));
            return statement.executeUpdate() == 1;
        }
    }

    private static Contribution map(ResultSet resultSet) throws SQLException {
        return new Contribution(
                resultSet.getLong("id"),
                resultSet.getLong("member_id"),
                resultSet.getLong("payment_id"),
                new BigDecimal(resultSet.getString("amount_received")),
                LocalDate.parse(resultSet.getString("date_received")),
                Timestamp.valueOf(resultSet.getString("created_at")).toLocalDateTime());
    }
}
