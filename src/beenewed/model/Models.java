package beenewed.model;

import beenewed.ui.Icons;

import java.time.LocalDate;

/** Plain data classes for the app. */
public final class Models {
    private Models() {}

    public enum Cycle {
        MONTHLY("Monthly"), YEARLY("Yearly");
        public final String label;
        Cycle(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public enum Status {
        ACTIVE("Active"), PAUSED("Paused"), CANCELLED("Cancelled");
        public final String label;
        Status(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    public static final String[] CATEGORIES = {
        "Entertainment", "Music", "Cloud Storage", "Productivity", "Education", "Gaming", "Utilities", "Other"
    };

    public static final String[] PAYMENT_METHODS = {
        "Not specified", "Cash", "GCash", "Maya", "Credit Card", "Debit Card", "Bank Transfer"
    };

    public static final String[] USAGE_UNITS = { "Hours", "Minutes", "Sessions", "Movie", "Episodes", "Days", "GB", "Times" };

    /** Default icon suggested for each category (the "Auto-suggested from category" behaviour). */
    public static Icons.Type defaultIcon(String category) {
        if (category == null) return Icons.Type.TELEVISION;
        return switch (category) {
            case "Music" -> Icons.Type.MUSIC;
            case "Cloud Storage" -> Icons.Type.CLOUD;
            case "Productivity" -> Icons.Type.WORK;
            case "Education" -> Icons.Type.EDUCATION;
            case "Gaming" -> Icons.Type.GAMING;
            case "Utilities" -> Icons.Type.UTILITIES;
            case "Other" -> Icons.Type.OTHER;
            default -> Icons.Type.TELEVISION;
        };
    }

    public static class Subscription {
        public final int id;
        public String name, category;
        public Icons.Type icon;
        public double price;
        public Cycle cycle;
        public LocalDate nextRenewal;
        public Status status = Status.ACTIVE;

        public Subscription(int id, String name, String category, Icons.Type icon, double price, Cycle cycle, LocalDate next) {
            this.id = id; this.name = name; this.category = category; this.icon = icon;
            this.price = price; this.cycle = cycle; this.nextRenewal = next;
        }
        public double monthlyEquivalent() { return cycle == Cycle.MONTHLY ? price : price / 12.0; }
        public double annualEquivalent()  { return cycle == Cycle.MONTHLY ? price * 12.0 : price; }
        public String perLabel() { return cycle == Cycle.MONTHLY ? "/ month" : "/ year"; }
        @Override public String toString() { return name; }
    }

    public static class Payment {
        public final int id;
        public Subscription sub;
        public LocalDate date;
        public double amount;
        public String period, method, notes;
        public Payment(int id, Subscription sub, LocalDate date, double amount, String period, String method, String notes) {
            this.id = id; this.sub = sub; this.date = date; this.amount = amount;
            this.period = period; this.method = method; this.notes = notes;
        }
        @Override public String toString() { return beenewed.ui.Theme.date(date) + " \u2014 " + beenewed.ui.Theme.peso(amount); }
    }

    public static class Member {
        public final int id;
        public Subscription sub;
        public String name, notes;
        public Member(int id, Subscription sub, String name, String notes) {
            this.id = id; this.sub = sub; this.name = name; this.notes = notes;
        }
        @Override public String toString() { return name; }
    }

    public static class Contribution {
        public final int id;
        public Subscription sub;
        public Member member;
        public Payment payment;
        public double amount;
        public LocalDate date;
        public Contribution(int id, Subscription sub, Member m, Payment p, double amount, LocalDate date) {
            this.id = id; this.sub = sub; this.member = m; this.payment = p; this.amount = amount; this.date = date;
        }
    }

    public static class Usage {
        public final int id;
        public Subscription sub;
        public LocalDate date;
        public Double quantity;
        public String unit, notes;
        public Usage(int id, Subscription sub, LocalDate date, Double qty, String unit, String notes) {
            this.id = id; this.sub = sub; this.date = date; this.quantity = qty; this.unit = unit; this.notes = notes;
        }
    }

    public static class PriceChange {
        public final int id;
        public Subscription sub;
        public LocalDate date;
        public double oldPrice, newPrice;
        public String notes;
        public PriceChange(int id, Subscription sub, LocalDate date, double oldP, double newP, String notes) {
            this.id = id; this.sub = sub; this.date = date; this.oldPrice = oldP; this.newPrice = newP; this.notes = notes;
        }
    }
}
