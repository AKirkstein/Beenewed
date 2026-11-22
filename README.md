# Beenewed — Subscription Tracker (Java Swing)

A Java Swing build of the **Beenewed Interfaces** Figma design. It is plain Swing with no outside libraries and runs on Java 17 or newer.

## Run it

- **Double-click** `Beenewed.jar`, or run `java -jar Beenewed.jar`
- **From source:** run `run.bat` (Windows) or `./run.sh` (macOS/Linux)
- **NetBeans / IntelliJ / Eclipse:** create a new Java project, copy the `src/beenewed` folder into its source folder, and run `beenewed.App`

## What's included

| Figma frame | Java class |
|---|---|
| S1 Dashboard | `screens/DashboardScreen` |
| S2 Subscriptions | `screens/SubscriptionsScreen` |
| S3 Subscription Details (Overview, Payments, Sharing, Usage, Price History tabs) | `screens/DetailsScreen` |
| S4 Renewals & Payments (Upcoming Renewals, Payment History tabs) | `screens/RenewalsScreen` |
| S5 Reports (Actual Paid, Current Estimated Recurring Cost) | `screens/ReportsScreen` |
| S6 Settings & Preferences | `screens/SettingsScreen` |
| D1 / D1-B / D1-C Add, Edit, Validation errors (with icon picker) | `dialogs/SubscriptionDialog` |
| D2 / D2-B Record and Edit Payment | `dialogs/PaymentDialog` |
| D3 Add/Edit Member | `dialogs/MemberDialog` |
| D4 Record/Edit Contribution | `dialogs/ContributionDialog` |
| D5 Record/Edit Usage | `dialogs/UsageDialog` |
| D6 Record Price Change | `dialogs/PriceChangeDialog` |
| D7 / D7-B / D7-C Change Status, Reactivate, Cancel | `dialogs/StatusDialog` |
| D8 Reminders | `dialogs/RemindersDialog` |

Everything works, not just the layout:
- Filters, search and tabs
- Adding, editing and deleting records, with input checking
- Totals that update after every change: monthly and annual equivalents, actual paid, contributions
- Settings saved on your computer

Keyboard shortcuts: **Ins** adds a new subscription. On the Subscriptions screen, **↑/↓** selects a row and **Enter** opens it.

## Light / Dark mode

- The **sun/moon switch** in the top-right of the header switches the whole app instantly: sidebar, header, cards, tables, forms, dropdowns, dialogs, buttons and pop-ups. You stay on the same page.
- **Settings → Appearance** controls the same setting, so the two always stay in sync.
- **Your choice is remembered** between launches through `java.util.prefs`, Swing's equivalent of a browser's localStorage. Light is the default the first time the app opens.
- All colours live in `ui/Theme.java` (`Theme.apply(dark)`), using the Beenewed palette: yellow `#F4B315`, orange `#E59312`, warm `#D3AF85`, and `#1A141A` / `#423738` / `#514344` for dark surfaces.
- The header also has a **bell** that opens Renewal Reminders, with a count of renewals due, and a **profile icon**.

## Project layout

```
src/beenewed/
  App.java            main window, sidebar, navigation, entry point (main)
  model/Models.java   data classes: Subscription, Payment, Member, Contribution, Usage, PriceChange
  model/Store.java    in-memory data with the design's sample data, plus saved settings
  ui/                 Theme (light/dark palettes, fonts), Icons (drawn in code), UI widgets, DataTable, DateField
  screens/            S1–S6
  dialogs/            D1–D8 (all extend BaseDialog)
```

## Notes

- **The demo date is fixed at Nov 18, 2026**, the reference date in the design, so the "In 3 days" badges match the mockups. To use the real date, set `Store.USE_REAL_DATE = true`.
- **Data lives in memory and resets when the app closes.** Only Settings are saved. To keep data between runs, you could save the lists in `Store` to a file or connect a database.
- **FlatLaf is optional.** If `flatlaf.jar` is on the classpath, the app uses it automatically, since the design copies FlatLaf's look. Without it, the app still uses its own styling.
- Icons are drawn in code, so the app needs no image files.
