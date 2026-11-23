package beenewed.dialogs;

import beenewed.App;
import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.Theme;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseConfig;
import com.beenewed.supabase.SupabaseRestClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Renewal Reminders dialog actions ("View" only, no "Record Payment"),
 * session-scoped viewed acknowledgment across refreshes/re-opens, notification badge
 * decrementing/hiding at 0, and re-surfacing on new billing cycles or state changes.
 */
class RemindersDialogTest {

    private volatile LocalDate sub1NextRenewal;
    private volatile LocalDate sub2NextRenewal;

    @Test
    void reminderRowsOnlyHaveViewAndViewingDismissesAndDecrementsBadge() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a display to construct JFrame/JDialog");

        sub1NextRenewal = LocalDate.now().plusDays(3);  // upcoming within 7 days
        sub2NextRenewal = LocalDate.now().minusDays(2); // overdue

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        SupabaseAuthService auth = null;
        AtomicReference<App> appRef = new AtomicReference<>();
        try {
            SupabaseConfig cfg = new SupabaseConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-anon-key");
            auth = new SupabaseAuthService(cfg);
            auth.signIn("kirk@example.com", "Passw0rd!");
            SupabaseRestClient rest = new SupabaseRestClient(cfg, auth);
            Store.initialize(rest);

            Store st = Store.get();
            assertEquals(1, st.upcomingReminders().size());
            assertEquals(1, st.overdue().size());
            assertEquals(2, st.activeReminderCount());

            SupabaseAuthService finalAuth = auth;
            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(false);
                App app = new App(finalAuth, rest);
                appRef.set(app);

                JLabel badge = bellBadge(app);
                assertTrue(badge.isVisible());
                assertEquals("2", badge.getText());

                // 1. Open RemindersDialog: confirm no "Record Payment" buttons, only "View" links
                RemindersDialog firstDialog = new RemindersDialog(app);
                List<AbstractButton> buttons = collectButtons(firstDialog.body);
                assertTrue(buttons.stream().noneMatch(b -> "Record Payment".equals(b.getText())),
                        "Reminder rows must not contain Record Payment buttons");
                List<AbstractButton> viewButtons = buttons.stream()
                        .filter(b -> "View".equals(b.getText()))
                        .toList();
                assertEquals(2, viewButtons.size(), "Expected View button for 1 upcoming + 1 overdue reminder");

                // 2. Click "View" on the first reminder (upcoming) -> dismissed & badge drops 2 -> 1
                viewButtons.get(0).doClick();
                assertEquals(0, st.upcomingReminders().size());
                assertEquals(1, st.overdue().size());
                assertEquals(1, st.activeReminderCount());
                assertTrue(badge.isVisible());
                assertEquals("1", badge.getText());
            });

            // 3. Refresh from server & reopen RemindersDialog -> viewed state persists in session
            st.refresh();
            assertEquals(0, st.upcomingReminders().size());
            assertEquals(1, st.overdue().size());
            assertEquals(1, st.activeReminderCount());

            SwingUtilities.invokeAndWait(() -> {
                App app = appRef.get();
                JLabel badge = bellBadge(app);

                RemindersDialog secondDialog = new RemindersDialog(app);
                List<AbstractButton> viewButtons = collectButtons(secondDialog.body).stream()
                        .filter(b -> "View".equals(b.getText()))
                        .toList();
                assertEquals(1, viewButtons.size(), "Only the remaining unviewed overdue reminder should appear");

                // 4. Click "View" on the last remaining reminder -> badge hides cleanly at 0
                viewButtons.get(0).doClick();
                assertEquals(0, st.upcomingReminders().size());
                assertEquals(0, st.overdue().size());
                assertEquals(0, st.activeReminderCount());
                assertFalse(badge.isVisible(), "Notification badge must hide when all reminders are viewed");
                assertEquals("", badge.getText());

                // Reopening dialog now shows 0 View buttons
                RemindersDialog emptyDialog = new RemindersDialog(app);
                try {
                    long remainingViews = collectButtons(emptyDialog.body).stream()
                            .filter(b -> "View".equals(b.getText()))
                            .count();
                    assertEquals(0, remainingViews);
                } finally {
                    emptyDialog.dispose();
                }
            });

            // 5. If a previously viewed subscription advances to a new due date or transitions to OVERDUE,
            //    the new cycle/state reminder is treated as unviewed again!
            Subscription sub1 = st.getSubscription(1);
            // Sub1 was viewed as UPCOMING for (today + 3). If it becomes OVERDUE (e.g., due date passes),
            // OVERDUE kind is not viewed:
            assertFalse(st.isReminderViewed(sub1, Store.ReminderKind.OVERDUE));

            // Advance sub1 to a new due date within the upcoming window (today + 5) -> new cycle is unviewed!
            sub1NextRenewal = LocalDate.now().plusDays(5);
            st.refresh();
            assertEquals(1, st.upcomingReminders().size());
            assertEquals(1, st.activeReminderCount());
        } finally {
            App app = appRef.get();
            if (app != null) {
                SwingUtilities.invokeAndWait(app::dispose);
            }
            Store.reset();
            if (auth != null) auth.signOutAndWait(Duration.ofSeconds(2));
            server.stop(0);
        }
    }

    private static JLabel bellBadge(App app) {
        try {
            Method m = App.class.getDeclaredMethod("bellBadgeForTest");
            m.setAccessible(true);
            return (JLabel) m.invoke(app);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<AbstractButton> collectButtons(Container root) {
        List<AbstractButton> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton b) {
                out.add(b);
            }
            if (c instanceof Container ct) {
                out.addAll(collectButtons(ct));
            }
        }
        return out;
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
            resp = "["
                    + subJson(1, "Netflix", 549.00, sub1NextRenewal) + ","
                    + subJson(2, "Spotify", 149.00, sub2NextRenewal)
                    + "]";
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

    private static String subJson(int id, String name, double price, LocalDate next) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("name", name);
        obj.addProperty("category", "Entertainment");
        obj.addProperty("icon_key", "television");
        obj.addProperty("current_price", price);
        obj.addProperty("billing_cycle", "MONTHLY");
        obj.addProperty("next_renewal_date", next.toString());
        obj.addProperty("status", "ACTIVE");
        obj.addProperty("created_at", "2026-01-01T00:00:00Z");
        obj.addProperty("updated_at", "2026-01-01T00:00:00Z");
        return obj.toString();
    }
}
