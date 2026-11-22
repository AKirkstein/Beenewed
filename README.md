# Beenewed — Subscription Tracker (Java Swing + SQLite)

A desktop application built with Java 17 Swing, FlatLaf, and SQLite that helps individuals, households, and small offices organize recurring subscriptions. It tracks renewal schedules, payment records, sharing contributions, usage logs, and price changes, and provides reminders and spending summaries to support decisions about keeping, changing, or cancelling services.

## Prerequisites

- **Java 17+** (JDK 17 or newer to build; JRE/JDK 17+ to run the standalone JAR)
- **Maven 3.8+**

## Run Locally (Maven)

Run the test suite:

```sh
mvn test
```

Launch the desktop application:

```sh
mvn exec:java "-Dexec.mainClass=beenewed.App"
```

## Build & Run Standalone JAR

Package a self-contained executable JAR (including Swing UI classes, backend services, FlatLaf, `sqlite-jdbc` native libraries, and `db/schema.sql`):

```sh
mvn clean package
```

Run the generated standalone JAR from any directory with Java 17+ installed:

```sh
java -jar target/beenewed-standalone.jar
```

> **Data persistence:** On first launch, `beenewed.db` is created automatically in the current working directory (initialized from `src/main/resources/db/schema.sql`) to persist subscriptions, payments, sharing members/contributions, usage logs, price history, reminder settings, and light/dark theme preferences.

## Features & UI

- **Screens:** Dashboard, Subscriptions, Subscription Details (Overview, Payments, Sharing, Usage, Price History), Renewals & Payments, Reports (Actual Paid and Current Estimated Recurring Cost), and Settings & Preferences.
- **Dialogs:** Add/Edit Subscription, Record/Edit Payment, Add/Edit Member, Record/Edit Contribution, Record/Edit Usage, Record Price Change, Change Status (Pause/Cancel/Reactivate), and Renewal Reminders.
- **Light / Dark mode:** Toggle instantly from the header switch or **Settings → Appearance**; your choice is persisted in `beenewed.db`.
- **Keyboard shortcuts:** `Ins` opens the New Subscription dialog; on the Subscriptions table, `↑`/`↓` selects a row and `Enter` opens its details.

## Project Layout

```text
src/
  main/
    java/
      beenewed/                  Swing UI entry point (App), Store adapter, ui/, screens/, dialogs/
      com/beenewed/backend/      BackendContext, config/, dao/, model/, services/, exception/
    resources/
      db/schema.sql              SQLite schema loaded automatically on startup
  test/
    java/com/beenewed/backend/   JUnit 5 DAO, service, and BackendContext integration tests
```

## Architecture (`BackendContext`)

`com.beenewed.backend.BackendContext` is the single entry point wiring `DatabaseConfig`, all 7 SQLite DAOs, and all 9 domain services:

- **Initialization & lifecycle:** `new BackendContext()` initializes `jdbc:sqlite:beenewed.db` in the working directory; `new BackendContext(String jdbcUrl)` supports an in-memory SQLite database for tests. `BackendContext` implements `AutoCloseable` and is closed when the main window exits.
- **Services exposed:**
  - `SubscriptionService` — CRUD, filtering, pause/cancel/reactivate, and renewal date calculation (`calculateNextRenewalDate`).
  - `PaymentService` — record, update, delete, and filter payments.
  - `MemberService` — manage members assigned to shared subscriptions.
  - `ContributionService` — record, update, and delete member contributions against payments.
  - `UsageLogService` — log and manage subscription usage entries.
  - `PriceHistoryService` — record price changes and view subscription price history.
  - `AppPreferencesService` — read and update singleton preferences (currency, reminder lead days, overdue visibility, light/dark theme).
  - `ReportService` — compute `ActualPaidReport` totals and category/subscription breakdowns by month, year, or custom date range.
  - `DashboardService` — aggregate active counts, recurring estimates, current-month actual paid, recent payments, and renewal reminders.
- **Error handling:** Service methods throw `ValidationException` for invalid input, `NotFoundException` for missing records, `SQLException` for persistence errors, and `IOException` during schema bootstrap.
- **Core backend rules:**
  - Monetary values use `BigDecimal` (never `double` or `float`).
  - Recording or editing a payment never automatically advances `next_renewal_date`.
  - Actual Paid sums recorded payment amounts by `payment_date` (excluding upcoming renewals and member contributions); recurring cost estimates include active subscriptions only.
  - Contributions are capped by their payment amount, are not counted as subscription expenses, and require the member to belong to the payment's subscription.
  - Paused and cancelled subscriptions retain their renewal date and are excluded from active estimates and reminders; reactivation preserves the stored date unless a replacement date is provided.
  - Renewal reminders respect the saved lead-day preference, and overdue items appear only when enabled.
