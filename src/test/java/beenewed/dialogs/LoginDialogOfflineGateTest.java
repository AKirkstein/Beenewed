package beenewed.dialogs;

import beenewed.ui.Theme;
import com.beenewed.backend.model.AppPreferences;
import com.beenewed.backend.model.BillingCycle;
import com.beenewed.backend.model.Subscription;
import com.beenewed.backend.model.SubscriptionStatus;
import com.beenewed.supabase.LocalSnapshotCache;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseConfig;
import com.beenewed.supabase.SupabaseRestClient.UserDataSnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies LoginDialog's offline launch gate:
 * - No active session (or after Sign Out): offline banner shown, login/signup disabled with
 *   "Network required to sign in", and Continue Offline hidden/blocked.
 * - Active session present: Continue Offline (Read-Only) remains available while login/signup
 *   are gated offline, and opens strictly the active user's snapshot.
 * - Connectivity recovery: hides offline banner and re-enables online auth.
 */
class LoginDialogOfflineGateTest {

    private static UserDataSnapshot sampleSnapshot(String name) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 12, 0);
        Subscription sub = new Subscription(
                1L, name, "Entertainment", "television",
                new BigDecimal("549.00"), BillingCycle.MONTHLY,
                LocalDate.of(2026, 12, 1), SubscriptionStatus.ACTIVE, now, now);
        AppPreferences prefs = new AppPreferences(
                1L, "PHP", com.beenewed.backend.model.Theme.LIGHT, 7, true);
        return new UserDataSnapshot(List.of(sub), List.of(), List.of(), List.of(), List.of(), List.of(), prefs);
    }

    @Test
    void offlineWithoutActiveSessionStaysOnAuthScreenAndBlocksOfflineBypass(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a display to construct JDialog");

        LocalSnapshotCache cache = new LocalSnapshotCache(tempDir.resolve("signed-out-cache.db"));
        // Populate and then explicitly sign out
        cache.save("u1", "alice@example.com", sampleSnapshot("Netflix"));
        cache.clearSessionOnSignOut();

        SupabaseAuthService auth = new SupabaseAuthService(new SupabaseConfig("http://127.0.0.1:1", "test-key"));
        AtomicBoolean online = new AtomicBoolean(false);
        AtomicReference<LocalSnapshotCache.CachedSnapshot> opened = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(false);
                LoginDialog dialog = new LoginDialog(null, auth, () -> {}, cache, opened::set, online::get);
                try {
                    dialog.checkConnectivitySyncForTest();

                    assertTrue(dialog.isNetworkOffline(), "Dialog must enter offline state when unreachable");
                    assertTrue(dialog.isOfflineNoticeVisibleForTest(), "Offline banner must be visible");
                    assertTrue(dialog.getOfflineNoticeTextForTest().toLowerCase().contains("network required"),
                            "Offline banner must inform the user that network is required");
                    assertFalse(dialog.isSubmitEnabledForTest(), "Sign In must be disabled while offline");
                    assertEquals("Network required to sign in", dialog.getSubmitButtonTextForTest());
                    assertFalse(dialog.isOfflineContinueVisibleForTest(),
                            "Continue Offline must be hidden after Sign Out");
                    assertFalse(dialog.isOfflineContinueEnabledForTest(),
                            "Continue Offline must be disabled after Sign Out");

                    // Attempting offline open programmatically must not bypass auth or expose data
                    dialog.triggerOfflineOpenForTest();
                    assertFalse(dialog.isOfflineOpened(), "Signed-out user must not open offline cache");
                    assertNull(opened.get(), "No cached snapshot may be handed to Store after Sign Out");

                    // Switching to Create Account while offline must also disable submit
                    dialog.switchModeToSignUpForTest();
                    assertFalse(dialog.isSubmitEnabledForTest(), "Create Account must be disabled while offline");
                    assertTrue(dialog.getSubmitButtonTextForTest().toLowerCase().contains("network required"));

                    // Recovering connectivity re-enables online authentication
                    online.set(true);
                    dialog.checkConnectivitySyncForTest();
                    assertFalse(dialog.isNetworkOffline());
                    assertFalse(dialog.isOfflineNoticeVisibleForTest());
                    assertTrue(dialog.isSubmitEnabledForTest());
                } finally {
                    dialog.dispose();
                }
            });
        } finally {
            auth.close();
        }
    }

    @Test
    void offlineWithActiveSessionAllowsReadOnlyFallbackForActiveUser(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a display to construct JDialog");

        LocalSnapshotCache cache = new LocalSnapshotCache(tempDir.resolve("active-session-cache.db"));
        cache.save("u1", "alice@example.com", sampleSnapshot("Netflix"));

        SupabaseAuthService auth = new SupabaseAuthService(new SupabaseConfig("http://127.0.0.1:1", "test-key"));
        AtomicBoolean online = new AtomicBoolean(true);
        AtomicReference<LocalSnapshotCache.CachedSnapshot> opened = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(false);
                LoginDialog dialog = new LoginDialog(null, auth, () -> {}, cache, opened::set, online::get);
                try {
                    // While online with a cached snapshot, Continue Offline must stay hidden
                    dialog.checkConnectivitySyncForTest();
                    assertFalse(dialog.isNetworkOffline());
                    assertFalse(dialog.isOfflineContinueVisibleForTest(),
                            "Continue Offline (Read-Only) must be hidden when online");
                    assertFalse(dialog.isOfflineContinueEnabledForTest(),
                            "Continue Offline (Read-Only) must be disabled when online");

                    // When connectivity is lost, Continue Offline appears for the active session
                    online.set(false);
                    dialog.checkConnectivitySyncForTest();

                    assertTrue(dialog.isNetworkOffline());
                    assertTrue(dialog.isOfflineNoticeVisibleForTest());
                    assertFalse(dialog.isSubmitEnabledForTest(), "Online Sign In must remain disabled offline");
                    assertEquals("Network required to sign in", dialog.getSubmitButtonTextForTest());

                    assertTrue(dialog.isOfflineContinueVisibleForTest(),
                            "Active session must show Continue Offline (Read-Only) when offline");
                    assertTrue(dialog.isOfflineContinueEnabledForTest(),
                            "Active session must enable Continue Offline (Read-Only) when offline");

                    // Recovering connectivity hides Continue Offline again
                    online.set(true);
                    dialog.checkConnectivitySyncForTest();
                    assertFalse(dialog.isNetworkOffline());
                    assertFalse(dialog.isOfflineContinueVisibleForTest(),
                            "Continue Offline (Read-Only) must hide again once connectivity recovers");

                    // Going offline again allows opening the active user's read-only snapshot
                    online.set(false);
                    dialog.checkConnectivitySyncForTest();
                    dialog.triggerOfflineOpenForTest();
                    assertTrue(dialog.isOfflineOpened(), "Active session should open read-only fallback");
                    assertNotNull(opened.get());
                    assertEquals("u1", opened.get().userId());
                    assertEquals("alice@example.com", opened.get().email());
                } finally {
                    dialog.dispose();
                }
            });
        } finally {
            auth.close();
        }
    }
}
