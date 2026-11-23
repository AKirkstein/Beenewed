# Beenewed

[![Java](https://img.shields.io/badge/Java-17%2B-orange?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![Tests](https://img.shields.io/badge/Tests-62%20passing-15803D)](src/test/java)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

**Beenewed** is a financial-grade desktop subscription tracker and expense management application built with **Java 17**, **FlatLaf**, and **Supabase**. It gives you a fast, cloud-synced workspace to monitor recurring renewals, lock in auditable payment ledgers, coordinate family split billing, and spot underutilized subscriptions before they drain your budget.

---

## What Is Beenewed?

Subscriptions rarely fail loudly—they quietly auto-renew across streaming platforms, cloud storage, utilities, and shared family plans until your monthly bill creeps higher than expected. Spreadsheets go stale, group splits turn into awkward guesswork, and it is hard to tell which services you still get value from.

Beenewed solves this in a single desktop application:

- **Eliminate recurring subscription leakage** by tracking every active, paused, and cancelled service in one place with instant search and filtering.
- **Prevent surprise charges** with automated cycle-by-cycle renewal advancement, overdue detection, and dismissible renewal reminders.
- **Keep family and group billing fair** with designated payers, cent-accurate split math, and clear Paid vs. Pending reimbursement tracking.
- **Measure real-world value** by pairing recurring costs with 30-day usage telemetry so you immediately see cost-per-use and underutilized plans.

---

## Key Capabilities

| Capability | What It Does |
| --- | --- |
| **Recurring Subscription Management** | Supports **Weekly**, **Monthly**, and **Yearly** billing interval logic with automated cycle-by-cycle renewal date advancement (safely handling month-end rollovers and leap years), overdue tracking, and full lifecycle states (`Active`, `Paused`, `Cancelled`). |
| **Immutable Payment Ledgers** | Prevents duplicate payments within the same billing period, enforces payment amount alignment against the subscription price or designated split share, and preserves recorded payments as read-only ledger receipts. |
| **Family Cost Splitting** | Designate a Group Head / Primary Payer and split bills equally (with exact cent-level rounding) or with validated custom allocations—persisted cleanly with zero extra database migrations and tracked per member as **Paid**, **Partial**, or **Pending**. |
| **Spending Analytics & Cash-Flow Forecasts** | Separates **Actual Paid** history from **Current Estimated Recurring Cost**, normalizes Weekly/Monthly/Yearly commitments to monthly and annual equivalents, visualizes spending by category, and projects scheduled cash outflows **1 to 6 months** ahead. |
| **Usage Telemetry & Cost Efficiency** | Integrates manual usage logs over a **30-day rolling window** to compute **Cost / Day** and **Cost / Use**, automatically badging active services as **No Usage** (`0`), **Low Usage** (`1–2`), or **Active Use** (`3+`). |
| **Data Protection & CSV Exports** | Exports RFC-4180 CSV reports for **Payment History**, **Active Subscriptions**, and **Family Split Distributions**, complete with spreadsheet formula-injection neutralization and metadata stripping. |
| **Offline Resilience** | Automatically persists a token-free local **SQLite** snapshot (`~/.beenewed/offline-cache.db`). If network connectivity drops or Supabase is unreachable at startup, Beenewed opens cleanly in **read-only offline mode** with a non-blocking reconnect banner. |
| **Account Security & Theming** | Supabase Email + OTP authentication with strong password policy enforcement (8+ chars, upper/lower/number/symbol), automatic session token refresh, local-scope sign-out, and instant **Light / Dark Mode** switching via FlatLaf. |

---

## Prerequisites

- **Java Development Kit (JDK) 17 or newer** (`java -version`)
- **Apache Maven 3.8 or newer** (`mvn -version`)
- **Supabase Project & Internet Access** for initial sign-in and cloud sync (schema provided in `src/main/resources/db/supabase_schema.sql`)

---

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/AKirkstein/Beenewed.git
cd Beenewed
```

### 2. Configure Supabase Credentials

Create a `.env` file in the project root (or in `~/.beenewed/.env`, or export these as system environment variables):

```env
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_ANON_KEY=your-supabase-anon-key
```

> **Note:** Never commit `.env` to Git (it is excluded by `.gitignore`). When launching the standalone JAR from any working directory, Beenewed automatically searches environment variables, `./.env`, the JAR directory's `.env`, and `~/.beenewed/.env` in order.

If you are setting up a fresh Supabase project, run `src/main/resources/db/supabase_schema.sql` in the Supabase SQL Editor first to create the Row Level Security–protected tables.

### 3. Run in Development Mode (Maven)

```bash
mvn compile exec:java -Dexec.mainClass=beenewed.App
```

On Windows PowerShell / Command Prompt, you can also use the included helper script:

```powershell
.\run.bat
```

### 4. Build & Launch the Standalone Production Fat JAR

Package a self-contained executable JAR (bundling FlatLaf, SQLite JDBC natives, Gson, and resources) using the `standalone` profile:

```bash
mvn clean package -Pstandalone
```

Then launch it from any directory:

```powershell
# Windows launcher
.\run-standalone.bat

# Cross-platform Java command
java -jar target/beenewed-standalone.jar
```

---

## Verification & Automated Testing

Run the full automated JUnit 5 test suite with:

```bash
mvn test
```

The suite includes **62 unit and integration tests** covering:

- **Authentication & Session Security:** Password policy enforcement, token redaction, automatic token refresh, local-scope sign-out, and OTP cleanup.
- **Billing & Ledger Integrity:** Duplicate billing-period detection, positive `BigDecimal` amount alignment, `ResolverStyle.STRICT` date parsing, keystroke `DocumentFilter`s, due-date advancement, and read-only receipt enforcement.
- **Family Splits & Reminders:** Cent-accurate equal splitting, custom split total validation, Group Head paid/pending distribution, and viewed reminder badge tracking.
- **Analytics, Forecasting & CSV Security:** Weekly/Monthly/Yearly normalization, category distribution, 1–6 month cash-flow projections, 30-day usage efficiency metrics, and RFC-4180 CSV escaping with formula-injection protection.
- **Offline Resilience & Lifecycle:** Local SQLite snapshot persistence, offline read-only fallback, fetch debouncing, executor shutdown, and Swing EDT UI regression checks.

---

## Architecture & Tech Stack

| Layer | Technology |
| --- | --- |
| **Language & Runtime** | Java 17 |
| **Desktop UI & Theming** | Java Swing + FlatLaf 3.5.2 (Light & Dark themes) |
| **Remote Auth & Cloud Data** | Supabase GoTrue Auth (Email OTP) & PostgREST (`java.net.http.HttpClient`) |
| **Local Offline Cache** | SQLite (`sqlite-jdbc` 3.46.1.0) + Gson 2.11.0 |
| **Build & Packaging** | Apache Maven + Maven Shade Plugin (`beenewed-standalone.jar`) |
| **Testing** | JUnit 5 (Jupiter 5.11.3) |

### Project Structure

```text
src/
├── main/
│   ├── java/
│   │   ├── beenewed/
│   │   │   ├── App.java                     # Main frame, navigation, connectivity banner & lifecycle
│   │   │   ├── model/
│   │   │   │   ├── Models.java              # Core domain records (Subscription, Payment, Member, Usage...)
│   │   │   │   ├── Store.java               # In-memory cache, offline fallback, debounced sync & mutations
│   │   │   │   ├── BillingRules.java        # Duplicate detection, amount alignment & due-date advancement
│   │   │   │   ├── SplitAllocator.java      # Equal/custom split math, settlement summaries & metadata codec
│   │   │   │   ├── AnalyticsEngine.java     # Normalization, category breakdown, 1–6m forecast & efficiency
│   │   │   │   └── CsvExporter.java         # RFC-4180 CSV exports with formula-injection neutralization
│   │   │   ├── screens/                     # Dashboard, Subscriptions, Details, Renewals, Reports, Settings
│   │   │   ├── dialogs/                     # Login, Subscription, Payment Receipt, SplitConfig, Reminders, Usage
│   │   │   └── ui/                          # Theme, Icons, DataTable, DateField, InputFilters, ProfileMenu
│   │   └── com/beenewed/
│   │       ├── supabase/                    # SupabaseConfig, AuthService, RestClient, LocalSnapshotCache
│   │       └── backend/                     # Shared domain models, DAOs, SQLite config & backend services
│   └── resources/
│       └── db/                              # schema.sql & supabase_schema.sql
└── test/
    └── java/                                # 62 JUnit 5 unit, Swing UI, mock-HTTP & SQLite tests
```
