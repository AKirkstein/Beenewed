package beenewed;

import beenewed.model.Store;
import beenewed.ui.ProfileMenu;
import beenewed.ui.Theme;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseConfig;
import com.beenewed.supabase.SupabaseRestClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the post-login {@link App} construction (header/profile icon)
 * and {@link ProfileMenu} accessibility setup.
 */
class AppHeaderAndProfileTest {

    @Test
    void profileMenuConstructsCleanlyInLightAndDarkThemes() throws Exception {
        for (boolean dark : new boolean[]{false, true}) {
            AtomicReference<ProfileMenu> ref = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(dark);
                ref.set(new ProfileMenu(
                        "kirk@example.com",
                        Instant.now().minusSeconds(300),
                        dark,
                        new ProfileMenu.Actions(() -> {}, () -> {}, () -> {}, () -> {})));
            });
            ProfileMenu menu = ref.get();
            assertNotNull(menu);
            assertTrue(menu.getComponentCount() >= 6);
            AccessibleContext headerCtx = menu.getComponent(0).getAccessibleContext();
            assertNotNull(headerCtx);
            assertEquals("Signed in as kirk@example.com", headerCtx.getAccessibleName());
        }
    }

    @Test
    void postLoginAppAndDashboardLoadWithoutAccessibleContextNpe() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a display to construct JFrame");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleMockSupabase);
        server.start();

        SupabaseAuthService auth = null;
        AtomicReference<App> appRef = new AtomicReference<>();
        try {
            SupabaseConfig config = new SupabaseConfig(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-anon-key");
            auth = new SupabaseAuthService(config);
            SupabaseRestClient rest = new SupabaseRestClient(config, auth);

            auth.signIn("kirk@example.com", "Passw0rd!");
            Store.initialize(rest);

            SupabaseAuthService finalAuth = auth;
            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(Store.get().getTheme() == com.beenewed.backend.model.Theme.DARK);
                appRef.set(new App(finalAuth, rest));
            });

            App app = appRef.get();
            assertNotNull(app);
            assertEquals("kirk@example.com", app.getCurrentUserEmail());

            Component profileComp = findByAccessibleName(app.getContentPane(), "Account menu");
            assertNotNull(profileComp, "Header should contain the accessible Account menu profile button");
            AccessibleContext ctx = profileComp.getAccessibleContext();
            assertNotNull(ctx);
            assertEquals(AccessibleRole.PUSH_BUTTON, ctx.getAccessibleRole());
            assertTrue(ctx.getAccessibleDescription().contains("kirk@example.com"));
        } finally {
            App app = appRef.get();
            if (app != null) {
                SwingUtilities.invokeAndWait(app::dispose);
            }
            Store.reset();
            if (auth != null) {
                auth.signOutAndWait(Duration.ofSeconds(2));
            }
            server.stop(0);
        }
    }

    private void handleMockSupabase(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        int status = 200;
        String response = "[]";
        if (path.equals("/auth/v1/token")) {
            response = "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\","
                    + "\"expires_in\":3600,\"token_type\":\"bearer\","
                    + "\"user\":{\"id\":\"user-123\",\"email\":\"kirk@example.com\"}}";
        } else if (path.equals("/auth/v1/logout")) {
            status = 204;
            response = "";
        } else if (path.equals("/rest/v1/app_preferences")) {
            response = "[{\"id\":1,\"user_id\":\"user-123\",\"currency\":\"PHP\","
                    + "\"theme\":\"LIGHT\",\"reminder_lead_days\":7,\"show_overdue_reminders\":true}]";
        }

        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    private static Component findByAccessibleName(Component root, String name) {
        AccessibleContext ctx = root.getAccessibleContext();
        if (ctx != null && name.equals(ctx.getAccessibleName())) {
            return root;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = findByAccessibleName(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
