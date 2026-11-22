package com.beenewed.backend;

import com.beenewed.backend.model.BillingCycle;
import com.beenewed.backend.model.Subscription;
import com.beenewed.backend.model.SubscriptionStatus;
import com.beenewed.backend.model.Theme;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BackendContextTest {
    @Test
    void wiresServicesAndSupportsBasicInMemoryUse() throws Exception {
        try (BackendContext context = new BackendContext("jdbc:sqlite::memory:")) {
            assertNotNull(context.getSubscriptionService());
            assertNotNull(context.getPaymentService());
            assertNotNull(context.getMemberService());
            assertNotNull(context.getContributionService());
            assertNotNull(context.getUsageLogService());
            assertNotNull(context.getPriceHistoryService());
            assertNotNull(context.getAppPreferencesService());
            assertNotNull(context.getReportService());
            assertNotNull(context.getDashboardService());

            assertEquals("PHP", context.getAppPreferencesService().get().currency());
            assertEquals(Theme.LIGHT, context.getAppPreferencesService().get().theme());

            Subscription subscription = context.getSubscriptionService().create(new Subscription(
                    null,
                    "Netflix",
                    "Entertainment",
                    null,
                    new BigDecimal("549.00"),
                    BillingCycle.MONTHLY,
                    LocalDate.now().plusMonths(1),
                    SubscriptionStatus.ACTIVE,
                    null,
                    null));

            assertEquals(1, context.getSubscriptionService().findAll().size());
            assertEquals(subscription.id(), context.getSubscriptionService()
                    .findAll().get(0).id());
        }
    }
}
