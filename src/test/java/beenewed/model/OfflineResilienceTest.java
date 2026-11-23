package beenewed.model;

import beenewed.App;
import beenewed.model.Models.*;
import beenewed.ui.Icons;
import beenewed.ui.Theme;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.supabase.LocalSnapshotCache;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseConfig;
import com.beenewed.supabase.SupabaseRestClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies offline SQLite cache fallback, read-only mutation guard, fetch debouncing,
 * connectivity banner visibility, and clean executor/listener teardown on dispose/close.
 */
class OfflineResilienceTest {

    private HttpServer server;
    private SupabaseAuthService auth;
    private SupabaseRestClient rest;
    private final AtomicInteger subscriptionGetCount = new AtomicInteger();

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
    void onlineSyncPopulatesCacheThenOfflineFallbackPreservesReadsAndBlocksWrites(@TempDir Path tempDir) throws Exception {
        LocalSnapshotCache cache = new LocalSnapshotCache(tempDir.resolve("cache.db"));
        startMockServer();

        SupabaseConfig cfg = new SupabaseConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-anon-key");
        auth = new SupabaseAuthService(cfg);
        auth.signIn("kirk@example.com", "Passw0rd!");
        rest = new SupabaseRestClient(cfg, auth);

        // 1. Online initialization fetches once and writes to SQLite cache
        Store st = Store.initialize(rest, cache, auth.getUserId(), auth.getEmail());
        assertFalse(st.isOffline());
        assertEquals(1, st.subscriptions.size());
        assertEquals(1, st.payments.size());
        assertTrue(cache.hasAnySnapshot());
        assertEquals(1, subscriptionGetCount.get());

        // 2. Debounced refreshIfStale() skips duplicate network requests inside the debounce window
        st.setRefreshDebounce(Duration.ofSeconds(30));
        assertTrue(st.refreshIfStale());
        assertTrue(st.refreshIfStale());
        assertEquals(1, subscriptionGetCount.get(), "Debounced refreshes must not issue duplicate API calls");

        // 3. Simulate network outage (stop mock server) -> forceRefresh falls back cleanly to offline read-only mode
        server.stop(0);
        server = null;

        boolean synced = st.forceRefresh();
        assertFalse(synced);
        assertTrue(st.isOffline(), "Store should enter offline mode when Supabase is unreachable");
        assertEquals(1, st.subscriptions.size(), "Cached subscriptions must remain intact offline");
        assertEquals(1, st.payments.size(), "Cached payments must remain intact offline");
        assertEquals(1, st.categoryDistribution().size(), "Analytics must still compute cleanly offline");
        assertEquals(3, st.forecastExpenses(3).months().size(), "Forecasts must still compute cleanly offline");

        // 4. Write mutations are blocked cleanly while offline
        Subscription newSub = new Subscription(0, "NewApp", "Other", Icons.Type.OTHER,
                199.00, Cycle.MONTHLY, LocalDate.now().plusDays(10));
        ValidationException readOnlyErr = assertThrows(ValidationException.class, () -> st.addSubscription(newSub));
        assertTrue(readOnlyErr.getMessage().toLowerCase().contains("offline"));

        // 5. Cold-start offline boot from SQLite cache works with 0 network calls
        Store.reset();
        LocalSnapshotCache.CachedSnapshot saved = cache.loadLatest().orElseThrow();
        Store offlineStore = Store.initializeOffline(rest, cache, saved);
        assertTrue(offlineStore.isOffline());
        assertEquals("kirk@example.com", offlineStore.getCachedUserEmail());
        assertEquals(1, offlineStore.subscriptions.size());
        assertEquals(1, offlineStore.payments.size());

        // 6. UI offline banner is visible and App.dispose() unregisters its Store listener cleanly
        if (!GraphicsEnvironment.isHeadless()) {
            AtomicReference<App> appRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(false);
                App app = new App(auth, rest, cache);
                appRef.set(app);
                assertTrue(isOfflineBannerVisible(app), "Offline banner must be visible in offline read-only mode");
                assertEquals(1, Store.get().listenerCount());
                app.dispose();
                assertEquals(0, Store.get().listenerCount(), "App.dispose() must detach Store listener cleanly");
            });
        }

        // 7. AutoCloseable executors terminate cleanly
        auth.close();
        rest.close();
        assertTrue(auth.isClosed());
        assertTrue(rest.isClosed());
    }

    private static boolean isOfflineBannerVisible(App app) {
        try {
            Method m = App.class.getDeclaredMethod("isOfflineBannerVisibleForTest");
            m.setAccessible(true);
            return (boolean) m.invoke(app);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
            resp = "{\"access_token\":\"tok\",\"refresh_token\":\"ref\",\"expires_in\":3600,\"user\":{\"id\":\"u1\",\"email\":\"kirk@example.com\"}}";
        } else if (path.equals("/auth/v1/logout")) {
            status = 204;
            resp = "";
        } else if (path.equals("/rest/v1/app_preferences")) {
            resp = "[{\"id\":1,\"user_id\":\"u1\",\"currency\":\"PHP\",\"theme\":\"LIGHT\",\"reminder_lead_days\":7,\"show_overdue_reminders\":true}]";
        } else if (path.equals("/rest/v1/subscriptions")) {
            subscriptionGetCount.incrementAndGet();
            resp = "[{\"id\":1,\"name\":\"Netflix\",\"category\":\"Entertainment\",\"icon_key\":\"television\","
                    + "\"current_price\":549.00,\"billing_cycle\":\"MONTHLY\",\"next_renewal_date\":\""
                    + LocalDate.now().plusDays(5) + "\","
                    + "\"status\":\"ACTIVE\",\"created_at\":\"2026-01-01T00:00:00Z\",\"updated_at\":\"2026-01-01T00:00:00Z\"}]";
        } else if (path.equals("/rest/v1/payments")) {
            resp = "[{\"id\":10,\"subscription_id\":1,\"payment_date\":\"" + LocalDate.now() + "\",\"amount_paid\":549.00,"
                    + "\"renewal_period\":\"November 2026\",\"payment_method\":\"GCash\",\"notes\":null,"
                    + "\"created_at\":\"2026-11-23T00:00:00Z\"}]";
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
