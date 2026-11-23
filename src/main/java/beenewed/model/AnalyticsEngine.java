package beenewed.model;

import beenewed.model.BillingRules.Interval;
import beenewed.model.Models.Status;
import beenewed.model.Models.Subscription;
import beenewed.model.Models.Usage;
import com.beenewed.backend.exception.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure analytics, cash-flow forecasting, and usage-efficiency calculation engine.
 * All financial math uses {@link BigDecimal} at 2-decimal {@link RoundingMode#HALF_UP} precision.
 */
public final class AnalyticsEngine {
    private AnalyticsEngine() {}

    public static final int DEFAULT_USAGE_WINDOW_DAYS = 30;
    public static final int LOW_USAGE_MAX = 2;
    public static final int MIN_FORECAST_MONTHS = 1;
    public static final int MAX_FORECAST_MONTHS = 6;

    private static final BigDecimal WEEKS_PER_YEAR = BigDecimal.valueOf(52);
    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    public enum UtilizationLevel {
        UNUSED("No Usage"),
        LOW_USAGE("Low Usage"),
        HEALTHY("Active Use");

        public final String label;
        UtilizationLevel(String label) { this.label = label; }
        public boolean isUnderutilized() { return this == UNUSED || this == LOW_USAGE; }
    }

    public record CategoryMetric(
            String category,
            int activeCount,
            BigDecimal monthlyTotal,
            BigDecimal annualTotal,
            BigDecimal sharePercent) {}

    public record ForecastItem(
            String name,
            BigDecimal price,
            Interval interval,
            LocalDate nextRenewal,
            Status status) {
        public static ForecastItem from(Subscription sub) {
            return new ForecastItem(
                    sub.name,
                    BillingRules.money(sub.price),
                    Interval.fromCycle(sub.cycle),
                    sub.nextRenewal,
                    sub.status);
        }
    }

    public record ForecastMonth(
            int monthNumber,
            LocalDate startDate,
            LocalDate endDateInclusive,
            String label,
            int renewalCount,
            BigDecimal projectedAmount,
            List<String> renewalNames) {}

    public record ForecastReport(
            int horizonMonths,
            LocalDate referenceDate,
            BigDecimal totalProjected,
            int totalRenewals,
            List<ForecastMonth> months) {}

    public record UsageEfficiency(
            Subscription subscription,
            BigDecimal monthlyCost,
            BigDecimal costPerDay,
            int usesInWindow,
            double totalQuantityInWindow,
            String primaryUnit,
            BigDecimal costPerUse,
            LocalDate lastUsedDate,
            UtilizationLevel level) {}

    public record EfficiencyReport(
            int windowDays,
            int totalActiveCount,
            int unusedCount,
            int lowUsageCount,
            int underutilizedCount,
            BigDecimal underutilizedMonthlySpend,
            BigDecimal underutilizedAnnualSpend,
            List<UsageEfficiency> rows) {}

    // ---------------------------------------------------------------------
    // Recurring Spend Normalization (Weekly, Monthly, Yearly)
    // ---------------------------------------------------------------------

    /** Normalizes a subscription price to its monthly equivalent (Weekly: *52/12, Monthly: *1, Yearly: /12). */
    public static BigDecimal monthlyEquivalent(BigDecimal price, Interval interval) {
        if (price == null || price.signum() < 0 || interval == null) {
            throw new ValidationException("Non-negative price and billing interval are required.");
        }
        BigDecimal p = BillingRules.money(price);
        return switch (interval) {
            case WEEKLY -> p.multiply(WEEKS_PER_YEAR).divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
            case MONTHLY -> p;
            case YEARLY -> p.divide(MONTHS_PER_YEAR, 2, RoundingMode.HALF_UP);
        };
    }

    public static BigDecimal monthlyEquivalent(Subscription sub) {
        return monthlyEquivalent(BillingRules.money(sub.price), Interval.fromCycle(sub.cycle));
    }

    /** Normalizes a subscription price to its annual equivalent (Weekly: *52, Monthly: *12, Yearly: *1). */
    public static BigDecimal annualEquivalent(BigDecimal price, Interval interval) {
        if (price == null || price.signum() < 0 || interval == null) {
            throw new ValidationException("Non-negative price and billing interval are required.");
        }
        BigDecimal p = BillingRules.money(price);
        return switch (interval) {
            case WEEKLY -> p.multiply(WEEKS_PER_YEAR).setScale(2, RoundingMode.HALF_UP);
            case MONTHLY -> p.multiply(MONTHS_PER_YEAR).setScale(2, RoundingMode.HALF_UP);
            case YEARLY -> p;
        };
    }

    public static BigDecimal annualEquivalent(Subscription sub) {
        return annualEquivalent(BillingRules.money(sub.price), Interval.fromCycle(sub.cycle));
    }

    /** Computes standardized daily cost ({@code annualEquivalent / 365}). */
    public static BigDecimal dailyCost(BigDecimal price, Interval interval) {
        return annualEquivalent(price, interval).divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal dailyCost(Subscription sub) {
        return dailyCost(BillingRules.money(sub.price), Interval.fromCycle(sub.cycle));
    }

    // ---------------------------------------------------------------------
    // Category Spend Distribution
    // ---------------------------------------------------------------------

    /** Groups active subscriptions by category, computing monthly/annual spend and % share of total. */
    public static List<CategoryMetric> categoryDistribution(List<Subscription> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return List.of();
        }
        Map<String, CategoryAccumulator> grouped = new HashMap<>();
        BigDecimal grandMonthly = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        for (Subscription sub : subscriptions) {
            if (sub == null || sub.status != Status.ACTIVE) {
                continue;
            }
            BigDecimal m = monthlyEquivalent(sub);
            BigDecimal a = annualEquivalent(sub);
            grandMonthly = grandMonthly.add(m);
            grouped.compute(sub.category, (cat, cur) -> cur == null
                    ? new CategoryAccumulator(1, m, a)
                    : new CategoryAccumulator(cur.count + 1, cur.monthly.add(m), cur.annual.add(a)));
        }

        List<CategoryMetric> metrics = new ArrayList<>(grouped.size());
        for (Map.Entry<String, CategoryAccumulator> entry : grouped.entrySet()) {
            CategoryAccumulator acc = entry.getValue();
            BigDecimal share = grandMonthly.signum() == 0
                    ? BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP)
                    : acc.monthly.multiply(HUNDRED).divide(grandMonthly, 1, RoundingMode.HALF_UP);
            metrics.add(new CategoryMetric(entry.getKey(), acc.count, acc.monthly, acc.annual, share));
        }

        metrics.sort(Comparator.comparing(CategoryMetric::monthlyTotal).reversed()
                .thenComparing(CategoryMetric::category, String.CASE_INSENSITIVE_ORDER));
        return metrics;
    }

    private record CategoryAccumulator(int count, BigDecimal monthly, BigDecimal annual) {}

    // ---------------------------------------------------------------------
    // Projected Expense Forecast (Upcoming 1 to 6 Months)
    // ---------------------------------------------------------------------

    public static ForecastReport forecastExpenses(
            List<Subscription> subscriptions,
            LocalDate referenceDate,
            int horizonMonths) {
        List<ForecastItem> items = new ArrayList<>();
        if (subscriptions != null) {
            for (Subscription s : subscriptions) {
                if (s != null) {
                    items.add(ForecastItem.from(s));
                }
            }
        }
        return forecastItems(items, referenceDate, horizonMonths);
    }

    /**
     * Projects scheduled cash-flow renewals across {@code horizonMonths} (1..6) sequential monthly
     * windows starting from {@code referenceDate}. Yearly plans appear in the exact month they renew;
     * weekly plans count every scheduled week in each month; paused/cancelled plans are excluded.
     */
    public static ForecastReport forecastItems(
            List<ForecastItem> items,
            LocalDate referenceDate,
            int horizonMonths) {
        if (referenceDate == null) {
            throw new ValidationException("Forecast reference date is required.");
        }
        if (horizonMonths < MIN_FORECAST_MONTHS || horizonMonths > MAX_FORECAST_MONTHS) {
            throw new ValidationException("Forecast horizon must be between 1 and 6 months.");
        }

        LocalDate[] starts = new LocalDate[horizonMonths];
        LocalDate[] endsExclusive = new LocalDate[horizonMonths];
        BigDecimal[] amounts = new BigDecimal[horizonMonths];
        int[] counts = new int[horizonMonths];
        List<List<String>> names = new ArrayList<>(horizonMonths);

        for (int i = 0; i < horizonMonths; i++) {
            starts[i] = referenceDate.plusMonths(i);
            endsExclusive[i] = referenceDate.plusMonths(i + 1);
            amounts[i] = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            names.add(new ArrayList<>());
        }
        LocalDate horizonEnd = endsExclusive[horizonMonths - 1];

        if (items != null) {
            for (ForecastItem item : items) {
                if (item == null || item.status() != Status.ACTIVE || item.nextRenewal() == null || item.interval() == null) {
                    continue;
                }
                BigDecimal charge = BillingRules.money(item.price());
                LocalDate due = item.nextRenewal();
                while (due.isBefore(referenceDate)) {
                    due = BillingRules.advanceNextDueDate(due, item.interval());
                }
                while (due.isBefore(horizonEnd)) {
                    for (int i = 0; i < horizonMonths; i++) {
                        if (!due.isBefore(starts[i]) && due.isBefore(endsExclusive[i])) {
                            amounts[i] = amounts[i].add(charge);
                            counts[i]++;
                            names.get(i).add(item.name());
                            break;
                        }
                    }
                    due = BillingRules.advanceNextDueDate(due, item.interval());
                }
            }
        }

        List<ForecastMonth> months = new ArrayList<>(horizonMonths);
        BigDecimal totalProjected = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        int totalRenewals = 0;
        for (int i = 0; i < horizonMonths; i++) {
            LocalDate incEnd = endsExclusive[i].minusDays(1);
            String label = formatWindowLabel(starts[i], incEnd);
            months.add(new ForecastMonth(
                    i + 1,
                    starts[i],
                    incEnd,
                    label,
                    counts[i],
                    amounts[i],
                    List.copyOf(names.get(i))));
            totalProjected = totalProjected.add(amounts[i]);
            totalRenewals += counts[i];
        }

        return new ForecastReport(horizonMonths, referenceDate, totalProjected, totalRenewals, months);
    }

    private static String formatWindowLabel(LocalDate start, LocalDate endInclusive) {
        if (start.getYear() == endInclusive.getYear()) {
            return SHORT_DAY.format(start) + " \u2013 " + SHORT_DAY_YEAR.format(endInclusive);
        }
        return SHORT_DAY_YEAR.format(start) + " \u2013 " + SHORT_DAY_YEAR.format(endInclusive);
    }

    // ---------------------------------------------------------------------
    // Usage Telemetry & Cost Efficiency
    // ---------------------------------------------------------------------

    /** Computes cost per use ({@code periodCost / usageCount}), or {@code null} when {@code usageCount <= 0}. */
    public static BigDecimal costPerUse(BigDecimal periodCost, int usageCount) {
        if (periodCost == null || usageCount <= 0) {
            return null;
        }
        return BillingRules.money(periodCost).divide(BigDecimal.valueOf(usageCount), 2, RoundingMode.HALF_UP);
    }

    /** Classifies utilization over the 30-day rolling window: 0 = No Usage, 1–2 = Low Usage, 3+ = Active Use. */
    public static UtilizationLevel classifyUtilization(int usesInWindow) {
        if (usesInWindow <= 0) {
            return UtilizationLevel.UNUSED;
        }
        if (usesInWindow <= LOW_USAGE_MAX) {
            return UtilizationLevel.LOW_USAGE;
        }
        return UtilizationLevel.HEALTHY;
    }

    public static EfficiencyReport evaluateUsageEfficiency(
            List<Subscription> subscriptions,
            List<Usage> usages,
            LocalDate today) {
        return evaluateUsageEfficiency(subscriptions, usages, today, DEFAULT_USAGE_WINDOW_DAYS);
    }

    public static EfficiencyReport evaluateUsageEfficiency(
            List<Subscription> subscriptions,
            List<Usage> usages,
            LocalDate today,
            int windowDays) {
        if (today == null) {
            throw new ValidationException("Evaluation date is required.");
        }
        if (windowDays <= 0) {
            throw new ValidationException("Usage evaluation window must be positive.");
        }

        LocalDate windowStart = today.minusDays(windowDays - 1L);
        Map<Integer, List<Usage>> usagesBySub = new LinkedHashMap<>();
        if (usages != null) {
            for (Usage u : usages) {
                if (u != null && u.sub != null) {
                    usagesBySub.computeIfAbsent(u.sub.id, k -> new ArrayList<>()).add(u);
                }
            }
        }

        List<UsageEfficiency> rows = new ArrayList<>();
        int unusedCount = 0;
        int lowUsageCount = 0;
        BigDecimal underutilizedMonthly = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal underutilizedAnnual = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (subscriptions != null) {
            for (Subscription sub : subscriptions) {
                if (sub == null || sub.status != Status.ACTIVE) {
                    continue;
                }
                UsageEfficiency metric = evaluateSingle(sub, usagesBySub.getOrDefault(sub.id, List.of()), windowStart, today);
                rows.add(metric);
                if (metric.level() == UtilizationLevel.UNUSED) {
                    unusedCount++;
                } else if (metric.level() == UtilizationLevel.LOW_USAGE) {
                    lowUsageCount++;
                }
                if (metric.level().isUnderutilized()) {
                    underutilizedMonthly = underutilizedMonthly.add(metric.monthlyCost());
                    underutilizedAnnual = underutilizedAnnual.add(annualEquivalent(sub));
                }
            }
        }

        rows.sort(Comparator.comparingInt((UsageEfficiency e) -> e.level().ordinal())
                .thenComparing(UsageEfficiency::monthlyCost, Comparator.reverseOrder())
                .thenComparing(e -> e.subscription().name, String.CASE_INSENSITIVE_ORDER));

        return new EfficiencyReport(
                windowDays,
                rows.size(),
                unusedCount,
                lowUsageCount,
                unusedCount + lowUsageCount,
                underutilizedMonthly,
                underutilizedAnnual,
                rows);
    }

    public static UsageEfficiency evaluateSingleSubscription(
            Subscription sub,
            List<Usage> subUsages,
            LocalDate today,
            int windowDays) {
        LocalDate windowStart = today.minusDays(windowDays - 1L);
        return evaluateSingle(sub, subUsages == null ? List.of() : subUsages, windowStart, today);
    }

    private static UsageEfficiency evaluateSingle(
            Subscription sub,
            List<Usage> subUsages,
            LocalDate windowStart,
            LocalDate today) {
        int usesInWindow = 0;
        double qtySum = 0.0;
        Set<String> units = new LinkedHashSet<>();
        LocalDate lastUsed = null;

        for (Usage u : subUsages) {
            if (u.date != null && (lastUsed == null || u.date.isAfter(lastUsed))) {
                lastUsed = u.date;
            }
            if (u.date != null && !u.date.isBefore(windowStart) && !u.date.isAfter(today)) {
                usesInWindow++;
                if (u.quantity != null && u.quantity > 0) {
                    qtySum += u.quantity;
                    if (u.unit != null && !u.unit.isBlank()) {
                        units.add(u.unit.trim());
                    }
                }
            }
        }

        BigDecimal monthlyCost = monthlyEquivalent(sub);
        BigDecimal perDay = dailyCost(sub);
        BigDecimal perUse = costPerUse(monthlyCost, usesInWindow);
        String primaryUnit = units.size() == 1 ? units.iterator().next() : null;
        UtilizationLevel level = classifyUtilization(usesInWindow);

        return new UsageEfficiency(
                sub,
                monthlyCost,
                perDay,
                usesInWindow,
                qtySum,
                primaryUnit,
                perUse,
                lastUsed,
                level);
    }
}
