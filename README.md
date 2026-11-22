# Beenewed — Subscription Tracker

**Beenewed** is a Java Swing desktop subscription manager backed by **Supabase Auth** (with **Email OTP** verification) and **PostgreSQL**. It gives each user their own cloud-synced account to organize recurring subscriptions, renewal reminders, payments, shared member contributions, usage logs, and price history in one place.

## Why Beenewed?

Keeping track of recurring subscriptions, shared costs and household members, payment history, upcoming renewals, and whether you still actually use a service quickly gets messy across spreadsheets and scattered notes. Beenewed brings everything into one clean, fast desktop app with cloud-backed accounts and responsive in-memory caching so switching views, filtering, and checking spending summaries feels instant.

**Built with:** Java 17 · Java Swing & FlatLaf · Apache Maven · Gson · Supabase Auth (Email OTP) · PostgreSQL

## Light / Dark Mode

- Click the **sun / moon switch** in the top-right header bar to switch the entire app between Light and Dark mode instantly—sidebar, header, cards, tables, forms, dropdowns, dialogs, and buttons update in place without leaving your current page.
- **Settings → Appearance** controls the exact same setting and always stays in sync with the header switch.
- **Your preference is saved** to your account in Supabase (`app_preferences`). Light mode is the default for new accounts.
- All colours live in `src/main/java/beenewed/ui/Theme.java` (`Theme.apply(dark)`), using the Beenewed palette: yellow `#F4B315`, orange `#E59312`, warm `#D3AF85`, and `#1A141A` / `#423738` / `#514344` for dark surfaces.

## Prerequisites & Recommended Extensions

Before running the app, make sure you have:

- **JDK 17+** — verify with `java -version`
- **Apache Maven (3.8+)** — verify with `mvn -version`
- **VS Code (Optional):** If you are using Visual Studio Code, install the **Extension Pack for Java** (`vscjava.vscode-java-pack`) for Java support, Maven integration, and easy running/debugging.

## How to Build & Run

### Step 1: Clone the repository

Clone the project on `main` and open the folder:

```sh
git clone https://github.com/AKirkstein/Beenewed.git
cd Beenewed
```

### Step 2: Configure Supabase credentials

Copy `supabase.properties.example` to `supabase.properties` in the project root:

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

> **Note for groupmates:** Ask a teammate for the shared `SUPABASE_URL` and `SUPABASE_ANON_KEY` values. **Never commit `supabase.properties` to Git** (it is already ignored in `.gitignore`). You can also provide `SUPABASE_URL` and `SUPABASE_ANON_KEY` as environment variables instead of a file.

### Step 3: Build & Run

**Quick start on Windows:**

```powershell
.\run
```

**Run via Maven (Windows / macOS / Linux):**

```sh
mvn compile exec:java "-Dexec.mainClass=beenewed.App"
```

**Run the automated test suite:**

```sh
mvn clean test
```

**Build & run the standalone JAR:**

```sh
mvn clean package
java -jar target/beenewed-standalone.jar
```

> Make sure `supabase.properties` is in the folder where you run `java -jar` (or set `SUPABASE_URL` and `SUPABASE_ANON_KEY` as environment variables).

### First-Time Sign Up

When the app opens, click **Create Account** in the login window, enter your email and password, enter the **Email OTP verification code** sent to your inbox, and then sign in. Once on the Dashboard, you can click **Refresh** anytime to re-sync your latest data from Supabase.
