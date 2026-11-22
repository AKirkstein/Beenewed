package com.beenewed.backend;

import com.beenewed.backend.config.DatabaseConfig;
import com.beenewed.backend.dao.AppPreferencesDao;
import com.beenewed.backend.dao.ContributionDao;
import com.beenewed.backend.dao.MemberDao;
import com.beenewed.backend.dao.PaymentDao;
import com.beenewed.backend.dao.PriceHistoryDao;
import com.beenewed.backend.dao.SubscriptionDao;
import com.beenewed.backend.dao.UsageLogDao;
import com.beenewed.backend.services.AppPreferencesService;
import com.beenewed.backend.services.ContributionService;
import com.beenewed.backend.services.DashboardService;
import com.beenewed.backend.services.MemberService;
import com.beenewed.backend.services.PaymentService;
import com.beenewed.backend.services.PriceHistoryService;
import com.beenewed.backend.services.ReportService;
import com.beenewed.backend.services.SubscriptionService;
import com.beenewed.backend.services.UsageLogService;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;

public final class BackendContext implements AutoCloseable {
    private final Connection connection;

    private final SubscriptionDao subscriptionDao;
    private final PaymentDao paymentDao;
    private final MemberDao memberDao;
    private final ContributionDao contributionDao;
    private final UsageLogDao usageLogDao;
    private final PriceHistoryDao priceHistoryDao;
    private final AppPreferencesDao appPreferencesDao;

    private final SubscriptionService subscriptionService;
    private final PaymentService paymentService;
    private final MemberService memberService;
    private final ContributionService contributionService;
    private final UsageLogService usageLogService;
    private final PriceHistoryService priceHistoryService;
    private final AppPreferencesService appPreferencesService;
    private final ReportService reportService;
    private final DashboardService dashboardService;

    public BackendContext() throws SQLException, IOException {
        this(new DatabaseConfig());
    }

    public BackendContext(String jdbcUrl) throws SQLException, IOException {
        this(new DatabaseConfig(jdbcUrl));
    }

    private BackendContext(DatabaseConfig databaseConfig) throws SQLException, IOException {
        connection = databaseConfig.initialize();

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
        priceHistoryService = new PriceHistoryService(connection, priceHistoryDao, subscriptionDao);
        appPreferencesService = new AppPreferencesService(appPreferencesDao);
        reportService = new ReportService(paymentDao, subscriptionDao);
        dashboardService = new DashboardService(
                Clock.systemDefaultZone(),
                subscriptionDao,
                paymentDao,
                reportService,
                appPreferencesService);
    }

    public SubscriptionService getSubscriptionService() {
        return subscriptionService;
    }

    public PaymentService getPaymentService() {
        return paymentService;
    }

    public MemberService getMemberService() {
        return memberService;
    }

    public ContributionService getContributionService() {
        return contributionService;
    }

    public UsageLogService getUsageLogService() {
        return usageLogService;
    }

    public PriceHistoryService getPriceHistoryService() {
        return priceHistoryService;
    }

    public AppPreferencesService getAppPreferencesService() {
        return appPreferencesService;
    }

    public ReportService getReportService() {
        return reportService;
    }

    public DashboardService getDashboardService() {
        return dashboardService;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
