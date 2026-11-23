package beenewed.dialogs;

import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.DateField;
import beenewed.ui.Theme;
import com.beenewed.supabase.SupabaseAuthService;
import com.beenewed.supabase.SupabaseConfig;
import com.beenewed.supabase.SupabaseRestClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link PaymentDialog#viewReceipt} renders every form control strictly read-only:
 * disabled, non-editable, non-focusable (no caret), and with the date picker button/popup inert.
 */
class PaymentDialogReadOnlyTest {

    @Test
    void receiptDialogDisablesDateFieldAndAllInputs() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a display to construct JDialog");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        SupabaseAuthService auth = null;
        AtomicReference<JFrame> ownerRef = new AtomicReference<>();
        AtomicReference<PaymentDialog> dialogRef = new AtomicReference<>();
        try {
            SupabaseConfig cfg = new SupabaseConfig("http://127.0.0.1:" + server.getAddress().getPort(), "test-anon-key");
            auth = new SupabaseAuthService(cfg);
            auth.signIn("kirk@example.com", "Passw0rd!");
            Store.initialize(new SupabaseRestClient(cfg, auth));

            Store st = Store.get();
            Subscription sub = st.getSubscription(1);
            Payment payment = st.paymentsFor(sub).get(0);

            SwingUtilities.invokeAndWait(() -> {
                Theme.apply(false);
                JFrame frame = new JFrame();
                ownerRef.set(frame);
                PaymentDialog dialog = PaymentDialog.viewReceipt(frame, payment);
                dialogRef.set(dialog);

                // Every editable/interactive form control inside the dialog body must be locked down
                List<Component> all = collectAll(dialog.body);
                List<DateField> dateFields = new ArrayList<>();
                int checkedTextInputs = 0;
                int checkedCombos = 0;

                for (Component c : all) {
                    if (c instanceof DateField df) {
                        dateFields.add(df);
                        assertFalse(df.isEnabled(), "DateField container must be disabled");
                    } else if (c instanceof JTextComponent tc) {
                        // Ignore non-interactive helper/info JTextAreas (they have editable=false & focusable=false)
                        assertFalse(tc.isEditable(), "Text component must not be editable: " + tc.getClass().getSimpleName());
                        assertFalse(tc.isFocusable(), "Text component must not be focusable (no active caret)");
                        if (tc instanceof JTextField) {
                            assertFalse(tc.isEnabled(), "Text field must be disabled");
                            checkedTextInputs++;
                        }
                    } else if (c instanceof JComboBox<?> combo) {
                        assertFalse(combo.isEnabled(), "ComboBox must be disabled");
                        assertFalse(combo.isFocusable(), "ComboBox must not be focusable");
                        checkedCombos++;
                    } else if (c instanceof AbstractButton btn) {
                        assertFalse(btn.isEnabled(), "Body button (e.g. calendar picker) must be disabled");
                        assertFalse(btn.isFocusable(), "Body button must not be focusable");
                    }
                }

                assertEquals(1, dateFields.size(), "Expected Payment Date DateField");
                assertTrue(checkedTextInputs >= 2, "Expected Date and Amount text fields");
                assertTrue(checkedCombos >= 5, "Expected Subscription, Scope, Payer, Renewal Period, and Payment Method combos");

                // Receipt Renewal Period combo must be disabled, non-editable, and show the exact recorded period
                JComboBox<?> receiptPeriodCombo = all.stream()
                        .filter(JComboBox.class::isInstance)
                        .map(JComboBox.class::cast)
                        .filter(cb -> payment.period.equals(cb.getSelectedItem()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Expected disabled Renewal Period JComboBox in receipt dialog"));
                assertFalse(receiptPeriodCombo.isEnabled(), "Receipt Renewal Period JComboBox must be disabled");
                assertFalse(receiptPeriodCombo.isEditable(), "Receipt Renewal Period JComboBox must be non-editable");
                assertEquals(1, receiptPeriodCombo.getItemCount());
                assertEquals("November 2026", receiptPeriodCombo.getSelectedItem());

                // Attempting to click the calendar picker button must not open popup or alter date
                DateField df = dateFields.get(0);
                LocalDate before = df.getDate();
                for (Component child : df.getComponents()) {
                    if (child instanceof JButton calBtn) {
                        calBtn.doClick();
                    }
                }
                assertFalse(df.isPopupVisible(), "Date picker popup must stay closed in read-only mode");
                assertEquals(before, df.getDate());

                // Primary save button hidden; Cancel renamed to Close and set as default button
                assertFalse(dialog.primaryBtn.isVisible(), "Save button must be hidden in receipt mode");
                assertEquals("Close", dialog.cancelBtn.getText());
                assertSame(dialog.cancelBtn, dialog.getRootPane().getDefaultButton());

                // Also verify SubscriptionDialog and PaymentDialog reject malformed price/date inline on submit
                SubscriptionDialog subDialog = new SubscriptionDialog(frame, null);
                try {
                    assertNull(subDialog.prepareSave(), "Blank subscription form must fail validation");
                } finally {
                    subDialog.dispose();
                }

                PaymentDialog newPayDialog = new PaymentDialog(frame, sub);
                try {
                    // Renewal Period must be a non-editable JComboBox populated with sequential unpaid periods
                    JComboBox<?> periodCombo = collectAll(newPayDialog.body).stream()
                            .filter(JComboBox.class::isInstance)
                            .map(JComboBox.class::cast)
                            .filter(cb -> "December 2026".equals(cb.getSelectedItem()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Expected Renewal Period JComboBox populated with sequential unpaid periods"));
                    assertFalse(periodCombo.isEditable(), "Renewal Period JComboBox must be non-editable");
                    assertEquals(6, periodCombo.getItemCount(), "Expected 6 consecutive unpaid monthly periods");
                    assertEquals("December 2026", periodCombo.getItemAt(0));
                    assertEquals("January 2027", periodCombo.getItemAt(1));
                    assertEquals("May 2027", periodCombo.getItemAt(5));

                    // Picking a later period (skipping unpaid December 2026) must fail validation
                    periodCombo.setSelectedIndex(1);
                    assertNull(newPayDialog.prepareSave(), "Skipping unpaid December 2026 must fail inline validation");
                    periodCombo.setSelectedIndex(0);

                    // Replace date with impossible calendar date 02/30/2026 -> must reject cleanly
                    for (Component c : collectAll(newPayDialog.body)) {
                        if (c instanceof DateField payDf && payDf.getComponent(0) instanceof JTextField tf) {
                            tf.setText("02/30/2026");
                        }
                    }
                    assertNull(newPayDialog.prepareSave(), "Malformed date 02/30/2026 must fail inline validation");

                    // Replace date with a future date beyond today -> must reject cleanly
                    for (Component c : collectAll(newPayDialog.body)) {
                        if (c instanceof DateField payDf) {
                            payDf.setDate(Store.today().plusDays(5));
                        }
                    }
                    assertNull(newPayDialog.prepareSave(), "Future payment date must fail inline validation");

                    // Valid today date + next sequential unpaid period -> prepareSave succeeds
                    for (Component c : collectAll(newPayDialog.body)) {
                        if (c instanceof DateField payDf) {
                            payDf.setDate(Store.today());
                        }
                    }
                    assertNotNull(newPayDialog.prepareSave(), "Valid sequential period and paymentDate <= today must pass");
                } finally {
                    newPayDialog.dispose();
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (dialogRef.get() != null) dialogRef.get().dispose();
                if (ownerRef.get() != null) ownerRef.get().dispose();
            });
            Store.reset();
            if (auth != null) auth.signOutAndWait(Duration.ofSeconds(2));
            server.stop(0);
        }
    }

    private static List<Component> collectAll(Container root) {
        List<Component> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            out.add(c);
            if (c instanceof Container ct) {
                out.addAll(collectAll(ct));
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
            resp = "[{\"id\":1,\"name\":\"Netflix\",\"category\":\"Entertainment\",\"icon_key\":\"television\","
                    + "\"current_price\":549.00,\"billing_cycle\":\"MONTHLY\",\"next_renewal_date\":\"2026-12-01\","
                    + "\"status\":\"ACTIVE\",\"created_at\":\"2026-01-01T00:00:00Z\",\"updated_at\":\"2026-01-01T00:00:00Z\"}]";
        } else if (path.equals("/rest/v1/members")) {
            resp = "[{\"id\":2,\"subscription_id\":1,\"name\":\"Ana\",\"role_notes\":null,"
                    + "\"created_at\":\"2026-01-01T00:00:00Z\",\"updated_at\":\"2026-01-01T00:00:00Z\"}]";
        } else if (path.equals("/rest/v1/payments")) {
            resp = "[{\"id\":10,\"subscription_id\":1,\"payment_date\":\"2026-11-23\",\"amount_paid\":549.00,"
                    + "\"renewal_period\":\"November 2026\",\"payment_method\":\"GCash\",\"notes\":\"Paid ref #42\","
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
