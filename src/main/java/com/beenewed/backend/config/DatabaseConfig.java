package com.beenewed.backend.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class DatabaseConfig {
    private static final String DEFAULT_JDBC_URL = "jdbc:sqlite:beenewed.db";
    private static final String SCHEMA_RESOURCE = "/db/schema.sql";

    private final String jdbcUrl;

    public DatabaseConfig() {
        this(DEFAULT_JDBC_URL);
    }

    public DatabaseConfig(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        if (jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
    }

    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            return connection;
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /**
     * Initializes the schema and returns the live connection. For an in-memory
     * SQLite URL, keep this connection open because the database is connection-scoped.
     */
    public Connection initialize() throws SQLException, IOException {
        Connection connection = openConnection();
        try {
            executeSchema(connection);
            return connection;
        } catch (SQLException | IOException exception) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private void executeSchema(Connection connection) throws SQLException, IOException {
        String schema;
        try (InputStream input = DatabaseConfig.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing database schema resource: " + SCHEMA_RESOURCE);
            }
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Statement statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                String command = sql.trim();
                if (!command.isEmpty()) {
                    statement.execute(command);
                }
            }
        }
    }
}
