package com.beenewed.backend.dao;

import com.beenewed.backend.model.Member;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MemberDao {
    private final Connection connection;

    public MemberDao(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
    }

    public Member insert(Member member) throws SQLException {
        String sql = """
                INSERT INTO members (subscription_id, name, role_notes)
                VALUES (?, ?, ?)
                RETURNING *
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, member.subscriptionId());
            statement.setString(2, member.name());
            statement.setString(3, member.roleNotes());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Inserting member did not return a row");
                }
                return map(resultSet);
            }
        }
    }

    public Optional<Member> findById(Long subscriptionId, Long memberId) throws SQLException {
        String sql = """
                SELECT id, subscription_id, name, role_notes, created_at, updated_at
                FROM members
                WHERE subscription_id = ? AND id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(subscriptionId, "subscriptionId must not be null"));
            statement.setLong(2, Objects.requireNonNull(memberId, "memberId must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    public List<Member> findBySubscriptionId(Long subscriptionId) throws SQLException {
        String sql = """
                SELECT id, subscription_id, name, role_notes, created_at, updated_at
                FROM members
                WHERE subscription_id = ?
                ORDER BY name COLLATE NOCASE, id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(subscriptionId, "subscriptionId must not be null"));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Member> members = new ArrayList<>();
                while (resultSet.next()) {
                    members.add(map(resultSet));
                }
                return members;
            }
        }
    }

    public boolean update(Long subscriptionId, Member member) throws SQLException {
        String sql = """
                UPDATE members
                SET name = ?, role_notes = ?, updated_at = CURRENT_TIMESTAMP
                WHERE subscription_id = ? AND id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, member.name());
            statement.setString(2, member.roleNotes());
            statement.setLong(3, Objects.requireNonNull(subscriptionId, "subscriptionId must not be null"));
            statement.setLong(4, Objects.requireNonNull(member.id(), "id must not be null"));
            return statement.executeUpdate() == 1;
        }
    }

    public boolean deleteById(Long subscriptionId, Long memberId) throws SQLException {
        String sql = "DELETE FROM members WHERE subscription_id = ? AND id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Objects.requireNonNull(subscriptionId, "subscriptionId must not be null"));
            statement.setLong(2, Objects.requireNonNull(memberId, "memberId must not be null"));
            return statement.executeUpdate() == 1;
        }
    }

    private static Member map(ResultSet resultSet) throws SQLException {
        return new Member(
                resultSet.getLong("id"),
                resultSet.getLong("subscription_id"),
                resultSet.getString("name"),
                resultSet.getString("role_notes"),
                Timestamp.valueOf(resultSet.getString("created_at")).toLocalDateTime(),
                Timestamp.valueOf(resultSet.getString("updated_at")).toLocalDateTime());
    }
}
