package com.beenewed.supabase;

import com.beenewed.supabase.SupabaseRestClient.UserDataSnapshot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistent SQLite local read-only cache storing user-scoped {@link UserDataSnapshot} payloads
 * so Beenewed can open and browse subscriptions, payments, splits, and analytics when offline.
 * <p>
 * Security: never stores authentication tokens, refresh tokens, or passwords.
 */
public final class LocalSnapshotCache {
    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS offline_snapshots (
                user_id TEXT PRIMARY KEY,
                email TEXT NOT NULL,
                saved_at TEXT NOT NULL,
                payload_json TEXT NOT NULL
            )
            """;

    private final Path dbPath;
    private final Gson gson;
    private final Object ioLock = new Object();

    public record CachedSnapshot(
            String userId,
            String email,
            Instant savedAt,
            UserDataSnapshot snapshot) {
    }

    public static LocalSnapshotCache defaultCache() {
        String home = System.getProperty("user.home", ".");
        return new LocalSnapshotCache(Path.of(home, ".beenewed", "offline-cache.db"));
    }

    public LocalSnapshotCache(Path dbPath) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null");
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new LocalDateAdapter())
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
                .create();
    }

    public Path getDbPath() {
        return dbPath;
    }

    /** Atomically saves the latest synced snapshot for a user (overwriting any previous snapshot). */
    public void save(String userId, String email, UserDataSnapshot snapshot) {
        if (userId == null || userId.isBlank() || snapshot == null) {
            return;
        }
        String cleanEmail = (email == null || email.isBlank()) ? "Signed-in account" : email.trim();
        String json = gson.toJson(snapshot);
        String now = Instant.now().toString();

        synchronized (ioLock) {
            try (Connection conn = openConnection()) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO offline_snapshots (user_id, email, saved_at, payload_json)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(user_id) DO UPDATE SET
                            email = excluded.email,
                            saved_at = excluded.saved_at,
                            payload_json = excluded.payload_json
                        """)) {
                    ps.setString(1, userId.trim());
                    ps.setString(2, cleanEmail);
                    ps.setString(3, now);
                    ps.setString(4, json);
                    ps.executeUpdate();
                }
            } catch (SQLException | IOException ignored) {
                // Local cache write failures must never break online usage.
            }
        }
    }

    public Optional<CachedSnapshot> loadByUserId(String userId) {
        if (userId == null || userId.isBlank() || !Files.isRegularFile(dbPath)) {
            return Optional.empty();
        }
        synchronized (ioLock) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT user_id, email, saved_at, payload_json FROM offline_snapshots WHERE user_id = ?")) {
                ps.setString(1, userId.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return parseRow(rs);
                    }
                }
            } catch (SQLException | IOException | RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<CachedSnapshot> loadByEmail(String email) {
        if (email == null || email.isBlank() || !Files.isRegularFile(dbPath)) {
            return Optional.empty();
        }
        String needle = email.trim().toLowerCase(Locale.ROOT);
        synchronized (ioLock) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT user_id, email, saved_at, payload_json FROM offline_snapshots "
                                 + "WHERE lower(email) = ? ORDER BY saved_at DESC LIMIT 1")) {
                ps.setString(1, needle);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return parseRow(rs);
                    }
                }
            } catch (SQLException | IOException | RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<CachedSnapshot> loadLatest() {
        if (!Files.isRegularFile(dbPath)) {
            return Optional.empty();
        }
        synchronized (ioLock) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT user_id, email, saved_at, payload_json FROM offline_snapshots "
                                 + "ORDER BY saved_at DESC LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return parseRow(rs);
                }
            } catch (SQLException | IOException | RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public boolean hasAnySnapshot() {
        return loadLatest().isPresent();
    }

    public void clearForUser(String userId) {
        if (userId == null || userId.isBlank() || !Files.isRegularFile(dbPath)) {
            return;
        }
        synchronized (ioLock) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM offline_snapshots WHERE user_id = ?")) {
                ps.setString(1, userId.trim());
                ps.executeUpdate();
            } catch (SQLException | IOException ignored) {
                // Best-effort cleanup
            }
        }
    }

    public void clearAll() {
        if (!Files.isRegularFile(dbPath)) {
            return;
        }
        synchronized (ioLock) {
            try (Connection conn = openConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM offline_snapshots")) {
                ps.executeUpdate();
            } catch (SQLException | IOException ignored) {
                // Best-effort cleanup
            }
        }
    }

    private Optional<CachedSnapshot> parseRow(ResultSet rs) throws SQLException {
        String uid = rs.getString("user_id");
        String mail = rs.getString("email");
        String savedAtRaw = rs.getString("saved_at");
        String payloadJson = rs.getString("payload_json");
        if (payloadJson == null || payloadJson.isBlank()) {
            return Optional.empty();
        }
        UserDataSnapshot snapshot = gson.fromJson(payloadJson, UserDataSnapshot.class);
        if (snapshot == null || snapshot.preferences() == null) {
            return Optional.empty();
        }
        Instant savedAt;
        try {
            savedAt = Instant.parse(savedAtRaw);
        } catch (RuntimeException e) {
            savedAt = Instant.now();
        }
        return Optional.of(new CachedSnapshot(uid, mail, savedAt, snapshot));
    }

    private Connection openConnection() throws SQLException, IOException {
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 3000");
            st.execute(CREATE_TABLE_SQL);
        }
        return conn;
    }

    private static final class LocalDateAdapter extends TypeAdapter<LocalDate> {
        @Override
        public void write(JsonWriter out, LocalDate value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(value.toString());
        }

        @Override
        public LocalDate read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return LocalDate.parse(in.nextString());
        }
    }

    private static final class LocalDateTimeAdapter extends TypeAdapter<LocalDateTime> {
        @Override
        public void write(JsonWriter out, LocalDateTime value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(value.toString());
        }

        @Override
        public LocalDateTime read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return LocalDateTime.parse(in.nextString());
        }
    }
}
