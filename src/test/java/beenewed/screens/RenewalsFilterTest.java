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

    @Test
    void filterRenewalsDoesNotDoubleRenderIdenticalDuplicatesButKeepsDifferentDates() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        Subscription first = sub(1, "Netflix", Status.ACTIVE, today.plusDays(5));
        Subscription identicalDup = sub(2, "  netflix ", Status.ACTIVE, today.plusDays(5));
        Subscription differentDate = sub(3, "Netflix", Status.ACTIVE, today.plusDays(15));

        List<Subscription> result = RenewalsScreen.filterRenewals(
                List.of(first, identicalDup, differentDate), "All Statuses", "All Dates", today);
        assertEquals(List.of(first, differentDate), result);
    }

    @Test
    void filterPaymentsAppliesDateRangeStatusAndSearchQuery() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        Subscription active = sub(1, "Netflix, Ultra", Status.ACTIVE, today.plusDays(5));
        Subscription paused = sub(2, "Spotify", Status.PAUSED, today.plusDays(10));
        Payment thisMonth = new Payment(1, active, LocalDate.of(2026, 11, 10), 549.00, "November 2026", "GCash", null);
        Payment last30DaysPrevMonth = new Payment(2, paused, LocalDate.of(2026, 10, 28), 149.00, "October 2026", "Card", null);
        Payment earlierThisYear = new Payment(3, active, LocalDate.of(2026, 3, 15), 549.00, "March 2026", "GCash", null);
        Payment lastYear = new Payment(4, active, LocalDate.of(2025, 11, 1), 549.00, "November 2025", "GCash", null);
        List<Payment> all = List.of(thisMonth, last30DaysPrevMonth, earlierThisYear, lastYear);

        assertEquals(List.of(thisMonth),
                RenewalsScreen.filterPayments(all, "This Month", "All Statuses", "", today));
        assertEquals(List.of(thisMonth, last30DaysPrevMonth),
                RenewalsScreen.filterPayments(all, "Last 30 Days", "All Statuses", "", today));
        assertEquals(List.of(thisMonth, last30DaysPrevMonth, earlierThisYear),
                RenewalsScreen.filterPayments(all, "This Year", "All Statuses", "", today));
        assertEquals(List.of(last30DaysPrevMonth),
                RenewalsScreen.filterPayments(all, "All Dates", "Paused", "", today));
        assertEquals(List.of(thisMonth, earlierThisYear, lastYear),
                RenewalsScreen.filterPayments(all, "All Dates", "All Statuses", "netflix", today));
    }
}
