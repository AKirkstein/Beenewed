package com.beenewed.backend.service;

import com.beenewed.backend.config.DatabaseConfig;
import com.beenewed.backend.dao.AppPreferencesDao;
import com.beenewed.backend.dao.ContributionDao;
import com.beenewed.backend.dao.MemberDao;
import com.beenewed.backend.dao.PaymentDao;
import com.beenewed.backend.dao.PriceHistoryDao;
import com.beenewed.backend.dao.SubscriptionDao;
import com.beenewed.backend.dao.UsageLogDao;
import com.beenewed.backend.exception.ValidationException;
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
import com.beenewed.backend.services.AppPreferencesService;
import com.beenewed.backend.services.ContributionService;
import com.beenewed.backend.services.DashboardService;
import com.beenewed.backend.services.MemberService;
import com.beenewed.backend.services.PaymentService;
import com.beenewed.backend.services.PriceHistoryService;
import com.beenewed.backend.services.ReportService;
import com.beenewed.backend.services.SubscriptionService;
import com.beenewed.backend.services.UsageLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceIntegrationTest {
    private Connection connection;
    private SubscriptionDao subscriptionDao;
    private PaymentDao paymentDao;
    private MemberDao memberDao;
    private ContributionDao contributionDao;
    private UsageLogDao usageLogDao;
    private PriceHistoryDao priceHistoryDao;
    private AppPreferencesDao appPreferencesDao;
    private SubscriptionService subscriptionService;
    private PaymentService paymentService;
    private MemberService memberService;
    private ContributionService contributionService;
    private UsageLogService usageLogService;
    private PriceHistoryService priceHistoryService;
    private AppPreferencesService appPreferencesService;
    private ReportService reportService;
    private DashboardService dashboardService;

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

        subscriptionService = new SubscriptionService(subscriptionDao);
        paymentService = new PaymentService(paymentDao, subscriptionDao, contributionDao);
        memberService = new MemberService(memberDao, subscriptionDao);
        contributionService = new ContributionService(contributionDao, paymentDao, memberDao);
        usageLogService = new UsageLogService(usageLogDao, subscriptionDao);
        priceHistoryService = new PriceHistoryService(connection);
        appPreferencesService = new AppPreferencesService(appPreferencesDao);
        reportService = new ReportService(paymentDao, subscriptionDao);
        dashboardService = new DashboardService(
                Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC),
                subscriptionDao,
                paymentDao,
                reportService,
                appPreferencesService);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @Test
    void subscriptionLifecyclePreservesAndExplicitlyUpdatesRenewalDate() throws Exception {
        LocalDate originalRenewalDate = LocalDate.parse("2026-01-31");
        Subscription subscription = createSubscription(
                "Netflix", "Entertainment", "120.00", BillingCycle.MONTHLY,
                originalRenewalDate, SubscriptionStatus.ACTIVE);

        Subscription paused = subscriptionService.pause(subscription.id());
        assertEquals(SubscriptionStatus.PAUSED, paused.status());
        assertEquals(originalRenewalDate, paused.nextRenewalDate());

        Subscription cancelled = subscriptionService.cancel(subscription.id());
        assertEquals(SubscriptionStatus.CANCELLED, cancelled.status());
        assertEquals(originalRenewalDate, cancelled.nextRenewalDate());

        LocalDate replacementDate = LocalDate.parse("2026-02-28");
        Subscription reactivated = subscriptionService.reactivate(subscription.id(), replacementDate);
        assertEquals(SubscriptionStatus.ACTIVE, reactivated.status());
        assertEquals(replacementDate, reactivated.nextRenewalDate());
        assertThrows(ValidationException.class, () -> subscriptionService.reactivate(subscription.id()));

        Subscription second = createSubscription(
                "Cloud", "Storage", "99.00", BillingCycle.MONTHLY,
                originalRenewalDate, SubscriptionStatus.ACTIVE);
        subscriptionService.pause(second.id());
        assertEquals(originalRenewalDate, subscriptionService.reactivate(second.id()).nextRenewalDate());

        assertEquals(LocalDate.parse("2024-02-29"),
                SubscriptionService.calculateNextRenewalDate(
                        LocalDate.parse("2024-01-31"), BillingCycle.MONTHLY));
        assertEquals(LocalDate.parse("2025-02-28"),
                SubscriptionService.calculateNextRenewalDate(
                        LocalDate.parse("2024-02-29"), BillingCycle.YEARLY));
    }

    @Test
    void subscriptionAndPaymentValidationAndPaymentRecordingDoNotAdvanceRenewal() throws Exception {
        assertThrows(ValidationException.class, () -> createSubscription(
                " ", "Entertainment", "10.00", BillingCycle.MONTHLY,
                LocalDate.parse("2026-12-01"), SubscriptionStatus.ACTIVE));
        assertThrows(ValidationException.class, () -> createSubscription(
                "Invalid", "Entertainment", "0.00", BillingCycle.MONTHLY,
                LocalDate.parse("2026-12-01"), SubscriptionStatus.ACTIVE));

        LocalDate renewalDate = LocalDate.parse("2026-12-01");
        Subscription subscription = createSubscription(
                "Netflix", "Entertainment", "120.00", BillingCycle.MONTHLY,
                renewalDate, SubscriptionStatus.ACTIVE);

        assertThrows(ValidationException.class, () -> paymentService.recordPayment(
                new Payment(null, subscription.id(), LocalDate.now(), BigDecimal.ZERO,
                        "November 2026", null, null, null)));
        assertThrows(ValidationException.class, () -> paymentService.recordPayment(
                new Payment(null, subscription.id(), LocalDate.now(), BigDecimal.ONE,
                        "  ", null, null, null)));

        Payment payment = createPayment(
                subscription.id(), "2026-11-22", "120.00", "November 2026");
        assertEquals(renewalDate, subscriptionService.getById(subscription.id()).nextRenewalDate());
        assertEquals(payment.id(), paymentService.getById(payment.id()).id());
    }

    @Test
    void contributionServiceEnforcesCapForCreateAndEditAndPaymentEdits() throws Exception {
        Subscription subscription = createSubscription(
                "Netflix", "Entertainment", "120.00", BillingCycle.MONTHLY,
                LocalDate.parse("2026-12-01"), SubscriptionStatus.ACTIVE);
        Subscription otherSubscription = createSubscription(
                "Cloud", "Storage", "99.00", BillingCycle.MONTHLY,
                LocalDate.parse("2026-12-10"), SubscriptionStatus.ACTIVE);
        Member member = addMember(subscription, "Alex");
        Member unrelatedMember = addMember(otherSubscription, "Jamie");
        Payment payment = createPayment(subscription.id(), "2026-11-05", "100.00", "November 2026");

        Contribution first = recordContribution(payment, member, "60.00");
        recordContribution(payment, member, "30.00");
        assertThrows(ValidationException.class,
                () -> recordContribution(payment, member, "11.00"));
        assertThrows(ValidationException.class, () -> contributionService.recordContribution(
                payment.id(),
                new Contribution(null, unrelatedMember.id(), payment.id(),
                        new BigDecimal("1.00"), LocalDate.parse("2026-11-06"), null)));
        assertThrows(ValidationException.class, () -> contributionService.recordContribution(
                payment.id(),
                new Contribution(null, member.id(), payment.id() + 1,
                        new BigDecimal("1.00"), LocalDate.parse("2026-11-06"), null)));
        assertThrows(ValidationException.class, () -> contributionService.recordContribution(
                payment.id(),
                new Contribution(null, member.id(), payment.id(),
                        BigDecimal.ZERO, LocalDate.parse("2026-11-06"), null)));

        Contribution adjusted = new Contribution(
                first.id(), member.id(), payment.id(), new BigDecimal("70.00"),
                first.dateReceived(), first.createdAt());
        contributionService.updateContribution(payment.id(), adjusted);
        assertEquals(new BigDecimal("100.00"), contributionDao.sumForPayment(payment.id()));

        Contribution overLimit = new Contribution(
                first.id(), member.id(), payment.id(), new BigDecimal("71.00"),
                first.dateReceived(), first.createdAt());
        assertThrows(ValidationException.class,
                () -> contributionService.updateContribution(payment.id(), overLimit));
        assertEquals(new BigDecimal("100.00"), contributionDao.sumForPayment(payment.id()));

        Payment reducedPayment = new Payment(
                payment.id(), payment.subscriptionId(), payment.paymentDate(), new BigDecimal("99.00"),
                payment.renewalPeriod(), payment.paymentMethod(), payment.notes(), payment.createdAt());
        assertThrows(ValidationException.class, () -> paymentService.updatePayment(reducedPayment));

        Payment movedPayment = new Payment(
                payment.id(), otherSubscription.id(), payment.paymentDate(), payment.amountPaid(),
                payment.renewalPeriod(), payment.paymentMethod(), payment.notes(), payment.createdAt());
        assertThrows(ValidationException.class, () -> paymentService.updatePayment(movedPayment));
    }

    @Test
    void memberUsageAndPreferencesValidation() throws Exception {
        Subscription subscription = createSubscription(
                "Netflix", "Entertainment", "120.00", BillingCycle.MONTHLY,
                LocalDate.parse("2026-12-01"), SubscriptionStatus.ACTIVE);

        assertThrows(ValidationException.class, () -> memberService.addMember(
                subscription.id(),
                new Member(null, subscription.id(), " ", null, null, null)));
        Member member = addMember(subscription, "Alex");
        assertEquals(subscription.id(), member.subscriptionId());

        assertThrows(ValidationException.class, () -> usageLogService.addUsageLog(
                subscription.id(),
                new UsageLog(null, subscription.id(), LocalDate.parse("2026-11-01"),
                        0.0, "Hours", null, null)));
        assertThrows(ValidationException.class, () -> usageLogService.addUsageLog(
                subscription.id(),
                new UsageLog(null, subscription.id(), LocalDate.parse("2026-11-01"),
                        1.0, null, null, null)));
        assertThrows(ValidationException.class, () -> usageLogService.addUsageLog(
                subscription.id(),
                new UsageLog(null, subscription.id(), LocalDate.parse("2026-11-01"),
                        1.0, " ", null, null)));
        UsageLog usageWithoutQuantity = usageLogService.addUsageLog(
                subscription.id(),
                new UsageLog(null, subscription.id(), LocalDate.parse("2026-11-01"),
                        null, null, "Manual note", null));
        assertNull(usageWithoutQuantity.quantity());

        AppPreferences defaults = appPreferencesService.get();
        assertThrows(ValidationException.class, () -> appPreferencesService.update(
                new AppPreferences(defaults.id(), defaults.currency(), Theme.LIGHT, -1, true)));
        assertEquals(defaults, appPreferencesService.get());
    }

    @Test
    void priceChangeUpdatesHistoryAndCurrentPriceWithoutChangingPayments() throws Exception {
        Subscription subscription = createSubscription(
                "Netflix", "Entertainment", "120.00", BillingCycle.MONTHLY,
                LocalDate.parse("2026-12-01"), SubscriptionStatus.ACTIVE);
        Payment payment = createPayment(subscription.id(), "2026-11-01", "115.00", "November 2026");

        assertThrows(ValidationException.class, () -> priceHistoryService.recordPriceChange(
                subscription.id(),
                BigDecimal.ZERO,
                LocalDate.parse("2026-12-01"),
                null));
        PriceHistory history = priceHistoryService.recordPriceChange(
                subscription.id(),
                new BigDecimal("130.00"),
                LocalDate.parse("2026-12-01"),
                "Annual price update");

        assertEquals(new BigDecimal("120.00"), history.previousPrice());
        assertEquals(new BigDecimal("130.00"), history.newPrice());
        assertEquals(new BigDecimal("130.00"),
                subscriptionService.getById(subscription.id()).currentPrice());
        assertEquals(new BigDecimal("115.00"), paymentService.getById(payment.id()).amountPaid());
        assertEquals(1, priceHistoryService.findBySubscriptionId(subscription.id()).size());
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void failedPriceUpdateRollsBackInsertedHistory() throws Exception {
        Subscription subscription = createSubscription(
                "Netflix", "Entertainment", "120.00", BillingCycle.MONTHLY,
                LocalDate.parse("2026-12-01"), SubscriptionStatus.ACTIVE);
        try (PreparedStatement statement = connection.prepareStatement("""
                CREATE TRIGGER reject_price_update
                BEFORE UPDATE OF current_price ON subscriptions
                BEGIN
                    SELECT RAISE(ABORT, 'price update rejected');
                END
                """)) {
            statement.execute();
        }

        assertThrows(SQLException.class, () -> priceHistoryService.recordPriceChange(
                subscription.id(),
                new BigDecimal("130.00"),
                LocalDate.parse("2026-12-01"),
                null));
        assertEquals(new BigDecimal("120.00"),
                subscriptionService.getById(subscription.id()).currentPrice());
        assertTrue(priceHistoryService.findBySubscriptionId(subscription.id()).isEmpty());
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void reportsAndDashboardSeparateActualPaidEstimatesAndReminderStates() throws Exception {
        Subscription monthly = createSubscription(
                "Netflix", "Entertainment", "120.00", BillingCycle.MONTHLY,
                LocalDate.parse("2026-11-22"), SubscriptionStatus.ACTIVE);
        Subscription yearly = createSubscription(
                "Cloud", "Storage", "1000.00", BillingCycle.YEARLY,
                LocalDate.parse("2026-11-29"), SubscriptionStatus.ACTIVE);
        Subscription smallYearlyOne = createSubscription(
                "Annual A", "Tools", "10.00", BillingCycle.YEARLY,
                LocalDate.parse("2026-12-20"), SubscriptionStatus.ACTIVE);
        Subscription smallYearlyTwo = createSubscription(
                "Annual B", "Tools", "10.00", BillingCycle.YEARLY,
                LocalDate.parse("2026-12-20"), SubscriptionStatus.ACTIVE);
        Subscription overdue = createSubscription(
                "Overdue", "Tools", "12.00", BillingCycle.MONTHLY,
                LocalDate.parse("2026-11-21"), SubscriptionStatus.ACTIVE);
        Subscription paused = createSubscription(
                "Paused", "Other", "999.00", BillingCycle.MONTHLY,
                LocalDate.parse("2026-11-22"), SubscriptionStatus.PAUSED);

        for (int day = 1; day <= 6; day++) {
            createPayment(monthly.id(), String.format("2026-11-%02d", day), "10.00", "November 2026");
        }
        Payment cloudPayment = createPayment(yearly.id(), "2026-11-05", "50.00", "2026");
        createPayment(paused.id(), "2026-11-10", "7.00", "November 2026");
        createPayment(monthly.id(), "2026-10-31", "99.00", "October 2026");
        Member cloudMember = addMember(yearly, "Sam");
        recordContribution(cloudPayment, cloudMember, "20.00");

        ReportService.ActualPaidReport range = reportService.actualPaidForRange(
                LocalDate.parse("2026-11-02"),
                LocalDate.parse("2026-11-05"));
        assertEquals(new BigDecimal("90.00"), range.totalPaid());
        assertEquals(new BigDecimal("40.00"), categoryTotal(range, "Entertainment"));
        assertEquals(new BigDecimal("50.00"), categoryTotal(range, "Storage"));
        assertEquals(new BigDecimal("40.00"), subscriptionTotal(range, monthly.id()));
        assertEquals(new BigDecimal("50.00"), subscriptionTotal(range, yearly.id()));
        assertEquals(new BigDecimal("117.00"),
                reportService.actualPaidForMonth(YearMonth.of(2026, 11)).totalPaid());
        assertEquals(new BigDecimal("216.00"),
                reportService.actualPaidForYear(Year.of(2026)).totalPaid());

        DashboardService.DashboardSummary summary = dashboardService.getSummary();
        assertEquals(5, summary.activeSubscriptionCount());
        assertEquals(new BigDecimal("216.99"), summary.estimatedMonthlyCost());
        assertEquals(new BigDecimal("2604.00"), summary.estimatedAnnualCost());
        assertEquals(new BigDecimal("117.00"), summary.currentMonthActualPaid());
        assertEquals(5, summary.recentPayments().size());
        assertEquals(2, summary.upcomingRenewals().size());
        assertEquals(List.of(monthly.id(), yearly.id()), summary.upcomingRenewals().stream()
                .map(DashboardService.RenewalReminder::subscriptionId)
                .toList());
        assertEquals(1, summary.overdueRenewals().size());
        assertEquals(overdue.id(), summary.overdueRenewals().get(0).subscriptionId());
        assertFalse(summary.upcomingRenewals().stream()
                .anyMatch(reminder -> reminder.subscriptionId().equals(paused.id())));

        AppPreferences preferences = appPreferencesService.get();
        appPreferencesService.update(new AppPreferences(
                preferences.id(), preferences.currency(), preferences.theme(),
                preferences.reminderLeadDays(), false));
        assertTrue(dashboardService.getSummary().overdueRenewals().isEmpty());
    }

    private Subscription createSubscription(
            String name,
            String category,
            String price,
            BillingCycle billingCycle,
            LocalDate renewalDate,
            SubscriptionStatus status) throws SQLException {
        return subscriptionService.create(new Subscription(
                null,
                name,
                category,
                null,
                new BigDecimal(price),
                billingCycle,
                renewalDate,
                status,
                null,
                null));
    }

    private Payment createPayment(
            Long subscriptionId,
            String paymentDate,
            String amount,
            String renewalPeriod) throws SQLException {
        return paymentService.recordPayment(new Payment(
                null,
                subscriptionId,
                LocalDate.parse(paymentDate),
                new BigDecimal(amount),
                renewalPeriod,
                null,
                null,
                null));
    }

    private Member addMember(Subscription subscription, String name) throws SQLException {
        return memberService.addMember(subscription.id(),
                new Member(null, subscription.id(), name, null, null, null));
    }

    private Contribution recordContribution(
            Payment payment,
            Member member,
            String amount) throws SQLException {
        return contributionService.recordContribution(
                payment.id(),
                new Contribution(
                        null,
                        member.id(),
                        payment.id(),
                        new BigDecimal(amount),
                        payment.paymentDate().plusDays(1),
                        null));
    }

    private static BigDecimal categoryTotal(
            ReportService.ActualPaidReport report,
            String category) {
        return report.byCategory().stream()
                .filter(row -> row.category().equals(category))
                .findFirst()
                .orElseThrow()
                .amountPaid();
    }

    private static BigDecimal subscriptionTotal(
            ReportService.ActualPaidReport report,
            Long subscriptionId) {
        return report.bySubscription().stream()
                .filter(row -> row.subscriptionId().equals(subscriptionId))
                .findFirst()
                .orElseThrow()
                .amountPaid();
    }
}
