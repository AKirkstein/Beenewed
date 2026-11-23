package beenewed.model;

import beenewed.model.Models.*;
import beenewed.model.SplitAllocator.DistributionSummary;
import beenewed.model.SplitAllocator.SettlementStatus;
import beenewed.model.SplitAllocator.SplitMode;
import com.beenewed.backend.exception.ValidationException;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseConfig;
import com.beenewed.supabase.SupabaseRestClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end Store tests against an in-process mock Supabase PostgREST server verifying
 * duplicate payment prevention, amount alignment, immutable payment ledger, automatic
 * due-date advancement + overdue clearing, and family split persistence/distribution.
 */
class StorePhase2Test {

    private HttpServer server;
    private SupabaseAuthService auth;
    private final AtomicLong nextPaymentId = new AtomicLong(100);
    private final AtomicLong nextContribId = new AtomicLong(500);
    private final List<String> httpLog = new CopyOnWriteArrayList<>();

    private volatile LocalDate sub1NextRenewal;
    private volatile String member1Notes = null;
    private volatile String member2Notes = null;

    @BeforeEach
    void setUp() throws Exception {
        sub1NextRenewal = LocalDate.now().minusDays(5); // overdue active subscription
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        SupabaseConfig cfg = new SupabaseConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-anon-key");
        auth = new SupabaseAuthService(cfg);
        auth.signIn("kirk@example.com", "Passw0rd!");
        SupabaseRestClient rest = new SupabaseRestClient(cfg, auth);
        Store.initialize(rest);
        httpLog.clear();
    }

