# BEENEWED Copilot Instructions

## Tech Stack

- Java 17 and Maven.
- SQLite accessed through raw JDBC using `sqlite-jdbc`.
- Swing with FlatLaf is reserved for the frontend team.
- Editor/Tooling: VS Code using the JetBrains IntelliJ IDEA Java extension (do not suggest Red Hat/Microsoft Java extension settings).

## Strict Role and File Boundaries

You are assisting the **Backend Developer**.

**Allowed to read and write:**

- `src/main/java/com/beenewed/backend/**`
- `src/main/java/com/beenewed/shared/**`
- `src/main/resources/db/**`
- `src/test/java/com/beenewed/backend/**`

**Forbidden:**

- Do not read, search, modify, or generate files in `src/main/java/com/beenewed/frontend/**` or `src/main/resources/assets/**`.
- Do not read, search, modify, or generate any `*.db`, `*.sqlite`, or `target/` files.
- Do not modify `pom.xml` or `Main.java` unless explicitly instructed.

If a task requires UI changes, stop and state the method signature the frontend team should call instead of editing frontend files.

## Architecture and Coding Standards

- Use layered architecture: `shared/models` and `shared/interfaces` -> `backend/dao` -> `backend/services`.
- Always use `PreparedStatement`; never concatenate SQL.
- Enable `PRAGMA foreign_keys = ON;` on every SQLite connection.
- Use `java.time.LocalDate` for dates, stored as ISO-8601 `YYYY-MM-DD` text.
- Use `BigDecimal` for all money values.
- Never import `javax.swing.*` or `java.awt.*` inside `backend/` or `shared/`.

## Domain Business Rules

- The application is a local, single-user store. Member records represent people sharing a subscription, not login accounts.
- Record payments explicitly. A renewal due date does not imply that a payment was made.
- Keep these spending figures strictly separate:
  - **Actual Paid:** the sum of recorded payments by `payment_date` in the selected period.
  - **Current Estimated Recurring Cost:** active subscriptions normalized monthly (`annual / 12`) or annually (`monthly * 12`).
- Member contributions link to both a member and a payment and must never be double-counted as subscription expenses.
- Price changes add an entry to `price_history` and update the current price without changing previously recorded payment amounts.
- Preserve financial history by changing subscription status (`ACTIVE`, `PAUSED`, `CANCELLED`) instead of hard-deleting subscriptions with payments.
- Handle month-end rollovers (for example, January 31 to February 28/29) and leap years safely using `LocalDate.plusMonths()` and `LocalDate.plusYears()`.
