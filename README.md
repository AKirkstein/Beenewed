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

## Prerequisites & Tools

Before running the app, make sure you have:

- **Java Development Kit (JDK 17+)** — verify with `java -version`
- **Apache Maven 3.10.2 (Binary Zip)** — step-by-step Windows setup instructions are in **Step 2** below
- **VS Code (Optional):** Install the **Extension Pack for Java** (`vscjava.vscode-java-pack`) for Java IntelliSense, Maven integration, and easy running/debugging.

## How to Build & Run

### Step 1: Clone the repository

Clone the project on `main` and open the folder:

```sh
git clone https://github.com/AKirkstein/Beenewed.git
cd Beenewed
```

### Step 2: Install & Configure Maven (Windows)

If you already have Maven installed and `mvn -version` works in your terminal, you can skip to **Step 3**.

1. Go to the official Apache Maven download page: https://maven.apache.org/download.cgi
2. Under **Files**, download the **Binary zip archive**: `apache-maven-3.10.2-bin.zip`.
3. Extract the zip file to a clean, permanent directory—for example:
   - `C:\Program Files\apache-maven-3.10.2` or
   - `C:\tools\apache-maven-3.10.2`
4. Add Maven to your Windows Environment Variables:
   1. Open Windows Search, type **"Edit the system environment variables"**, and open it.
   2. Click **Environment Variables...** near the bottom-right.
   3. Under **System variables**, select `Path` and click **Edit**.
   4. Click **New** and paste the path to Maven's `bin` folder (for example, `C:\Program Files\apache-maven-3.10.2\bin`).
   5. *(Optional best practice)* Under **System variables**, click **New** and add `MAVEN_HOME` (or `M2_HOME`) pointing to the extracted root folder (for example, `C:\Program Files\apache-maven-3.10.2`).
   6. Click **OK** on all open dialogs to save.
5. Open a **new** terminal window (PowerShell or Command Prompt) and verify the installation:

```sh
mvn -version
```

You should see Apache Maven 3.10.2 and your Java 17+ version printed.

### Step 3: Configure credentials

Copy `.env.example` to `.env` in the project root:

```sh
# Windows (PowerShell)
Copy-Item .env.example .env

# macOS / Linux
cp .env.example .env
```

Open `.env` and paste the project's `SUPABASE_URL` and `SUPABASE_ANON_KEY`:

```env
SUPABASE_URL=your-supabase-project-url
SUPABASE_ANON_KEY=your-supabase-anon-key
```

> **Note for groupmates:** Ask a teammate for the shared `SUPABASE_URL` and `SUPABASE_ANON_KEY` values. **Never commit `.env` to Git** (it is already ignored in `.gitignore`). You can also set `SUPABASE_URL` and `SUPABASE_ANON_KEY` directly as system environment variables instead of using a `.env` file.

### Step 4: Run the application

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

> Make sure `.env` is in the folder where you run `java -jar` (or set `SUPABASE_URL` and `SUPABASE_ANON_KEY` as environment variables).

### First-Time Sign Up

When the app opens, click **Create Account** in the login window, enter your email and password, enter the **Email OTP verification code** sent to your inbox, and then sign in. Once on the Dashboard, you can click **Refresh** anytime to re-sync your latest data from Supabase.
