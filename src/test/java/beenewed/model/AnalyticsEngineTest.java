package beenewed.model;

import beenewed.model.AnalyticsEngine.*;
import beenewed.model.BillingRules.Interval;
import beenewed.model.Models.*;
import beenewed.ui.Icons;
import com.beenewed.backend.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsEngineTest {

    private static Subscription sub(int id, String name, String cat, double price, Cycle cycle, LocalDate next, Status status) {
        Subscription s = new Subscription(id, name, cat, Icons.Type.TELEVISION, price, cycle, next);
        s.status = status;
        return s;
    }

    @Test
    void cycleNormalizationHandlesWeeklyMonthlyAndYearly() {
        // Weekly: 100 * 52 = 5200/yr, 5200 / 12 = 433.33/mo, 5200 / 365 = 14.25/day
        assertEquals(new BigDecimal("433.33"), AnalyticsEngine.monthlyEquivalent(new BigDecimal("100.00"), Interval.WEEKLY));
        assertEquals(new BigDecimal("5200.00"), AnalyticsEngine.annualEquivalent(new BigDecimal("100.00"), Interval.WEEKLY));
        assertEquals(new BigDecimal("14.25"), AnalyticsEngine.dailyCost(new BigDecimal("100.00"), Interval.WEEKLY));

        // Monthly: 549/mo -> 6588/yr -> 18.05/day
        assertEquals(new BigDecimal("549.00"), AnalyticsEngine.monthlyEquivalent(new BigDecimal("549.00"), Interval.MONTHLY));
        assertEquals(new BigDecimal("6588.00"), AnalyticsEngine.annualEquivalent(new BigDecimal("549.00"), Interval.MONTHLY));
        assertEquals(new BigDecimal("18.05"), AnalyticsEngine.dailyCost(new BigDecimal("549.00"), Interval.MONTHLY));

        // Yearly: 1200/yr -> 100/mo, 365/yr -> 1.00/day
        assertEquals(new BigDecimal("100.00"), AnalyticsEngine.monthlyEquivalent(new BigDecimal("1200.00"), Interval.YEARLY));
        assertEquals(new BigDecimal("1200.00"), AnalyticsEngine.annualEquivalent(new BigDecimal("1200.00"), Interval.YEARLY));
        assertEquals(new BigDecimal("1.00"), AnalyticsEngine.dailyCost(new BigDecimal("365.00"), Interval.YEARLY));
    }

    @Test
    void categoryDistributionGroupsActiveSpendSortsDescendingAndIgnoresInactive() {
        LocalDate d = LocalDate.of(2026, 12, 1);
        List<Subscription> subs = List.of(
                sub(1, "Netflix", "Entertainment", 600.00, Cycle.MONTHLY, d, Status.ACTIVE),
                sub(2, "Disney+", "Entertainment", 2400.00, Cycle.YEARLY, d, Status.ACTIVE), // 200/mo
                sub(3, "iCloud", "Cloud Storage", 200.00, Cycle.MONTHLY, d, Status.ACTIVE),
                sub(4, "OldCloud", "Cloud Storage", 999.00, Cycle.MONTHLY, d, Status.PAUSED),
                sub(5, "Cancelled", "Gaming", 500.00, Cycle.MONTHLY, d, Status.CANCELLED)
        );

        List<CategoryMetric> dist = AnalyticsEngine.categoryDistribution(subs);
        assertEquals(2, dist.size());

        // Entertainment = 600 + 200 = 800/mo (80.0%), Cloud Storage = 200/mo (20.0%)
        assertEquals("Entertainment", dist.get(0).category());
        assertEquals(2, dist.get(0).activeCount());
        assertEquals(new BigDecimal("800.00"), dist.get(0).monthlyTotal());
        assertEquals(new BigDecimal("9600.00"), dist.get(0).annualTotal());
        assertEquals(new BigDecimal("80.0"), dist.get(0).sharePercent());

        assertEquals("Cloud Storage", dist.get(1).category());
        assertEquals(1, dist.get(1).activeCount());
        assertEquals(new BigDecimal("200.00"), dist.get(1).monthlyTotal());
        assertEquals(new BigDecimal("20.0"), dist.get(1).sharePercent());
    }

    @Test
    void forecastProjectionHandlesWeeklyMonthlyYearlyAndSpikesInRenewalMonth() {
        LocalDate ref = LocalDate.of(2026, 11, 23);
        List<ForecastItem> items = List.of(
                // Monthly 500 due Dec 1 -> hits Month 1, Month 2, Month 3 (1500 total over 3 months)
                new ForecastItem("Netflix", new BigDecimal("500.00"), Interval.MONTHLY, LocalDate.of(2026, 12, 1), Status.ACTIVE),
                // Yearly 1200 due Jan 15 -> spikes ONLY in Month 2 (Dec 23 – Jan 22)
                new ForecastItem("Domain", new BigDecimal("1200.00"), Interval.YEARLY, LocalDate.of(2027, 1, 15), Status.ACTIVE),
                // Weekly 100 due Nov 25 -> hits Nov 25, Dec 2, Dec 9, Dec 16 in Month 1 (400)
                new ForecastItem("News", new BigDecimal("100.00"), Interval.WEEKLY, LocalDate.of(2026, 11, 25), Status.ACTIVE),
                // Paused plan excluded
                new ForecastItem("PausedApp", new BigDecimal("999.00"), Interval.MONTHLY, LocalDate.of(2026, 12, 1), Status.PAUSED)
        );

        ForecastReport r3 = AnalyticsEngine.forecastItems(items, ref, 3);
        assertEquals(3, r3.months().size());
        // Month 1 (Nov 23 – Dec 22): 500 (Netflix) + 4*100 (News) = 900.00
        assertEquals(new BigDecimal("900.00"), r3.months().get(0).projectedAmount());
        assertEquals(5, r3.months().get(0).renewalCount());
        // Month 2 (Dec 23 – Jan 22): 500 (Netflix) + 1200 (Domain) + 5*100 (Dec 23, 30, Jan 6, 13, 20) = 2200.00
        assertEquals(new BigDecimal("2200.00"), r3.months().get(1).projectedAmount());
        assertTrue(r3.months().get(1).renewalNames().contains("Domain"));
        // Month 3 (Jan 23 – Feb 22): 500 (Netflix) + 4*100 (Jan 27, Feb 3, 10, 17) = 900.00
        assertEquals(new BigDecimal("900.00"), r3.months().get(2).projectedAmount());
        assertEquals(new BigDecimal("4000.00"), r3.totalProjected());

        // 1-month and 6-month horizons are accepted; 0 and 7 are rejected
        assertEquals(1, AnalyticsEngine.forecastItems(items, ref, 1).months().size());
        assertEquals(6, AnalyticsEngine.forecastItems(items, ref, 6).months().size());
        assertThrows(ValidationException.class, () -> AnalyticsEngine.forecastItems(items, ref, 0));
        assertThrows(ValidationException.class, () -> AnalyticsEngine.forecastItems(items, ref, 7));
    }

    @Test
    void usageEfficiencyComputesCostPerUseCostPerDayAndLowUsageBadges() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        Subscription unused = sub(1, "GymApp", "Utilities", 600.00, Cycle.MONTHLY, today.plusDays(5), Status.ACTIVE);
        Subscription lowUse = sub(2, "CloudPro", "Cloud Storage", 300.00, Cycle.MONTHLY, today.plusDays(5), Status.ACTIVE);
        Subscription healthy = sub(3, "Spotify", "Music", 150.00, Cycle.MONTHLY, today.plusDays(5), Status.ACTIVE);
        Subscription paused = sub(4, "OldSub", "Other", 500.00, Cycle.MONTHLY, today.plusDays(5), Status.PAUSED);

        List<Usage> usages = List.of(
                // GymApp: one old usage 45 days ago -> outside 30-day window -> UNUSED
                new Usage(1, unused, today.minusDays(45), 1.0, "Sessions", null),
                // CloudPro: 2 uses within 30 days -> LOW_USAGE (cost/use = 300/2 = 150.00)
                new Usage(2, lowUse, today.minusDays(29), 2.0, "Hours", null),
                new Usage(3, lowUse, today.minusDays(2), 1.5, "Hours", null),
                // Spotify: 3 uses within 30 days -> HEALTHY (cost/use = 150/3 = 50.00)
                new Usage(4, healthy, today.minusDays(10), 1.0, "Hours", null),
                new Usage(5, healthy, today.minusDays(5), null, null, "Listened to playlist"),
                new Usage(6, healthy, today, 2.0, "Hours", null)
        );

        EfficiencyReport report = AnalyticsEngine.evaluateUsageEfficiency(
                List.of(unused, lowUse, healthy, paused), usages, today, 30);

        assertEquals(3, report.totalActiveCount());
        assertEquals(1, report.unusedCount());
        assertEquals(1, report.lowUsageCount());
        assertEquals(2, report.underutilizedCount());
        assertEquals(new BigDecimal("900.00"), report.underutilizedMonthlySpend());
        assertEquals(new BigDecimal("10800.00"), report.underutilizedAnnualSpend());

        // Sorted: UNUSED first, LOW_USAGE second, HEALTHY third
        UsageEfficiency row0 = report.rows().get(0);
        assertEquals("GymApp", row0.subscription().name);
        assertEquals(UtilizationLevel.UNUSED, row0.level());
        assertEquals(0, row0.usesInWindow());
        assertNull(row0.costPerUse());
        assertEquals(today.minusDays(45), row0.lastUsedDate());

        UsageEfficiency row1 = report.rows().get(1);
        assertEquals("CloudPro", row1.subscription().name);
        assertEquals(UtilizationLevel.LOW_USAGE, row1.level());
        assertEquals(2, row1.usesInWindow());
        assertEquals(new BigDecimal("150.00"), row1.costPerUse());
        assertEquals("Hours", row1.primaryUnit());

        UsageEfficiency row2 = report.rows().get(2);
        assertEquals("Spotify", row2.subscription().name);
        assertEquals(UtilizationLevel.HEALTHY, row2.level());
        assertEquals(3, row2.usesInWindow());
        assertEquals(new BigDecimal("50.00"), row2.costPerUse());
        assertEquals(new BigDecimal("4.93"), row2.costPerDay()); // 150*12 / 365 = 4.93
    }
}
