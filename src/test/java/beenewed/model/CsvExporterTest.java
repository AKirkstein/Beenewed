package beenewed.model;

import beenewed.model.AnalyticsEngine.EfficiencyReport;
import beenewed.model.Models.*;
import beenewed.model.SplitAllocator.DistributionSummary;
import beenewed.model.SplitAllocator.PaymentSplitMeta;
import beenewed.ui.Icons;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
}
