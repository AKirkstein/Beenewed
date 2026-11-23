package beenewed;

import beenewed.model.Models;
import beenewed.model.Store;
import beenewed.ui.Icons;
import beenewed.ui.Theme;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.supabase.LocalSnapshotCache;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseConfig;
import com.beenewed.supabase.SupabaseException;
import com.beenewed.supabase.SupabaseRestClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end security tests for offline session privacy:
 * 1. Explicit Sign Out clears SQLite offline snapshots & active session state.
 * 2. Closing without Sign Out preserves active-session read-only fallback offline.
 * 3. Store offline fallback never leaks another user's cached snapshot across accounts.
 */
class OfflineSessionSecurityTest {

    private HttpServer server;
    private SupabaseAuthService auth;
    private SupabaseRestClient rest;

    @AfterEach
    void cleanup() {
        Store.reset();
        if (auth != null) {
            auth.signOutAndWait(Duration.ofSeconds(1));
            auth.close();
        }
        if (rest != null) {
            rest.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signOutPurgesLocalCacheAndInvalidatesOfflineRelaunch(@TempDir Path tempDir) throws Exception {
        LocalSnapshotCache cache = new LocalSnapshotCache(tempDir.resolve("signout-security.db"));
        startMockServer();

        SupabaseConfig cfg = new SupabaseConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-anon-key");
        auth = new SupabaseAuthService(cfg);
        auth.signIn("alice@example.com", "Passw0rd!");
        rest = new SupabaseRestClient(cfg, auth);

        Store st = Store.initialize(rest, cache, auth.getUserId(), auth.getEmail());
        assertEquals(1, st.subscriptions.size());
        assertTrue(cache.hasActiveSessionSnapshot(), "Online login must activate offline read-only cache");
        assertTrue(cache.hasAnySnapshot());

        if (!GraphicsEnvironment.isHeadless()) {
            AtomicReference<App> appRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(false);
                App app = new App(auth, rest, cache);
                appRef.set(app);
                // Execute the exact Sign-Out cleanup path used by App.logout()
                app.clearSessionForSignOut();
                app.dispose();
            });
        } else {
            cache.clearSessionOnSignOut();
            auth.signOut();
            Store.reset();
        }

        assertFalse(auth.isAuthenticated(), "Sign out must clear in-memory auth session");
        assertFalse(Store.isInitialized(), "Sign out must reset Store");
        assertFalse(cache.hasActiveSessionSnapshot(), "Sign out must invalidate active offline session");
        assertTrue(cache.loadActiveSessionSnapshot().isEmpty(), "No active session snapshot may remain after Sign Out");
        assertFalse(cache.hasAnySnapshot(), "Sign out must purge cached private snapshots from SQLite");
    }

    @Test
    void activeSessionWithoutSignOutStillSupportsReadOnlyOfflineFallback(@TempDir Path tempDir) throws Exception {
        LocalSnapshotCache cache = new LocalSnapshotCache(tempDir.resolve("active-offline.db"));
        startMockServer();

        SupabaseConfig cfg = new SupabaseConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-anon-key");
        auth = new SupabaseAuthService(cfg);
        auth.signIn("alice@example.com", "Passw0rd!");
        rest = new SupabaseRestClient(cfg, auth);

        Store.initialize(rest, cache, auth.getUserId(), auth.getEmail());
        assertTrue(cache.hasActiveSessionSnapshot());

        // Simulate closing the app normally without clicking Sign Out, then going offline
        auth.signOutAndWait(Duration.ofSeconds(1));
        Store.reset();
        server.stop(0);
        server = null;

        assertFalse(auth.isServerReachable(Duration.ofSeconds(1)));
        assertTrue(cache.hasActiveSessionSnapshot(), "Closing without Sign Out must retain active offline session");

        LocalSnapshotCache.CachedSnapshot active = cache.loadActiveSessionSnapshot().orElseThrow();
        Store offlineStore = Store.initializeOffline(rest, cache, active);
        assertTrue(offlineStore.isOffline());
        assertEquals("u-alice", offlineStore.getCachedUserId());
        assertEquals("alice@example.com", offlineStore.getCachedUserEmail());
        assertEquals(1, offlineStore.subscriptions.size());

        // Mutations remain strictly read-only offline
        Models.Subscription extra = new Models.Subscription(
                0, "Spotify", "Music", Icons.Type.MUSIC,
                149.00, Models.Cycle.MONTHLY, LocalDate.now().plusDays(15));
        assertThrows(ValidationException.class, () -> offlineStore.addSubscription(extra));
    }

    @Test
    void storeFallbackNeverLeaksAnotherUsersSnapshotAcrossAccounts(@TempDir Path tempDir) throws Exception {
        LocalSnapshotCache cache = new LocalSnapshotCache(tempDir.resolve("isolation.db"));
        startMockServer();

        SupabaseConfig cfg = new SupabaseConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-anon-key");
        auth = new SupabaseAuthService(cfg);
        auth.signIn("alice@example.com", "Passw0rd!");
        rest = new SupabaseRestClient(cfg, auth);

        // User A populates local cache
        Store.initialize(rest, cache, "u-alice", "alice@example.com");
        assertTrue(cache.hasAnySnapshot());
        Store.reset();

        // Network goes down; User B attempts to initialize with their own userId/email
        server.stop(0);
        server = null;

        assertThrows(SupabaseException.class,
                () -> Store.initialize(rest, cache, "u-bob", "bob@example.com"),
                "User B must never silently load User A's cached snapshot when offline");
    }

    private void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        int status = 200;
        String resp = "[]";
        if (path.equals("/auth/v1/token")) {
            resp = "{\"access_token\":\"tok\",\"refresh_token\":\"ref\",\"expires_in\":3600,"
                    + "\"user\":{\"id\":\"u-alice\",\"email\":\"alice@example.com\"}}";
        } else if (path.equals("/auth/v1/logout")) {
            status = 204;
            resp = "";
        } else if (path.equals("/rest/v1/app_preferences")) {
            resp = "[{\"id\":1,\"user_id\":\"u-alice\",\"currency\":\"PHP\",\"theme\":\"LIGHT\","
                    + "\"reminder_lead_days\":7,\"show_overdue_reminders\":true}]";
        } else if (path.equals("/rest/v1/subscriptions")) {
            resp = "[{\"id\":1,\"name\":\"Netflix\",\"category\":\"Entertainment\",\"icon_key\":\"television\","
                    + "\"current_price\":549.00,\"billing_cycle\":\"MONTHLY\",\"next_renewal_date\":\""
                    + LocalDate.now().plusDays(5) + "\","
                    + "\"status\":\"ACTIVE\",\"created_at\":\"2026-01-01T00:00:00Z\",\"updated_at\":\"2026-01-01T00:00:00Z\"}]";
        }

        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }
}
