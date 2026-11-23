package beenewed.screens;

import beenewed.model.Models.*;
import beenewed.model.Store;
import beenewed.ui.Icons;
import beenewed.ui.Theme;
import beenewed.ui.UI;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RenewalsFilterTest {

    private static Subscription sub(int id, String name, Status status, LocalDate due) {
        Subscription s = new Subscription(id, name, "Entertainment", Icons.Type.TELEVISION, 299.00, Cycle.MONTHLY, due);
        s.status = status;
        return s;
    }

    @Test
    void allStatusesFilterIncludesActivePausedAndCancelledSubscriptions() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        Subscription active = sub(1, "Netflix", Status.ACTIVE, today.plusDays(3));
        Subscription overdueActive = sub(2, "Spotify", Status.ACTIVE, today.minusDays(2));
        Subscription paused = sub(3, "Cloud", Status.PAUSED, today.minusDays(5));
        Subscription cancelled = sub(4, "OldApp", Status.CANCELLED, today.minusDays(10));
        List<Subscription> all = List.of(cancelled, paused, overdueActive, active);

        List<Subscription> allResult = RenewalsScreen.filterRenewals(all, "All Statuses", "All Dates", today);
        assertEquals(4, allResult.size());
        assertTrue(allResult.containsAll(List.of(active, overdueActive, paused, cancelled)));

        List<Subscription> pausedOnly = RenewalsScreen.filterRenewals(all, "Paused", "All Dates", today);
        assertEquals(List.of(paused), pausedOnly);

        List<Subscription> cancelledOnly = RenewalsScreen.filterRenewals(all, "Cancelled", "All Dates", today);
        assertEquals(List.of(cancelled), cancelledOnly);

        // Overdue due-date filter only flags ACTIVE subscriptions past their due date
        List<Subscription> overdueOnly = RenewalsScreen.filterRenewals(all, "All Statuses", "Overdue", today);
        assertEquals(List.of(overdueActive), overdueOnly);
    }

    @Test
    void dueBadgeRespectsShowOverduePreference() {
        Theme.apply(false);
        LocalDate pastDue = Store.today().minusDays(3);

        UI.Pill highlighted = Common.dueBadge(pastDue, 7, true);
        assertEquals("Overdue 3 days", highlighted.getText());
        assertEquals(Theme.RED, highlighted.getForeground());

        UI.Pill neutral = Common.dueBadge(pastDue, 7, false);
        assertEquals("3 days ago", neutral.getText());
        assertEquals(Theme.MUTED, neutral.getForeground());
        assertNotEquals(Theme.RED, neutral.getForeground());
    }
}