    @AfterEach
    void tearDown() {
        Store.reset();
        if (auth != null) {
            auth.signOutAndWait(Duration.ofSeconds(2));
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void recordPaymentAdvancesDueDateClearsOverdueAndBlocksDuplicatesAndMismatchedAmounts() {
        Store st = Store.get();
        Subscription netflix = st.getSubscription(1);
        LocalDate originalDue = netflix.nextRenewal;
        assertEquals(1, st.overdue().size(), "Overdue subscription should appear before payment");

        // Mismatched amount rejected before hitting Supabase
        Payment wrongAmount = new Payment(0, netflix, Store.today(), 100.00, "Current Cycle", "GCash", null);
        assertThrows(ValidationException.class, () -> st.recordPayment(wrongAmount));
        assertTrue(httpLog.isEmpty(), "Rejected payment must not send HTTP requests");

        // Valid full-price payment succeeds, persists payment + patches next_renewal_date
        Payment valid = new Payment(0, netflix, Store.today(), 549.00, "November 2026", "GCash", "Receipt #1");
        st.recordPayment(valid);

        assertEquals(originalDue.plusMonths(1), netflix.nextRenewal);
        assertEquals(originalDue.plusMonths(1), sub1NextRenewal);
        assertTrue(st.overdue().isEmpty(), "Overdue status should clear once the renewal period is settled");
        assertTrue(httpLog.stream().anyMatch(s -> s.startsWith("POST /rest/v1/payments")));
        assertTrue(httpLog.stream().anyMatch(s -> s.startsWith("PATCH /rest/v1/subscriptions")));

        // Duplicate period (even in alternate format) is rejected before hitting Supabase
        httpLog.clear();
        Payment dup = new Payment(0, netflix, Store.today(), 549.00, "Nov 2026", "Cash", null);
        assertThrows(ValidationException.class, () -> st.recordPayment(dup));
        assertTrue(httpLog.isEmpty());

        // Recorded payment ledger is immutable (no edit or delete)
        Payment recorded = st.paymentsFor(netflix).get(0);
        assertThrows(ValidationException.class, () -> st.updatePayment(recorded));
        assertThrows(ValidationException.class, () -> st.deletePayment(recorded));
    }

    @Test
    void cancelledSubscriptionCannotAcceptNewPayments() {
        Store st = Store.get();
        Subscription cancelled = st.getSubscription(3);
        assertEquals(Status.CANCELLED, cancelled.status);

        Payment attempt = new Payment(0, cancelled, Store.today(), 299.00, "November 2026", "Cash", null);
        assertThrows(ValidationException.class, () -> st.recordPayment(attempt));
        assertTrue(httpLog.isEmpty());
    }

    @Test
    void familySplitConfigurationValidatesTotalsAndUpdatesPayerDistribution() {
        Store st = Store.get();
        Subscription netflix = st.getSubscription(1); // 549.00, members Ana (10) & Ben (11)

        // Invalid custom split sum rejected
        Map<String, BigDecimal> badSplit = new LinkedHashMap<>();
        badSplit.put(SplitAllocator.OWNER_KEY, new BigDecimal("200.00"));
        badSplit.put(SplitAllocator.memberKey(10), new BigDecimal("200.00"));
        badSplit.put(SplitAllocator.memberKey(11), new BigDecimal("100.00"));
        assertThrows(ValidationException.class,
                () -> st.saveSplitConfiguration(netflix, SplitAllocator.OWNER_KEY, true, SplitMode.CUSTOM, badSplit));

        // Valid custom split persisted (Owner = 249, Ana = 150, Ben = 150)
        Map<String, BigDecimal> goodSplit = new LinkedHashMap<>();
        goodSplit.put(SplitAllocator.OWNER_KEY, new BigDecimal("249.00"));
        goodSplit.put(SplitAllocator.memberKey(10), new BigDecimal("150.00"));
        goodSplit.put(SplitAllocator.memberKey(11), new BigDecimal("150.00"));
        st.saveSplitConfiguration(netflix, SplitAllocator.OWNER_KEY, true, SplitMode.CUSTOM, goodSplit);

        // Recording full bill succeeds, and Group Head distribution shows Ana & Ben pending
        st.recordPayment(new Payment(0, netflix, Store.today(), 549.00, "November 2026", "GCash", null));
        Payment recorded = st.paymentsFor(netflix).get(0);
        DistributionSummary before = st.distributionFor(recorded);
        assertEquals(new BigDecimal("300.00"), before.totalToCollect());
        assertEquals(new BigDecimal("0.00"), before.totalCollected());
        assertEquals(new BigDecimal("300.00"), before.totalPending());

        // Ana pays her share -> Collected = 150, Pending = 150
        Member ana = st.membersFor(netflix).stream().filter(m -> m.id == 10).findFirst().orElseThrow();
        st.recordContribution(new Contribution(0, netflix, ana, recorded, 150.00, Store.today()));

        DistributionSummary after = st.distributionFor(recorded);
        assertEquals(new BigDecimal("150.00"), after.totalCollected());
        assertEquals(new BigDecimal("150.00"), after.totalPending());
        assertEquals(SettlementStatus.PAYER, after.rows().get(0).status());
        assertEquals(SettlementStatus.PAID, after.rows().get(1).status());
        assertEquals(SettlementStatus.PENDING, after.rows().get(2).status());

        // Recording next period using owner's designated split share (249.00) is also accepted
        assertDoesNotThrow(() -> st.recordPayment(
                new Payment(0, netflix, Store.today(), 249.00, "December 2026", "GCash", null)));
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();
        String reqBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        httpLog.add(method + " " + path + (query == null ? "" : "?" + query));

        int status = 200;
        String resp = "[]";

        if (path.equals("/auth/v1/token")) {
            resp = "{\"access_token\":\"tok\",\"refresh_token\":\"ref\",\"expires_in\":3600,\"user\":{\"id\":\"u1\",\"email\":\"kirk@example.com\"}}";
        } else if (path.equals("/auth/v1/logout")) {
            status = 204;
            resp = "";
        } else if (path.equals("/rest/v1/app_preferences")) {
            resp = "[{\"id\":1,\"user_id\":\"u1\",\"currency\":\"PHP\",\"theme\":\"LIGHT\",\"reminder_lead_days\":7,\"show_overdue_reminders\":true}]";
        } else if (path.equals("/rest/v1/subscriptions") && method.equals("GET")) {
            resp = "["
                    + subJson(1, "Netflix", 549.00, "MONTHLY", sub1NextRenewal, "ACTIVE") + ","
                    + subJson(2, "Cloud", 199.00, "MONTHLY", LocalDate.now().minusDays(2), "PAUSED") + ","
                    + subJson(3, "OldApp", 299.00, "MONTHLY", LocalDate.now().minusDays(10), "CANCELLED")
                    + "]";
        } else if (path.equals("/rest/v1/subscriptions") && method.equals("PATCH")) {
            JsonObject obj = JsonParser.parseString(reqBody).getAsJsonObject();
            if (obj.has("next_renewal_date")) {
                sub1NextRenewal = LocalDate.parse(obj.get("next_renewal_date").getAsString());
            }
            resp = "[" + subJson(1, "Netflix", 549.00, "MONTHLY", sub1NextRenewal, "ACTIVE") + "]";
        } else if (path.equals("/rest/v1/members") && method.equals("GET")) {
            resp = "["
                    + memberJson(10, 1, "Ana", member1Notes) + ","
                    + memberJson(11, 1, "Ben", member2Notes)
                    + "]";
        } else if (path.equals("/rest/v1/members") && method.equals("PATCH")) {
            JsonObject obj = JsonParser.parseString(reqBody).getAsJsonObject();
            String notes = obj.has("role_notes") && !obj.get("role_notes").isJsonNull() ? obj.get("role_notes").getAsString() : null;
            if (query != null && query.contains("id=eq.10")) {
                member1Notes = notes;
                resp = "[" + memberJson(10, 1, "Ana", member1Notes) + "]";
            } else {
                member2Notes = notes;
                resp = "[" + memberJson(11, 1, "Ben", member2Notes) + "]";
            }
        } else if (path.equals("/rest/v1/payments") && method.equals("POST")) {
            JsonObject obj = JsonParser.parseString(reqBody).getAsJsonObject();
            long id = nextPaymentId.getAndIncrement();
            obj.addProperty("id", id);
            obj.addProperty("created_at", "2026-11-23T00:00:00Z");
            resp = "[" + obj + "]";
        } else if (path.equals("/rest/v1/contributions") && method.equals("POST")) {
            JsonObject obj = JsonParser.parseString(reqBody).getAsJsonObject();
            long id = nextContribId.getAndIncrement();
            obj.addProperty("id", id);
            obj.addProperty("created_at", "2026-11-23T00:00:00Z");
            resp = "[" + obj + "]";
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

    private static String subJson(int id, String name, double price, String cycle, LocalDate next, String status) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"category\":\"Entertainment\",\"icon_key\":\"television\","
                + "\"current_price\":" + price + ",\"billing_cycle\":\"" + cycle + "\",\"next_renewal_date\":\"" + next + "\","
                + "\"status\":\"" + status + "\",\"created_at\":\"2026-01-01T00:00:00Z\",\"updated_at\":\"2026-01-01T00:00:00Z\"}";
    }

    private static String memberJson(int id, int subId, String name, String notes) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("subscription_id", subId);
        obj.addProperty("name", name);
        if (notes == null) obj.add("role_notes", com.google.gson.JsonNull.INSTANCE);
        else obj.addProperty("role_notes", notes);
        obj.addProperty("created_at", "2026-01-01T00:00:00Z");
        obj.addProperty("updated_at", "2026-01-01T00:00:00Z");
        return obj.toString();
    }
}
