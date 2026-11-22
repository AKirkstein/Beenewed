package beenewed.model;

import beenewed.model.Models.*;
import beenewed.ui.Icons;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * In-memory data store with the sample data shown in the Figma design.
 * Screens call {@link #changed()} after edits; listeners refresh the UI.
 */
public final class Store {
    private static final Store INSTANCE = new Store();
    public static Store get() { return INSTANCE; }

    /**
     * "Today" for the app. Fixed to Nov 18, 2026 so the sample data matches the design
     * (the design's reference date). Set USE_REAL_DATE = true to use the system clock.
     */
    public static final boolean USE_REAL_DATE = false;
    private static final LocalDate DEMO_TODAY = LocalDate.of(2026, 11, 18);
    public static LocalDate today() { return USE_REAL_DATE ? LocalDate.now() : DEMO_TODAY; }

    public final List<Subscription> subscriptions = new ArrayList<>();
    public final List<Payment> payments = new ArrayList<>();
    public final List<Member> members = new ArrayList<>();
    public final List<Contribution> contributions = new ArrayList<>();
    public final List<Usage> usages = new ArrayList<>();
    public final List<PriceChange> priceChanges = new ArrayList<>();

    private final List<Runnable> listeners = new ArrayList<>();
    private int nextId = 100;

    // ---- Preferences (saved locally via java.util.prefs) ----
    private final Preferences prefs = Preferences.userNodeForPackage(Store.class);
    public int remindDays = prefs.getInt("remindDays", 7);
    public boolean showOverdue = prefs.getBoolean("showOverdue", true);

    public void savePrefs(int days, boolean overdue) {
        remindDays = days; showOverdue = overdue;
        prefs.putInt("remindDays", days);
        prefs.putBoolean("showOverdue", overdue);
        changed();
    }

    private Store() { seed(); }

    public int newId() { return nextId++; }
    public void onChange(Runnable r) { listeners.add(r); }
    public void changed() { listeners.forEach(Runnable::run); }

    private void seed() {
        Subscription netflix = add(new Subscription(1, "Netflix", "Entertainment", Icons.Type.TELEVISION, 549, Cycle.MONTHLY, LocalDate.of(2026, 12, 5)));
        Subscription spotify = add(new Subscription(2, "Spotify", "Entertainment", Icons.Type.MUSIC, 149, Cycle.MONTHLY, LocalDate.of(2026, 11, 25)));
        Subscription google  = add(new Subscription(3, "Google One", "Cloud Storage", Icons.Type.CLOUD, 89, Cycle.MONTHLY, LocalDate.of(2026, 12, 2)));
        Subscription youtube = add(new Subscription(4, "YouTube Premium", "Entertainment", Icons.Type.TELEVISION, 159, Cycle.MONTHLY, LocalDate.of(2026, 11, 21)));
        add(new Subscription(5, "Canva Pro", "Productivity", Icons.Type.DESIGN, 2990, Cycle.YEARLY, LocalDate.of(2026, 12, 15)));
        add(new Subscription(6, "Microsoft 365", "Productivity", Icons.Type.WORK, 3499, Cycle.YEARLY, LocalDate.of(2027, 1, 9)));

        Payment pNetflix = new Payment(11, netflix, LocalDate.of(2026, 11, 5), 549, null, "Not specified", null);
        payments.add(pNetflix);
        payments.add(new Payment(12, google, LocalDate.of(2026, 11, 2), 89, null, "Not specified", null));
        payments.add(new Payment(13, spotify, LocalDate.of(2026, 10, 25), 149, null, "Not specified", null));
        payments.add(new Payment(14, youtube, LocalDate.of(2026, 10, 21), 159, null, "Not specified", null));

        Member alex = new Member(21, netflix, "Alex Rivera", "Household member");
        Member jamie = new Member(22, netflix, "Jamie Santos", "Household member");
        members.add(alex); members.add(jamie);
        contributions.add(new Contribution(31, netflix, alex, pNetflix, 200, LocalDate.of(2026, 11, 6)));
        contributions.add(new Contribution(32, netflix, jamie, pNetflix, 150, LocalDate.of(2026, 11, 7)));

        usages.add(new Usage(41, netflix, LocalDate.of(2026, 11, 16), 2.0, "Hours", "Watched a series"));
        usages.add(new Usage(42, netflix, LocalDate.of(2026, 11, 13), 1.0, "Movie", "Watched a film"));
        usages.add(new Usage(43, netflix, LocalDate.of(2026, 11, 10), null, null, "Used during the weekend"));

        priceChanges.add(new PriceChange(51, netflix, LocalDate.of(2026, 11, 1), 499, 549, "Monthly subscription price increase"));
    }

    private Subscription add(Subscription s) { subscriptions.add(s); return s; }

    // ---- Queries -------------------------------------------------------
    public List<Subscription> active() {
        return subscriptions.stream().filter(s -> s.status == Status.ACTIVE).collect(Collectors.toList());
    }
    public List<Subscription> byRenewal(List<Subscription> in) {
        List<Subscription> l = new ArrayList<>(in);
        l.sort(Comparator.comparing(s -> s.nextRenewal));
        return l;
    }
    public List<Payment> paymentsNewestFirst() {
        List<Payment> l = new ArrayList<>(payments);
        l.sort(Comparator.comparing((Payment p) -> p.date).reversed());
        return l;
    }
    public List<Payment> paymentsFor(Subscription s) {
        return paymentsNewestFirst().stream().filter(p -> p.sub == s).collect(Collectors.toList());
    }
    public List<Member> membersFor(Subscription s) {
        return members.stream().filter(m -> m.sub == s).collect(Collectors.toList());
    }
    public List<Contribution> contributionsFor(Subscription s) {
        return contributions.stream().filter(c -> c.sub == s).sorted(Comparator.comparing(c -> c.date)).collect(Collectors.toList());
    }
    public List<Usage> usagesFor(Subscription s) {
        return usages.stream().filter(u -> u.sub == s).sorted(Comparator.comparing((Usage u) -> u.date).reversed()).collect(Collectors.toList());
    }
    public List<PriceChange> priceChangesFor(Subscription s) {
        return priceChanges.stream().filter(p -> p.sub == s).sorted(Comparator.comparing((PriceChange p) -> p.date).reversed()).collect(Collectors.toList());
    }
    public double monthlyTotal() { return active().stream().mapToDouble(Subscription::monthlyEquivalent).sum(); }
    public double annualTotal()  { return active().stream().mapToDouble(Subscription::annualEquivalent).sum(); }

    public static long daysUntil(LocalDate d) { return ChronoUnit.DAYS.between(today(), d); }

    /** Active subscriptions renewing within the reminder window (0..remindDays days away). */
    public List<Subscription> upcomingReminders() {
        return byRenewal(active()).stream().filter(s -> { long d = daysUntil(s.nextRenewal); return d >= 0 && d <= remindDays; }).collect(Collectors.toList());
    }
    public List<Subscription> overdue() {
        return byRenewal(active()).stream().filter(s -> daysUntil(s.nextRenewal) < 0).collect(Collectors.toList());
    }

    /** Deletes a subscription together with everything linked to it. */
    public void deleteSubscription(Subscription s) {
        subscriptions.remove(s);
        payments.removeIf(p -> p.sub == s);
        members.removeIf(m -> m.sub == s);
        contributions.removeIf(c -> c.sub == s);
        usages.removeIf(u -> u.sub == s);
        priceChanges.removeIf(p -> p.sub == s);
    }
}
