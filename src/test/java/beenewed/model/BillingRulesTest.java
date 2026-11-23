package beenewed.model;

import beenewed.model.BillingRules.Interval;
import beenewed.model.Models.*;
import beenewed.ui.Icons;
import com.beenewed.backend.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BillingRulesTest {

    private static Subscription sub(int id, Cycle cycle, double price, LocalDate nextRenewal, Status status) {
        Subscription s = new Subscription(id, "Sub-" + id, "Entertainment", Icons.Type.TELEVISION, price, cycle, nextRenewal);
        s.status = status;
        return s;
    }

    @Test
    void duplicatePeriodDetectionNormalizesEquivalentFormats() {
        Subscription netflix = sub(1, Cycle.MONTHLY, 549.00, LocalDate.of(2026, 11, 15), Status.ACTIVE);
        Subscription spotify = sub(2, Cycle.MONTHLY, 149.00, LocalDate.of(2026, 11, 15), Status.ACTIVE);
        Payment nov = new Payment(10, netflix, LocalDate.of(2026, 11, 14), 549.00, "November 2026", "GCash", null);

        assertTrue(BillingRules.findDuplicatePayment(netflix, "November 2026", List.of(nov)).isPresent());
        assertTrue(BillingRules.findDuplicatePayment(netflix, "  november   2026 ", List.of(nov)).isPresent());
        assertTrue(BillingRules.findDuplicatePayment(netflix, "Nov 2026", List.of(nov)).isPresent());
        assertTrue(BillingRules.findDuplicatePayment(netflix, "2026-11", List.of(nov)).isPresent());
        assertTrue(BillingRules.findDuplicatePayment(netflix, "11/2026", List.of(nov)).isPresent());

        // Different period or different subscription is allowed
        assertTrue(BillingRules.findDuplicatePayment(netflix, "December 2026", List.of(nov)).isEmpty());
        assertTrue(BillingRules.findDuplicatePayment(spotify, "November 2026", List.of(nov)).isEmpty());
    }

    @Test
    void weeklyPeriodNormalizationMatchesIsoAndWeekOfLabels() {
        String keyA = BillingRules.canonicalPeriodKey("Week of Nov 23, 2026", Interval.WEEKLY);
        String keyB = BillingRules.canonicalPeriodKey("2026-W48", Interval.WEEKLY);
        assertEquals(keyB, keyA);
        assertEquals("2026-W48", keyA);
    }

    @Test
    void paymentAmountMustAlignWithPriceOrDesignatedSplitShare() {
        BigDecimal price = new BigDecimal("549.00");
        List<BigDecimal> shares = List.of(new BigDecimal("183.00"));

        assertDoesNotThrow(() -> BillingRules.validatePaymentAmount(new BigDecimal("549.00"), price, List.of()));
        assertDoesNotThrow(() -> BillingRules.validatePaymentAmount(new BigDecimal("183.00"), price, shares));

        ValidationException soloErr = assertThrows(ValidationException.class,
                () -> BillingRules.validatePaymentAmount(new BigDecimal("500.00"), price, List.of()));
        assertTrue(soloErr.getMessage().contains("549.00"));

        ValidationException splitErr = assertThrows(ValidationException.class,
                () -> BillingRules.validatePaymentAmount(new BigDecimal("200.00"), price, shares));
        assertTrue(splitErr.getMessage().contains("549.00"));
        assertTrue(splitErr.getMessage().contains("183.00"));

        assertThrows(ValidationException.class,
                () -> BillingRules.validatePaymentAmount(BigDecimal.ZERO, price, shares));
    }

    @Test
    void dueDateAdvancementHandlesWeeklyMonthlyYearlyRolloversAndEarlyPayments() {
        // Weekly
        assertEquals(LocalDate.of(2026, 11, 30),
                BillingRules.advanceNextDueDate(LocalDate.of(2026, 11, 23), Interval.WEEKLY));

        // Early monthly payment still advances from scheduled due date by 1 month
        assertEquals(LocalDate.of(2027, 1, 1),
                BillingRules.advanceNextDueDate(LocalDate.of(2026, 12, 1), Interval.MONTHLY));

        // Month-end rollover (leap year & non-leap year)
        assertEquals(LocalDate.of(2024, 2, 29),
                BillingRules.advanceNextDueDate(LocalDate.of(2024, 1, 31), Interval.MONTHLY));
        assertEquals(LocalDate.of(2025, 2, 28),
                BillingRules.advanceNextDueDate(LocalDate.of(2025, 1, 31), Interval.MONTHLY));

        // Yearly & leap-day rollover
        assertEquals(LocalDate.of(2025, 2, 28),
                BillingRules.advanceNextDueDate(LocalDate.of(2024, 2, 29), Interval.YEARLY));
        assertEquals(LocalDate.of(2027, 11, 15),
                BillingRules.advanceNextDueDate(LocalDate.of(2026, 11, 15), Cycle.YEARLY));
    }

    @Test
    void overdueReevaluationClearsAfterAdvancingCycleAndIgnoresNonActiveStatuses() {
        LocalDate today = LocalDate.of(2026, 11, 23);

        // 1 cycle overdue -> advance by 1 month -> no longer overdue
        LocalDate overdueDue = LocalDate.of(2026, 11, 10);
        assertTrue(BillingRules.isOverdue(Status.ACTIVE, overdueDue, today));
        LocalDate advanced = BillingRules.advanceNextDueDate(overdueDue, Interval.MONTHLY);
        assertEquals(LocalDate.of(2026, 12, 10), advanced);
        assertFalse(BillingRules.isOverdue(Status.ACTIVE, advanced, today));

        // 2 cycles overdue -> cycle-by-cycle (+1 month) advances Sep 10 -> Oct 10 (still overdue until settled)
        LocalDate twoBehind = LocalDate.of(2026, 9, 10);
        LocalDate afterOne = BillingRules.advanceNextDueDate(twoBehind, Interval.MONTHLY);
        assertEquals(LocalDate.of(2026, 10, 10), afterOne);
        assertTrue(BillingRules.isOverdue(Status.ACTIVE, afterOne, today));

        // Paused and Cancelled subscriptions are never flagged as overdue
        assertFalse(BillingRules.isOverdue(Status.PAUSED, overdueDue, today));
        assertFalse(BillingRules.isOverdue(Status.CANCELLED, overdueDue, today));
    }

    @Test
    void paymentDateAllowsPastAndTodayButRejectsFutureDates() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        assertDoesNotThrow(() -> BillingRules.validatePaymentDate(today.minusDays(10), today));
        assertDoesNotThrow(() -> BillingRules.validatePaymentDate(today, today));

        ValidationException futureErr = assertThrows(ValidationException.class,
                () -> BillingRules.validatePaymentDate(today.plusDays(1), today));
        assertTrue(futureErr.getMessage().toLowerCase().contains("future"));
        assertThrows(ValidationException.class, () -> BillingRules.validatePaymentDate(null, today));
    }

    @Test
    void upcomingUnpaidPeriodsComputesConsecutiveCyclesAndExcludesAlreadyPaid() {
        Subscription monthly = sub(1, Cycle.MONTHLY, 549.00, LocalDate.of(2026, 11, 15), Status.ACTIVE);
        Payment novPaid = new Payment(10, monthly, LocalDate.of(2026, 11, 10), 549.00, "November 2026", "GCash", null);

        List<String> labels = BillingRules.upcomingUnpaidPeriodLabels(monthly, List.of(novPaid));
        assertEquals(List.of(
                "December 2026",
                "January 2027",
                "February 2027",
                "March 2027",
                "April 2027",
                "May 2027"), labels);

        Subscription yearly = sub(2, Cycle.YEARLY, 1990.00, LocalDate.of(2026, 11, 15), Status.ACTIVE);
        List<String> yearlyLabels = BillingRules.upcomingUnpaidPeriodLabels(yearly, List.of());
        assertEquals(List.of("November 2026", "November 2027", "November 2028"), yearlyLabels);
    }

    @Test
    void sequentialPeriodValidationAllowsNextUnpaidAndRejectsSkippedOrAlreadyPaid() {
        Subscription netflix = sub(1, Cycle.MONTHLY, 549.00, LocalDate.of(2026, 11, 15), Status.ACTIVE);
        Payment novPaid = new Payment(10, netflix, LocalDate.of(2026, 11, 10), 549.00, "November 2026", "GCash", null);

        // Next unpaid period is December 2026 -> accepted and advances due date to Jan 15, 2027
        BillingRules.PeriodOption dec = BillingRules.validateSequentialPeriod(netflix, "December 2026", List.of(novPaid));
        assertEquals(LocalDate.of(2026, 12, 15), dec.dueDate());
        assertEquals("December 2026", dec.label());
        assertEquals(LocalDate.of(2027, 1, 15),
                BillingRules.nextDueDateAfterPayment(netflix, dec, List.of(novPaid)));

        // Already-paid period rejected (even in alternate format)
        ValidationException dupErr = assertThrows(ValidationException.class,
                () -> BillingRules.validateSequentialPeriod(netflix, "Nov 2026", List.of(novPaid)));
        assertTrue(dupErr.getMessage().toLowerCase().contains("already"));

        // Skipping unpaid December 2026 to pay January 2027 rejected
        ValidationException skipErr = assertThrows(ValidationException.class,
                () -> BillingRules.validateSequentialPeriod(netflix, "January 2027", List.of(novPaid)));
        assertTrue(skipErr.getMessage().toLowerCase().contains("skip"));
        assertTrue(skipErr.getMessage().contains("December 2026"));
    }
}
