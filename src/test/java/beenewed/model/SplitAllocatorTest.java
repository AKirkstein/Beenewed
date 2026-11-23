package beenewed.model;

import beenewed.model.Models.*;
import beenewed.model.SplitAllocator.*;
import beenewed.ui.Icons;
import com.beenewed.backend.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SplitAllocatorTest {

    private static Subscription familyPlan(double price) {
        return new Subscription(1, "Family Plan", "Entertainment", Icons.Type.TELEVISION,
                price, Cycle.MONTHLY, LocalDate.of(2026, 12, 1));
    }

    @Test
    void equalSplitHandlesFractionalRoundingWithExactSum() {
        List<BigDecimal> split500 = SplitAllocator.splitEqually(new BigDecimal("500.00"), 3);
        assertEquals(List.of(new BigDecimal("166.67"), new BigDecimal("166.67"), new BigDecimal("166.66")), split500);
        assertEquals(new BigDecimal("500.00"), split500.stream().reduce(BigDecimal.ZERO, BigDecimal::add));

        List<BigDecimal> split100 = SplitAllocator.splitEqually(new BigDecimal("100.00"), 3);
        assertEquals(List.of(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33")), split100);
        assertEquals(new BigDecimal("100.00"), split100.stream().reduce(BigDecimal.ZERO, BigDecimal::add));

        List<BigDecimal> split1000By7 = SplitAllocator.splitEqually(new BigDecimal("1000.00"), 7);
        assertEquals(7, split1000By7.size());
        assertEquals(new BigDecimal("1000.00"), split1000By7.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void customSplitValidationEnforcesExactTotal() {
        BigDecimal total = new BigDecimal("549.00");

        Map<String, BigDecimal> valid = new LinkedHashMap<>();
        valid.put(SplitAllocator.OWNER_KEY, new BigDecimal("200.00"));
        valid.put("member:1", new BigDecimal("200.00"));
        valid.put("member:2", new BigDecimal("149.00"));
        assertDoesNotThrow(() -> SplitAllocator.validateCustomSplits(total, valid));

        Map<String, BigDecimal> shortByCent = new LinkedHashMap<>(valid);
        shortByCent.put("member:2", new BigDecimal("148.99"));
        ValidationException shortErr = assertThrows(ValidationException.class,
                () -> SplitAllocator.validateCustomSplits(total, shortByCent));
        assertTrue(shortErr.getMessage().contains("short"));

        Map<String, BigDecimal> overByCent = new LinkedHashMap<>(valid);
        overByCent.put("member:2", new BigDecimal("149.01"));
        ValidationException overErr = assertThrows(ValidationException.class,
                () -> SplitAllocator.validateCustomSplits(total, overByCent));
        assertTrue(overErr.getMessage().contains("exceeds"));

        Map<String, BigDecimal> negative = new LinkedHashMap<>(valid);
        negative.put("member:2", new BigDecimal("-1.00"));
        assertThrows(ValidationException.class, () -> SplitAllocator.validateCustomSplits(total, negative));
    }

    @Test
    void groupHeadDistributionTracksPaidPartialPendingAndPayer() {
        Subscription sub = familyPlan(500.00);
        Member ana = new Member(11, sub, "Ana", null);
        Member ben = new Member(12, sub, "Ben", null);
        Payment payment = new Payment(100, sub, LocalDate.of(2026, 11, 23), 500.00, "November 2026", "GCash", null);

        // Ana paid her full share (166.67), Ben paid partially (100.00 of 166.66)
        Contribution anaPaid = new Contribution(1, sub, ana, payment, 166.67, LocalDate.of(2026, 11, 23));
        Contribution benPartial = new Contribution(2, sub, ben, payment, 100.00, LocalDate.of(2026, 11, 23));

        DistributionSummary summary = SplitAllocator.computeDistribution(
                payment, List.of(ana, ben), List.of(anaPaid, benPartial));

        assertEquals(SplitAllocator.OWNER_KEY, summary.payerKey());
        assertEquals(new BigDecimal("500.00"), summary.totalPayment());
        assertEquals(new BigDecimal("166.67"), summary.payerShare());
        assertEquals(new BigDecimal("333.33"), summary.totalToCollect());
        assertEquals(new BigDecimal("266.67"), summary.totalCollected());
        assertEquals(new BigDecimal("66.66"), summary.totalPending());
        assertEquals(3, summary.rows().size());

        assertEquals(SettlementStatus.PAYER, summary.rows().get(0).status());
        assertEquals(SettlementStatus.PAID, summary.rows().get(1).status());
        assertEquals(new BigDecimal("0.00"), summary.rows().get(1).pendingAmount());
        assertEquals(SettlementStatus.PARTIAL, summary.rows().get(2).status());
        assertEquals(new BigDecimal("66.66"), summary.rows().get(2).pendingAmount());

        // When Ben pays the remaining 66.66 -> Pending drops to 0.00
        Contribution benRest = new Contribution(3, sub, ben, payment, 66.66, LocalDate.of(2026, 11, 24));
        DistributionSummary settled = SplitAllocator.computeDistribution(
                payment, List.of(ana, ben), List.of(anaPaid, benPartial, benRest));
        assertEquals(new BigDecimal("0.00"), settled.totalPending());
        assertEquals(SettlementStatus.PAID, settled.rows().get(2).status());
    }

    @Test
    void memberAsDesignatedPayerAndMetadataCodecPreserveUserNotes() {
        Subscription sub = familyPlan(600.00);
        MemberSplitMeta anaMeta = new MemberSplitMeta(true, new BigDecimal("250.00"), true, SplitMode.CUSTOM);
        MemberSplitMeta benMeta = new MemberSplitMeta(false, new BigDecimal("150.00"), true, SplitMode.CUSTOM);

        String anaEncoded = SplitAllocator.encodeMemberNotes("Older sister", anaMeta);
        String benEncoded = SplitAllocator.encodeMemberNotes(null, benMeta);

        assertEquals("Older sister", SplitAllocator.cleanNotes(anaEncoded));
        assertNull(SplitAllocator.cleanNotes(benEncoded));
        assertEquals(anaMeta, SplitAllocator.parseMemberMeta(anaEncoded));
        assertEquals(benMeta, SplitAllocator.parseMemberMeta(benEncoded));

        Member ana = new Member(21, sub, "Ana", anaEncoded);
        Member ben = new Member(22, sub, "Ben", benEncoded);
        SplitConfig resolved = SplitAllocator.resolveSubscriptionSplit(sub, List.of(ana, ben));

        assertEquals(SplitMode.CUSTOM, resolved.mode());
        assertEquals(SplitAllocator.memberKey(21), resolved.payerKey());
        // Owner share = 600 - (250 + 150) = 200
        assertEquals(new BigDecimal("200.00"), resolved.shares().get(0).share());
        assertEquals(new BigDecimal("250.00"), resolved.shares().get(1).share());
        assertTrue(resolved.shares().get(1).payer());
        assertEquals(new BigDecimal("150.00"), resolved.shares().get(2).share());
    }

    @Test
    void paymentMetadataRoundTripsSnapshotAndCleanNotes() {
        Map<String, BigDecimal> snap = new LinkedHashMap<>();
        snap.put(SplitAllocator.OWNER_KEY, new BigDecimal("200.00"));
        snap.put("member:5", new BigDecimal("300.00"));
        PaymentSplitMeta meta = new PaymentSplitMeta("member:5", false, snap);

        String encoded = SplitAllocator.encodePaymentNotes("Paid via GCash #991", meta);
        assertEquals("Paid via GCash #991", SplitAllocator.cleanNotes(encoded));

        PaymentSplitMeta decoded = SplitAllocator.parsePaymentMeta(encoded);
        assertEquals("member:5", decoded.payerKey());
        assertFalse(decoded.splitShareOnly());
        assertEquals(snap, decoded.shareSnapshot());
    }

    @Test
    void accountOwnerContributionIsTrackedWhenMemberIsPrimaryPayer() {
        Subscription sub = familyPlan(600.00);
        Member ana = new Member(21, sub, "Ana",
                SplitAllocator.encodeMemberNotes(null, new MemberSplitMeta(true, null, true, SplitMode.EQUAL)));
        Member ben = new Member(22, sub, "Ben", null);
        Member ownerRec = new Member(23, sub, SplitAllocator.OWNER_LABEL, SplitAllocator.encodeOwnerMemberNotes());

        // Owner record must not be double-counted as a 4th participant
        SplitConfig config = SplitAllocator.resolveSubscriptionSplit(sub, List.of(ana, ben, ownerRec));
        assertEquals(3, config.shares().size());
        assertEquals(SplitAllocator.memberKey(21), config.payerKey());

        Payment payment = new Payment(200, sub, LocalDate.of(2026, 11, 23), 600.00, "November 2026", "GCash",
                SplitAllocator.encodePaymentNotes(null, new PaymentSplitMeta(SplitAllocator.memberKey(21), false, Map.of())));

        // Before owner pays: Account Owner is Pending (200.00)
        DistributionSummary before = SplitAllocator.computeDistribution(payment, List.of(ana, ben, ownerRec), List.of());
        assertEquals(SettlementStatus.PENDING, before.rows().get(0).status());
        assertEquals(new BigDecimal("200.00"), before.rows().get(0).pendingAmount());

        // Owner pays 100.00 -> Partial; then remaining 100.00 -> Paid
        Contribution ownerPart1 = new Contribution(1, sub, ownerRec, payment, 100.00, LocalDate.of(2026, 11, 23));
        DistributionSummary partial = SplitAllocator.computeDistribution(payment, List.of(ana, ben, ownerRec), List.of(ownerPart1));
        assertEquals(SettlementStatus.PARTIAL, partial.rows().get(0).status());
        assertEquals(new BigDecimal("100.00"), partial.rows().get(0).pendingAmount());

        Contribution ownerPart2 = new Contribution(2, sub, ownerRec, payment, 100.00, LocalDate.of(2026, 11, 23));
        DistributionSummary paid = SplitAllocator.computeDistribution(payment, List.of(ana, ben, ownerRec), List.of(ownerPart1, ownerPart2));
        assertEquals(SettlementStatus.PAID, paid.rows().get(0).status());
        assertEquals(new BigDecimal("0.00"), paid.rows().get(0).pendingAmount());
    }
}
