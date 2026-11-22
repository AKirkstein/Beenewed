# Beenewed — Subscription Tracker

Beenewed is a Java Swing desktop app for tracking recurring subscriptions, renewal dates, payments, shared member contributions, usage logs, and price changes. Each user signs in to their own account backed by **Supabase Auth** and **PostgreSQL**, so subscriptions, payment history, and app preferences stay safely synced across runs.

## Tech Stack

- **Java 17** & **Java Swing** (with FlatLaf styling and Java 17 `HttpClient`)
- **Apache Maven** (build, tests, and packaging)
- **Gson** (JSON serialization for Supabase Auth & REST requests)
- **Supabase Auth** (Email + Password sign-in and Email OTP verification on sign-up)
- **PostgreSQL** (hosted on Supabase with Row Level Security per user)

## Prerequisites & Extensions

Make sure you have the following installed before running the project:

- **JDK 17+** — check with `java -version`
- **Apache Maven (3.8+)** — check with `mvn -version`
- **VS Code (Optional):** Install the **Extension Pack for Java** (`vscjava.vscode-java-pack`) for Java IntelliSense, Maven integration, and one-click debugging.

## How to Run

### Step 1: Clone the repository

Clone the project and switch to the working branch:

```sh
git clone https://github.com/AKirkstein/Beenewed.git
cd Beenewed
git checkout feat/supabase-auth
```

### Step 2: Configure Supabase credentials

Copy `supabase.properties.example` to `supabase.properties` in the project root folder:

```sh
# Windows (PowerShell)
Copy-Item supabase.properties.example supabase.properties

# macOS / Linux
cp supabase.properties.example supabase.properties
```

Open `supabase.properties` and paste the project's `SUPABASE_URL` and `SUPABASE_ANON_KEY`:

```properties
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_ANON_KEY=your-supabase-anon-key
```

> **Note for groupmates:** Ask a teammate for the shared project's `SUPABASE_URL` and `SUPABASE_ANON_KEY`. **Never commit `supabase.properties` to Git** (it is already listed in `.gitignore`). You can also set `SUPABASE_URL` and `SUPABASE_ANON_KEY` as system environment variables instead of using a file.

### Step 3: Build & Run

Run the test suite to verify everything compiles and passes:

```sh
mvn clean test
```

Launch the desktop app:

```sh
mvn exec:java "-Dexec.mainClass=beenewed.App"
```

On first launch, click **Create Account** in the login window, enter your email and password, type the **Email OTP verification code** sent to your inbox, and then sign in. Use the **Refresh** button on the Dashboard anytime you want to manually re-sync from Supabase.

> **Optional standalone JAR:** Run `mvn clean package` and launch with `java -jar target/beenewed-standalone.jar` (make sure `supabase.properties` is in the folder where you run the command, or set the environment variables).

## What's Included

All UI classes live under `src/main/java/beenewed/`:

| Screen / Dialog | Java class |
| --- | --- |
| Sign In, Create Account & Email OTP Verification | `dialogs/LoginDialog` |
| S1 Dashboard (stats, upcoming renewals, recent payments, Refresh) | `screens/DashboardScreen` |
| S2 Subscriptions (search, category & status filters) | `screens/SubscriptionsScreen` |
| S3 Subscription Details (Overview, Payments, Sharing, Usage, Price History tabs) | `screens/DetailsScreen` |
| S4 Renewals & Payments (Upcoming Renewals, Payment History tabs) | `screens/RenewalsScreen` |
| S5 Reports (Actual Paid, Current Estimated Recurring Cost) | `screens/ReportsScreen` |
| S6 Settings & Preferences (theme, reminders, Account & Log Out) | `screens/SettingsScreen` |
| D1 / D1-B / D1-C Add & Edit Subscription (with validation & icon picker) | `dialogs/SubscriptionDialog` |
| D2 / D2-B Record & Edit Payment | `dialogs/PaymentDialog` |
| D3 Add / Edit Shared Member | `dialogs/MemberDialog` |
| D4 Record / Edit Member Contribution | `dialogs/ContributionDialog` |
| D5 Record / Edit Usage Entry | `dialogs/UsageDialog` |
| D6 Record Price Change | `dialogs/PriceChangeDialog` |
| D7 / D7-B / D7-C Change Status (Pause, Cancel, Reactivate) | `dialogs/StatusDialog` |
| D8 Renewal Reminders | `dialogs/RemindersDialog` |

**Keyboard shortcuts:** Press **Ins** anywhere to add a new subscription. On the Subscriptions table, use **↑ / ↓** to select a row and **Enter** to open its details.

## Light / Dark Mode

- Click the **sun / moon switch** in the top-right header bar to switch the entire app between Light and Dark mode instantly—sidebar, header, cards, tables, forms, dropdowns, dialogs, and buttons update in place without leaving your current page.
- **Settings → Appearance** controls the exact same setting and always stays in sync with the header switch.
- **Your choice is remembered** per user account in Supabase (`app_preferences`). Light mode is the default for new accounts.
- All colours live in `src/main/java/beenewed/ui/Theme.java` (`Theme.apply(dark)`), using the Beenewed palette: yellow `#F4B315`, orange `#E59312`, warm `#D3AF85`, and `#1A141A` / `#423738` / `#514344` for dark surfaces.

## Project Layout

```text
src/
  main/
    java/
      beenewed/                  Swing UI: App, Store cache, ui/, screens/, dialogs/
      com/beenewed/supabase/     SupabaseConfig, SupabaseAuthService, SupabaseRestClient
      com/beenewed/backend/      Domain models, exceptions, and existing backend services
    resources/
      db/supabase_schema.sql     PostgreSQL schema & Row Level Security policies for Supabase
  test/
    java/com/beenewed/backend/   JUnit 5 backend tests
```
