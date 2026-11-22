# Beenewed
A desktop application helps individuals, households and small offices organize recurring subscriptions. It tracks renewal schedules, payment records, costs and usage, and provides reminders and spending summaries to support decisions about keeping, changing or cancelling services.

## Backend Usage & Frontend Handoff

Use `BackendContext` as the backend entry point. Its default constructor initializes the local `beenewed.db` SQLite database; the JDBC URL constructor supports an in-memory database for tests. Close the context when the application shuts down so its connection is released.

```java
import com.beenewed.backend.BackendContext;
import com.beenewed.backend.model.AppPreferences;
import com.beenewed.backend.model.BillingCycle;
import com.beenewed.backend.model.Subscription;
import com.beenewed.backend.model.SubscriptionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class BackendExample {
    public static void main(String[] args) throws Exception {
        try (BackendContext backend = new BackendContext()) {
            AppPreferences preferences = backend.getAppPreferencesService().get();
            Subscription subscription = backend.getSubscriptionService().create(new Subscription(
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
            System.out.println(preferences.currency() + ": " + subscription.id());
        }
    }
}
```

The snippet uses `com.beenewed.backend.BackendContext`, the model types under `com.beenewed.backend.model`, and `java.math.BigDecimal` / `java.time.LocalDate`. `BackendContext` constructors may throw `SQLException` or `IOException`; close it with try-with-resources.

### Service API

| Service | Main methods and return types |
| --- | --- |
| `SubscriptionService` | `create(Subscription)`, `getById(Long)`, `update(Subscription)`, `pause(Long)`, `cancel(Long)`, `reactivate(Long)`, and `reactivate(Long, LocalDate)` return `Subscription`; `findAll()` and `findFiltered(String, String, SubscriptionStatus)` return `List<Subscription>`; `delete(Long)` returns `boolean`; `calculateNextRenewalDate(LocalDate, BillingCycle)` returns `LocalDate`. |
| `PaymentService` | `recordPayment(Payment)`, `updatePayment(Payment)`, and `getById(Long)` return `Payment`; `findAll()`, `findBySubscriptionId(Long)`, `findByDateRange(LocalDate, LocalDate)`, and `findFiltered(Long, LocalDate, LocalDate, SubscriptionStatus, String)` return `List<Payment>`; `deletePayment(Long)` returns `boolean`. |
| `MemberService` | `addMember(Long, Member)`, `getMember(Long, Long)`, and `updateMember(Long, Member)` return `Member`; `findBySubscriptionId(Long)` returns `List<Member>`; `deleteMember(Long, Long)` returns `boolean`. |
| `ContributionService` | `recordContribution(Long, Contribution)`, `getContribution(Long, Long)`, and `updateContribution(Long, Contribution)` return `Contribution`; `findByPaymentId(Long)` returns `List<Contribution>`; `deleteContribution(Long, Long)` returns `boolean`. |
| `UsageLogService` | `addUsageLog(Long, UsageLog)`, `getUsageLog(Long, Long)`, and `updateUsageLog(Long, UsageLog)` return `UsageLog`; `findBySubscriptionId(Long)` returns `List<UsageLog>`; `deleteUsageLog(Long, Long)` returns `boolean`. |
| `PriceHistoryService` | `recordPriceChange(Long, BigDecimal, LocalDate, String)` returns `PriceHistory`; `findBySubscriptionId(Long)` returns `List<PriceHistory>`. |
| `AppPreferencesService` | `get()` and `update(AppPreferences)` return `AppPreferences`. |
| `ReportService` | `actualPaidForMonth(YearMonth)`, `actualPaidForYear(Year)`, and `actualPaidForRange(LocalDate, LocalDate)` return `ReportService.ActualPaidReport` with a total and category/subscription breakdowns. |
| `DashboardService` | `getSummary()` returns `DashboardService.DashboardSummary` with active counts, recurring estimates, current-month actual paid, recent payments, and renewal reminders. |

Methods that access SQLite may throw `SQLException`. Catch `ValidationException` to present invalid input, and `NotFoundException` when a requested entity or singleton preference is missing. Handle `SQLException` as a persistence error; catch `IOException` during context initialization when reporting bootstrap failures.

### Backend rules exposed to the UI

- Pass money as `BigDecimal`; do not use `double` or `float` for monetary values.
- Recording or editing a payment never advances `next_renewal_date`. Use the subscription renewal-date helper only when the user explicitly advances or reactivates a schedule.
- Actual Paid sums recorded payment amounts by `payment_date`; it excludes upcoming renewals and member contributions. Current estimated recurring costs use active subscriptions only.
- Contributions are capped by their payment amount and are not subscription expenses. A member must belong to the subscription attached to the payment.
- Paused and cancelled subscriptions retain their renewal date and are excluded from active estimates/reminders. Reactivation preserves the stored date unless an explicit replacement date is supplied.
- Renewal reminders use the saved lead-day preference; overdue reminders are returned only when enabled.

Run the backend tests locally with:

```sh
mvn test
```
