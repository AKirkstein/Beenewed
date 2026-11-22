package com.beenewed.backend.dao;

import com.beenewed.backend.model.PriceHistory;

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

public final class PriceHistoryDao {
    private final Connection connection;

    public PriceHistoryDao(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    public PriceHistory insert(PriceHistory priceHistory) throws SQLException {
        String sql = """
                INSERT INTO price_history (
                    subscription_id, previous_price, new_price, effective_date, reason
                )
                VALUES (?, ?, ?, ?, ?)
                RETURNING *
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, priceHistory.subscriptionId());
            statement.setString(2, priceHistory.previousPrice().toPlainString());
            statement.setString(3, priceHistory.newPrice().toPlainString());
            statement.setString(4, priceHistory.effectiveDate().toString());
            statement.setString(5, priceHistory.reason());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Inserting price history did not return a row");
                }
                return map(resultSet);
            }
        }
    }

    public List<PriceHistory> findBySubscriptionId(Long subscriptionId) throws SQLException {
        String sql = """
                SELECT id, subscription_id, previous_price, new_price,
                       effective_date, reason, created_at
                FROM price_history
                WHERE subscription_id = ?
                ORDER BY effective_date DESC, id DESC
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(subscriptionId, "subscriptionId must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PriceHistory> priceHistory = new ArrayList<>();
                while (resultSet.next()) {
                    priceHistory.add(map(resultSet));
                }
                return priceHistory;
            }
        }
    }

    private static PriceHistory map(ResultSet resultSet) throws SQLException {
        return new PriceHistory(
                resultSet.getLong("id"),
                resultSet.getLong("subscription_id"),
                new BigDecimal(resultSet.getString("previous_price")),
                new BigDecimal(resultSet.getString("new_price")),
                LocalDate.parse(resultSet.getString("effective_date")),
                resultSet.getString("reason"),
                Timestamp.valueOf(resultSet.getString("created_at")).toLocalDateTime());
    }
}
