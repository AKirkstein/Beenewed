package com.beenewed.backend.dao;

import com.beenewed.backend.config.DatabaseConfig;
import com.beenewed.backend.model.AppPreferences;
import com.beenewed.backend.model.BillingCycle;
import com.beenewed.backend.model.Contribution;
import com.beenewed.backend.model.Member;
import com.beenewed.backend.model.Payment;
import com.beenewed.backend.model.PriceHistory;
import com.beenewed.backend.model.Subscription;
import com.beenewed.backend.model.SubscriptionStatus;
import com.beenewed.backend.model.Theme;
import com.beenewed.backend.model.UsageLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaoIntegrationTest {
    private Connection connection;
    private SubscriptionDao subscriptionDao;
    private PaymentDao paymentDao;
    private MemberDao memberDao;
    private ContributionDao contributionDao;
    private UsageLogDao usageLogDao;
    private PriceHistoryDao priceHistoryDao;
    private AppPreferencesDao appPreferencesDao;

    @BeforeEach
    void setUp() throws Exception {
        connection = new DatabaseConfig("jdbc:sqlite::memory:").initialize();
        subscriptionDao = new SubscriptionDao(connection);
        paymentDao = new PaymentDao(connection);
        memberDao = new MemberDao(connection);
        contributionDao = new ContributionDao(connection);
        usageLogDao = new UsageLogDao(connection);
        priceHistoryDao = new PriceHistoryDao(connection);
        appPreferencesDao = new AppPreferencesDao(connection);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void subscriptionAndPaymentCrudAndFilters() throws Exception {
        Subscription netflix = createSubscription(
                "Netflix", "Entertainment", "549.00", SubscriptionStatus.ACTIVE);
        Subscription spotify = createSubscription(
                "Spotify", "Entertainment", "149.00", SubscriptionStatus.PAUSED);

        assertTrue(subscriptionDao.findById(netflix.id()).isPresent());
        assertEquals(2, subscriptionDao.findAll().size());
        assertEquals(List.of(netflix.id()), subscriptionDao.findFiltered(
                "netflix", "Entertainment", SubscriptionStatus.ACTIVE)
                .stream().map(Subscription::id).toList());
        assertEquals(List.of(netflix.id()), subscriptionDao.findActiveSubscriptions()
                .stream().map(Subscription::id).toList());
        assertTrue(subscriptionDao.findFiltered(null, "Cloud Storage", null).isEmpty());

        Subscription changedNetflix = new Subscription(
                netflix.id(),
                "Netflix Premium",
                netflix.category(),
                netflix.iconKey(),
                netflix.currentPrice(),
                netflix.billingCycle(),
                netflix.nextRenewalDate(),
                SubscriptionStatus.PAUSED,
                netflix.createdAt(),
                netflix.updatedAt());
        assertTrue(subscriptionDao.update(changedNetflix));
        assertEquals("Netflix Premium", subscriptionDao.findById(netflix.id()).orElseThrow().name());
        assertTrue(subscriptionDao.findActiveSubscriptions().isEmpty());

        Payment novemberPayment = createPayment(
                netflix.id(), "2026-11-05", "123456789012345678.90", "November 2026");
        Payment decemberPayment = createPayment(
                netflix.id(), "2026-12-05", "549.00", "December 2026");
        Payment spotifyPayment = createPayment(
                spotify.id(), "2026-11-25", "149.00", "November 2026");

        assertEquals(2, paymentDao.findBySubscriptionId(netflix.id()).size());
        assertEquals(List.of(spotifyPayment.id(), novemberPayment.id()), paymentDao.findByDateRange(
                LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-30")).stream().map(Payment::id).toList());
        assertEquals(List.of(spotifyPayment.id()), paymentDao.findFiltered(
                null,
                null,
                null,
                SubscriptionStatus.PAUSED,
                "spot")
                .stream().map(Payment::id).toList());
        assertEquals(new BigDecimal("123456789012345678.90"),
                paymentDao.findById(novemberPayment.id()).orElseThrow().amountPaid());

        Payment changedPayment = new Payment(
                novemberPayment.id(),
                netflix.id(),
                novemberPayment.paymentDate(),
                novemberPayment.amountPaid(),
                novemberPayment.renewalPeriod(),
                "GCash",
                "Receipt 123",
                novemberPayment.createdAt());
        assertTrue(paymentDao.update(changedPayment));
        assertEquals("GCash", paymentDao.findById(novemberPayment.id()).orElseThrow().paymentMethod());
        assertTrue(paymentDao.deleteById(decemberPayment.id()));
        assertFalse(paymentDao.findById(decemberPayment.id()).isPresent());
    }

    @Test
    void memberContributionUsageAndPriceHistoryDaos() throws Exception {
        Subscription subscription = createSubscription(
                "Netflix", "Entertainment", "549.00", SubscriptionStatus.ACTIVE);
        Subscription otherSubscription = createSubscription(
                "Spotify", "Entertainment", "149.00", SubscriptionStatus.ACTIVE);
        Member member = memberDao.insert(new Member(
                null, subscription.id(), "Alex Rivera", "Household member", null, null));
        Member otherMember = memberDao.insert(new Member(
                null, otherSubscription.id(), "Jamie Santos", null, null, null));

        assertEquals(1, memberDao.findBySubscriptionId(subscription.id()).size());
        assertTrue(memberDao.findById(subscription.id(), member.id()).isPresent());
        assertFalse(memberDao.findById(otherSubscription.id(), member.id()).isPresent());
        Member changedMember = new Member(
                member.id(), subscription.id(), member.name(), "Primary account holder",
                member.createdAt(), member.updatedAt());
        assertTrue(memberDao.update(subscription.id(), changedMember));
        assertEquals("Primary account holder",
                memberDao.findById(subscription.id(), member.id()).orElseThrow().roleNotes());

        Payment payment = createPayment(
                subscription.id(), "2026-11-05", "123456789012345678.90", "November 2026");
        Contribution first = contributionDao.insert(new Contribution(
                null, member.id(), payment.id(), new BigDecimal("123456789012345678.89"),
                LocalDate.parse("2026-11-06"), null));
        Contribution second = contributionDao.insert(new Contribution(
                null, member.id(), payment.id(), new BigDecimal("0.01"),
                LocalDate.parse("2026-11-07"), null));

        assertEquals(2, contributionDao.findByPaymentId(payment.id()).size());
        assertEquals(new BigDecimal("123456789012345678.90"),
                contributionDao.sumForPayment(payment.id()));
        Contribution changedContribution = new Contribution(
                first.id(), member.id(), payment.id(), new BigDecimal("123456789012345678.88"),
                first.dateReceived(), first.createdAt());
        assertTrue(contributionDao.update(payment.id(), changedContribution));
        assertEquals(new BigDecimal("123456789012345678.89"),
                contributionDao.sumForPayment(payment.id()));
        assertTrue(contributionDao.deleteById(payment.id(), second.id()));
        assertFalse(contributionDao.findById(payment.id(), second.id()).isPresent());

        UsageLog usageWithoutQuantity = usageLogDao.insert(new UsageLog(
                null, subscription.id(), LocalDate.parse("2026-11-18"), null, null, "Watched a series", null));
        UsageLog usageWithQuantity = usageLogDao.insert(new UsageLog(
                null, subscription.id(), LocalDate.parse("2026-11-16"), 2.0, "Hours", null, null));
        assertEquals(2, usageLogDao.findBySubscriptionId(subscription.id()).size());
        assertNull(usageLogDao.findById(subscription.id(), usageWithoutQuantity.id())
                .orElseThrow().quantity());
        assertNotNull(usageLogDao.findById(subscription.id(), usageWithQuantity.id()).orElseThrow().createdAt());
        UsageLog changedUsage = new UsageLog(
                usageWithQuantity.id(), subscription.id(), usageWithQuantity.usageDate(),
                2.5, "Hours", "Updated entry", usageWithQuantity.createdAt());
        assertTrue(usageLogDao.update(subscription.id(), changedUsage));
        assertEquals(Double.valueOf(2.5), usageLogDao.findById(subscription.id(), usageWithQuantity.id())
                .orElseThrow().quantity());
        assertTrue(usageLogDao.deleteById(subscription.id(), usageWithoutQuantity.id()));

        PriceHistory olderChange = priceHistoryDao.insert(new PriceHistory(
                null, subscription.id(), new BigDecimal("499.00"), new BigDecimal("529.00"),
                LocalDate.parse("2026-10-01"), "October change", null));
        PriceHistory newerChange = priceHistoryDao.insert(new PriceHistory(
                null, subscription.id(), new BigDecimal("529.00"), new BigDecimal("549.00"),
                LocalDate.parse("2026-11-01"), "November change", null));
        List<PriceHistory> history = priceHistoryDao.findBySubscriptionId(subscription.id());
        assertEquals(List.of(newerChange.id(), olderChange.id()),
                history.stream().map(PriceHistory::id).toList());
        assertEquals(new BigDecimal("549.00"), history.get(0).newPrice());
        assertEquals(0, priceHistoryDao.findBySubscriptionId(otherSubscription.id()).size());
        assertTrue(memberDao.deleteById(otherSubscription.id(), otherMember.id()));
    }

    @Test
    void preferencesAreReadAndUpdatedOnSingletonRow() throws Exception {
        AppPreferences defaults = appPreferencesDao.get().orElseThrow();
        assertEquals(Long.valueOf(1L), defaults.id());
        assertEquals("PHP", defaults.currency());
        assertEquals(Theme.LIGHT, defaults.theme());
        assertEquals(7, defaults.reminderLeadDays());
        assertTrue(defaults.showOverdueReminders());

        AppPreferences updated = new AppPreferences(
                defaults.id(), "PHP", Theme.DARK, 14, false);
        assertTrue(appPreferencesDao.update(updated));
        assertEquals(updated, appPreferencesDao.get().orElseThrow());
    }

    @Test
    void foreignKeysRestrictFinancialDeletesAndCascadeNonFinancialRows() throws Exception {
        Subscription withPayment = createSubscription(
                "Netflix", "Entertainment", "549.00", SubscriptionStatus.ACTIVE);
        Member member = memberDao.insert(new Member(
                null, withPayment.id(), "Alex Rivera", null, null, null));
        Payment payment = createPayment(
                withPayment.id(), "2026-11-05", "549.00", "November 2026");
        contributionDao.insert(new Contribution(
                null, member.id(), payment.id(), new BigDecimal("100.00"),
                LocalDate.parse("2026-11-06"), null));

        assertThrows(SQLException.class, () -> paymentDao.deleteById(payment.id()));
        assertThrows(SQLException.class, () -> subscriptionDao.deleteById(withPayment.id()));

        Subscription withoutPayment = createSubscription(
                "Unused", "Other", "10.00", SubscriptionStatus.CANCELLED);
        Member cascadeMember = memberDao.insert(new Member(
                null, withoutPayment.id(), "Jamie Santos", null, null, null));
        UsageLog usage = usageLogDao.insert(new UsageLog(
                null, withoutPayment.id(), LocalDate.parse("2026-11-18"), null, null, null, null));
        PriceHistory history = priceHistoryDao.insert(new PriceHistory(
                null, withoutPayment.id(), new BigDecimal("10.00"), new BigDecimal("12.00"),
                LocalDate.parse("2026-11-20"), null, null));

        assertTrue(subscriptionDao.deleteById(withoutPayment.id()));
        assertFalse(memberDao.findById(withoutPayment.id(), cascadeMember.id()).isPresent());
        assertFalse(usageLogDao.findById(withoutPayment.id(), usage.id()).isPresent());
        assertTrue(priceHistoryDao.findBySubscriptionId(withoutPayment.id()).isEmpty());
        assertTrue(paymentDao.findById(payment.id()).isPresent());
    }

    private Subscription createSubscription(
            String name,
            String category,
            String price,
            SubscriptionStatus status) throws SQLException {
        return subscriptionDao.insert(new Subscription(
                null,
                name,
                category,
                "default",
                new BigDecimal(price),
                BillingCycle.MONTHLY,
                LocalDate.parse("2026-12-05"),
                status,
                null,
                null));
    }

    private Payment createPayment(
            Long subscriptionId,
            String paymentDate,
            String amount,
            String renewalPeriod) throws SQLException {
        return paymentDao.insert(new Payment(
                null,
                subscriptionId,
                LocalDate.parse(paymentDate),
                new BigDecimal(amount),
                renewalPeriod,
                null,
                null,
                null));
    }
}
