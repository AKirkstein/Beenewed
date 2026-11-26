package beenewed.model;

import beenewed.model.AnalyticsEngine.CategoryMetric;
import beenewed.model.AnalyticsEngine.EfficiencyReport;
import beenewed.model.Models.*;
import beenewed.model.SplitAllocator.DistributionSummary;
import beenewed.model.SplitAllocator.PaymentSplitMeta;
import beenewed.ui.Icons;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CsvExporterTest {

    @Test
    void paymentLedgerCsvEscapesSpecialCharsStripsMetadataAndNeutralizesFormulas() {
        Subscription sub = new Subscription(1, "Netflix, \"4K\"", "Entertainment",
                Icons.Type.TELEVISION, 549.00, Cycle.MONTHLY, LocalDate.of(2026, 12, 1));
        String encodedNotes = SplitAllocator.encodePaymentNotes(
                "=SUM(A1:A2), paid ref",
                new PaymentSplitMeta(SplitAllocator.OWNER_KEY, false, Map.of()));
        Payment p = new Payment(10, sub, LocalDate.of(2026, 11, 23), 549.00, "November 2026", "GCash", encodedNotes);

        String csv = CsvExporter.exportPaymentLedger(List.of(p), pay -> SplitAllocator.OWNER_LABEL);
        String[] lines = csv.split("\r\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("Payment ID,Subscription,Category,Payment Date"));
        // Quotes escaped per RFC-4180, internal metadata stripped, leading '=' neutralized with '\''
        assertTrue(lines[1].contains("\"Netflix, \"\"4K\"\"\""));
        assertTrue(lines[1].contains("\"'=SUM(A1:A2), paid ref\""));
        assertFalse(csv.contains("[[beenewed:"));
    }

    @Test
    void activeSubscriptionsCsvIncludesNormalizedSpendAndEfficiencyMetrics() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        Subscription sub = new Subscription(7, "Spotify", "Music",
                Icons.Type.MUSIC, 150.00, Cycle.MONTHLY, LocalDate.of(2026, 12, 5));
        List<Usage> usages = List.of(
                new Usage(1, sub, today.minusDays(4), 1.0, "Hours", null),
                new Usage(2, sub, today.minusDays(2), 1.0, "Hours", null),
                new Usage(3, sub, today, 1.0, "Hours", null));
        EfficiencyReport eff = AnalyticsEngine.evaluateUsageEfficiency(List.of(sub), usages, today, 30);

        String csv = CsvExporter.exportActiveSubscriptions(eff.rows());
        String[] lines = csv.split("\r\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("Monthly Equivalent (PHP),Annual Equivalent (PHP)"));
        assertTrue(lines[1].contains("7,Spotify,Music,Active,150.00,Monthly,150.00,1800.00,2026-12-05,3,4.93,50.00,Active Use"));
    }

    @Test
    void familySplitsCsvExportsAllParticipantsAndSettlementStatuses() {
        Subscription sub = new Subscription(1, "Family Plan", "Entertainment",
                Icons.Type.TELEVISION, 500.00, Cycle.MONTHLY, LocalDate.of(2026, 12, 1));
        Member ana = new Member(11, sub, "Ana", null);
        Member ben = new Member(12, sub, "Ben", null);
        Payment payment = new Payment(99, sub, LocalDate.of(2026, 11, 23), 500.00, "November 2026", "GCash", null);
        Contribution anaPaid = new Contribution(1, sub, ana, payment, 166.67, LocalDate.of(2026, 11, 23));

        DistributionSummary summary = SplitAllocator.computeDistribution(payment, List.of(ana, ben), List.of(anaPaid));
        String csv = CsvExporter.exportFamilySplits(List.of(summary));
        String[] lines = csv.split("\r\n");
        assertEquals(4, lines.length); // header + 3 participants
        assertTrue(lines[1].endsWith(",166.67,166.67,0.00,Paid (Payer)"));
        assertTrue(lines[2].endsWith(",166.67,166.67,0.00,Paid"));
        assertTrue(lines[3].endsWith(",166.66,0.00,166.66,Pending"));
        assertEquals(new BigDecimal("166.66"), summary.totalPending());
    }

    @Test
    void upcomingRenewalsCsvMatchesDataTableSchemaAndEscapesCommas() {
        LocalDate today = LocalDate.of(2026, 11, 23);
        Subscription active = new Subscription(1, "Netflix, Ultra HD", "Entertainment",
                Icons.Type.TELEVISION, 549.00, Cycle.MONTHLY, today.plusDays(4));
        Subscription overdue = new Subscription(2, "Spotify", "Music",
                Icons.Type.MUSIC, 149.00, Cycle.MONTHLY, today.minusDays(2));
        Subscription paused = new Subscription(3, "iCloud+", "Cloud",
                Icons.Type.CLOUD, 199.00, Cycle.MONTHLY, today.plusDays(10));
        paused.status = Status.PAUSED;
        Subscription cancelled = new Subscription(4, "Old \"App\"", "Work",
                Icons.Type.WORK, 999.00, Cycle.YEARLY, today.minusDays(5));
        cancelled.status = Status.CANCELLED;

        String csv = CsvExporter.exportUpcomingRenewals(List.of(active, overdue, paused, cancelled), today, true);
        String[] lines = csv.split("\r\n");
        assertEquals(5, lines.length);
        assertEquals("Subscription,Status,Billing Cycle,Next Renewal,Expected Amount (PHP),Due", lines[0]);
        assertEquals("\"Netflix, Ultra HD\",Active,Monthly,2026-11-27,549.00,In 4 days", lines[1]);
        assertEquals("Spotify,Active,Monthly,2026-11-21,149.00,Overdue 2 days", lines[2]);
        assertEquals("iCloud+,Paused,Monthly,2026-12-03,199.00,On hold", lines[3]);
        assertEquals("\"Old \"\"App\"\"\",Cancelled,Yearly,2026-11-18,999.00,Inactive", lines[4]);

        String csvNoOverdueHighlight = CsvExporter.exportUpcomingRenewals(List.of(overdue), today, false);
        assertTrue(csvNoOverdueHighlight.contains("Spotify,Active,Monthly,2026-11-21,149.00,2 days ago"));
    }

    @Test
    void paymentHistoryCsvMatchesDataTableSchemaWithFallbacksAndEscaping() {
        Subscription sub = new Subscription(1, "Adobe, Creative Cloud", "Design",
                Icons.Type.DESIGN, 1200.00, Cycle.MONTHLY, LocalDate.of(2026, 12, 1));
        Payment p1 = new Payment(10, sub, LocalDate.of(2026, 11, 1), 1200.00, "November 2026", "GCash", null);
        Payment p2 = new Payment(11, sub, LocalDate.of(2026, 10, 1), 1200.00, "  ", null, null);

        String csv = CsvExporter.exportPaymentHistory(List.of(p1, p2));
        String[] lines = csv.split("\r\n");
        assertEquals(3, lines.length);
        assertEquals("Subscription,Payment Date,Amount Paid (PHP),Renewal Period,Payment Method", lines[0]);
        assertEquals("\"Adobe, Creative Cloud\",2026-11-01,1200.00,November 2026,GCash", lines[1]);
        assertEquals("\"Adobe, Creative Cloud\",2026-10-01,1200.00,\u2014,Not specified", lines[2]);
    }

    @Test
    void reportsSummaryCsvExportsCategoriesRecurringAmountsAndUsage(@TempDir Path tempDir) throws Exception {
        LocalDate today = LocalDate.of(2026, 11, 23);
        Subscription spotify = new Subscription(1, "Spotify, Duo", "Music",
                Icons.Type.MUSIC, 150.00, Cycle.MONTHLY, LocalDate.of(2026, 12, 5));
        Subscription netflix = new Subscription(2, "Netflix", "Entertainment",
                Icons.Type.TELEVISION, 600.00, Cycle.MONTHLY, LocalDate.of(2026, 12, 1));
        List<Subscription> subs = List.of(spotify, netflix);
        List<Usage> usages = List.of(
                new Usage(1, spotify, today.minusDays(2), 1.0, "Hours", null),
                new Usage(2, spotify, today.minusDays(1), 1.0, "Hours", null),
                new Usage(3, spotify, today, 1.0, "Hours", null));

        List<CategoryMetric> categories = AnalyticsEngine.categoryDistribution(subs);
        EfficiencyReport eff = AnalyticsEngine.evaluateUsageEfficiency(subs, usages, today, 30);

        String csv = CsvExporter.exportReportsSummary(categories, eff);
        assertTrue(csv.contains("Section,Category Breakdown\r\n"));
        assertTrue(csv.contains("Category,Active Subscriptions,Monthly Equivalent (PHP),Annual Equivalent (PHP),Share (%)\r\n"));
        assertTrue(csv.contains("Entertainment,1,600.00,7200.00,80.0%\r\n"));
        assertTrue(csv.contains("Music,1,150.00,1800.00,20.0%\r\n"));
        assertTrue(csv.contains("Section,Recurring Spend & Usage Efficiency\r\n"));
        assertTrue(csv.contains("\"Spotify, Duo\",Music,Active,150.00,Monthly,150.00,1800.00,2026-12-05,3,4.93,50.00,2026-11-23,Active Use\r\n"));
        assertTrue(csv.contains("Netflix,Entertainment,Active,600.00,Monthly,600.00,7200.00,2026-12-01,0,19.73,,Never,No Usage\r\n"));

        Path outFile = tempDir.resolve("reports-summary.csv");
        CsvExporter.writeUtf8(outFile, csv);
        String diskContent = Files.readString(outFile, StandardCharsets.UTF_8);
        assertEquals(csv, diskContent);
    }
}
