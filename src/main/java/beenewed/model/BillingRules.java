package beenewed.model;

import beenewed.model.Models.Cycle;
import beenewed.model.Models.Payment;
import beenewed.model.Models.Status;
import beenewed.model.Models.Subscription;
import com.beenewed.backend.exception.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure domain rules for payment validation, duplicate billing-period prevention,
 * sequential pay-ahead renewal period calculation, amount alignment,
 * due-date advancement (weekly/monthly/yearly), and overdue evaluation.
 */
public final class BillingRules {
    public static final int DEFAULT_PAY_AHEAD_WEEKLY = 6;
    public static final int DEFAULT_PAY_AHEAD_MONTHLY = 6;
    public static final int DEFAULT_PAY_AHEAD_YEARLY = 3;

    private BillingRules() {}

    /** A single unpaid billing period in a subscription's sequential pay-ahead schedule. */
    public record PeriodOption(LocalDate dueDate, String label, String canonicalKey) {}

    public enum Interval {
        WEEKLY,
        MONTHLY,
        YEARLY;

        public static Interval fromCycle(Cycle cycle) {
            if (cycle == null) {
                throw new ValidationException("Billing cycle is required");
            }
            return switch (cycle) {
                case MONTHLY -> MONTHLY;
                case YEARLY -> YEARLY;
            };
        }
    }

    public static int defaultPayAheadCount(Interval interval) {
        if (interval == null) {
            return DEFAULT_PAY_AHEAD_MONTHLY;
        }
        return switch (interval) {
            case WEEKLY -> DEFAULT_PAY_AHEAD_WEEKLY;
            case MONTHLY -> DEFAULT_PAY_AHEAD_MONTHLY;
            case YEARLY -> DEFAULT_PAY_AHEAD_YEARLY;
        };
    }

    private static final DateTimeFormatter MONTH_YEAR_FULL = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("MMMM yyyy")
            .toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_YEAR_SHORT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("MMM yyyy")
            .toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter ISO_YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter SLASH_MONTH_YEAR = DateTimeFormatter.ofPattern("M/yyyy");
    private static final DateTimeFormatter WEEK_LABEL_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("MMM d, yyyy")
            .toFormatter(Locale.ENGLISH);
    private static final Pattern YEAR_ONLY = Pattern.compile("^(?:fy\\s*)?(\\d{4})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO_WEEK = Pattern.compile("^(\\d{4})-?w(\\d{1,2})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEK_OF_PREFIX = Pattern.compile("^week\\s+of\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /**
     * Advances a subscription's due date by exactly one billing interval.
     * Works consistently for early, on-time, and overdue payments, and handles
     * month-end rollovers (e.g., Jan 31 -> Feb 28/29) and leap years safely.
     */
    public static LocalDate advanceNextDueDate(LocalDate currentDueDate, Interval interval) {
        if (currentDueDate == null || interval == null) {
            throw new ValidationException("Current due date and billing interval are required");
        }
        return switch (interval) {
            case WEEKLY -> currentDueDate.plusWeeks(1);
            case MONTHLY -> currentDueDate.plusMonths(1);
            case YEARLY -> currentDueDate.plusYears(1);
        };
    }

    public static LocalDate advanceNextDueDate(LocalDate currentDueDate, Cycle cycle) {
        return advanceNextDueDate(currentDueDate, Interval.fromCycle(cycle));
    }

    /**
     * Evaluates whether a subscription is currently overdue.
     * Only {@link Status#ACTIVE} subscriptions with a due date strictly before {@code today} are overdue.
     */
    public static boolean isOverdue(Status status, LocalDate nextDueDate, LocalDate today) {
        return status == Status.ACTIVE
                && nextDueDate != null
                && today != null
                && nextDueDate.isBefore(today);
    }

    /** Formats a human-readable default renewal period label from a subscription's due date. */
    public static String defaultPeriodLabel(LocalDate dueDate, Interval interval) {
        if (dueDate == null) {
            return "";
        }
        if (interval == Interval.WEEKLY) {
            return "Week of " + WEEK_LABEL_DATE.format(dueDate);
        }
        return dueDate.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + dueDate.getYear();
    }

    public static String defaultPeriodLabel(LocalDate dueDate, Cycle cycle) {
        return defaultPeriodLabel(dueDate, cycle == null ? Interval.MONTHLY : Interval.fromCycle(cycle));
    }

    /**
     * Normalizes a renewal period label into a canonical key for duplicate detection.
     * Equivalent forms such as {@code "November 2026"}, {@code "Nov 2026"}, and {@code "2026-11"}
     * map to the same canonical key ({@code "2026-11"}).
     */
    public static String canonicalPeriodKey(String renewalPeriod, Interval interval) {
        if (renewalPeriod == null) {
            return "";
        }
        String collapsed = renewalPeriod.trim().replaceAll("\\s+", " ");
        if (collapsed.isEmpty()) {
            return "";
        }

        if (interval == Interval.WEEKLY) {
            Matcher isoWeek = ISO_WEEK.matcher(collapsed);
            if (isoWeek.matches()) {
                int y = Integer.parseInt(isoWeek.group(1));
                int w = Integer.parseInt(isoWeek.group(2));
                return String.format(Locale.ROOT, "%04d-W%02d", y, w);
            }
            Matcher weekOf = WEEK_OF_PREFIX.matcher(collapsed);
            if (weekOf.matches()) {
                LocalDate parsed = tryParseDate(weekOf.group(1).trim());
                if (parsed != null) {
                    int weekYear = parsed.get(IsoFields.WEEK_BASED_YEAR);
                    int weekNum = parsed.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                    return String.format(Locale.ROOT, "%04d-W%02d", weekYear, weekNum);
                }
            }
        }

        YearMonth ym = tryParseYearMonth(collapsed);
        if (ym != null) {
            return ym.toString(); // YYYY-MM
        }

        Matcher yearOnly = YEAR_ONLY.matcher(collapsed);
        if (yearOnly.matches()) {
            return Year.of(Integer.parseInt(yearOnly.group(1))).toString();
        }

        return collapsed.toLowerCase(Locale.ROOT);
    }

    public static String canonicalPeriodKey(String renewalPeriod, Cycle cycle) {
        return canonicalPeriodKey(renewalPeriod, cycle == null ? Interval.MONTHLY : Interval.fromCycle(cycle));
    }

    /**
     * Collects canonical keys for every renewal period already recorded for {@code subscription}.
     */
    public static Set<String> recordedPeriodKeys(Subscription subscription, List<Payment> existingPayments) {
        Set<String> keys = new LinkedHashSet<>();
        if (subscription == null || existingPayments == null || existingPayments.isEmpty()) {
            return keys;
        }
        Interval interval = Interval.fromCycle(subscription.cycle);
        for (Payment existing : existingPayments) {
            if (existing == null || existing.sub == null || existing.sub.id != subscription.id) {
                continue;
            }
            String key = canonicalPeriodKey(existing.period, interval);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Calculates the next available unpaid renewal periods sequentially for {@code subscription}
     * based on its billing cycle (default: 6 consecutive unpaid monthly/weekly periods, 3 yearly).
     * Already-paid periods are always excluded.
     */
    public static List<PeriodOption> upcomingUnpaidPeriods(
            Subscription subscription,
            List<Payment> existingPayments) {
        if (subscription == null || subscription.cycle == null) {
            return List.of();
        }
        return upcomingUnpaidPeriods(subscription, existingPayments, defaultPayAheadCount(Interval.fromCycle(subscription.cycle)));
    }

    /**
     * Calculates up to {@code maxCount} consecutive unpaid renewal periods sequentially
     * starting from the subscription's next due cycle, excluding any already-paid periods.
     */
    public static List<PeriodOption> upcomingUnpaidPeriods(
            Subscription subscription,
            List<Payment> existingPayments,
            int maxCount) {
        if (subscription == null || subscription.nextRenewal == null || subscription.cycle == null || maxCount <= 0) {
            return List.of();
        }
        Interval interval = Interval.fromCycle(subscription.cycle);
        Set<String> paidKeys = recordedPeriodKeys(subscription, existingPayments);
        List<PeriodOption> options = new ArrayList<>(maxCount);
        LocalDate cursor = subscription.nextRenewal;
        int maxSteps = maxCount + paidKeys.size() + 24;
        for (int step = 0; step < maxSteps && options.size() < maxCount; step++) {
            String label = defaultPeriodLabel(cursor, interval);
            String key = canonicalPeriodKey(label, interval);
            if (!key.isEmpty() && !paidKeys.contains(key)) {
                options.add(new PeriodOption(cursor, label, key));
            }
            cursor = advanceNextDueDate(cursor, interval);
        }
        return List.copyOf(options);
    }

    /** Returns just the display labels of the sequential unpaid upcoming periods. */
    public static List<String> upcomingUnpaidPeriodLabels(
            Subscription subscription,
            List<Payment> existingPayments) {
        return upcomingUnpaidPeriods(subscription, existingPayments).stream()
                .map(PeriodOption::label)
                .toList();
    }

    /**
     * Validates that {@code renewalPeriod} is non-blank, has not already been paid, and is the
     * exact next unpaid period in sequence (SPayLater-style sequential pay-ahead: no skipping
     * unpaid periods and no paying an already-paid period).
     */
    public static PeriodOption validateSequentialPeriod(
            Subscription subscription,
            String renewalPeriod,
            List<Payment> existingPayments) {
        if (subscription == null) {
            throw new ValidationException("Subscription is required.");
        }
        if (renewalPeriod == null || renewalPeriod.isBlank()) {
            throw new ValidationException("Renewal period is required.");
        }
        String trimmed = renewalPeriod.trim();
        if (findDuplicatePayment(subscription, trimmed, existingPayments).isPresent()) {
            throw new ValidationException("A payment for " + subscription.name
                    + " in renewal period \"" + trimmed + "\" has already been recorded.");
        }
        List<PeriodOption> upcoming = upcomingUnpaidPeriods(subscription, existingPayments);
        if (upcoming.isEmpty()) {
            throw new ValidationException("Unable to determine the next unpaid renewal period for " + subscription.name + ".");
        }
        Interval interval = Interval.fromCycle(subscription.cycle);
        String targetKey = canonicalPeriodKey(trimmed, interval);
        PeriodOption nextExpected = upcoming.get(0);
        if (nextExpected.canonicalKey().equals(targetKey)) {
            return nextExpected;
        }
        boolean isLaterUpcoming = upcoming.stream()
                .skip(1)
                .anyMatch(opt -> opt.canonicalKey().equals(targetKey));
        if (isLaterUpcoming) {
            throw new ValidationException("Cannot skip unpaid renewal period \"" + nextExpected.label()
                    + "\". Please pay periods sequentially starting with \"" + nextExpected.label() + "\".");
        }
        throw new ValidationException("Renewal period \"" + trimmed
                + "\" is not the next unpaid period. Next due period is \"" + nextExpected.label() + "\".");
    }

    /**
     * Advances a subscription's next renewal date to the billing cycle immediately after
     * {@code paidPeriod}, skipping past any cycles already recorded in {@code existingPayments}.
     */
    public static LocalDate nextDueDateAfterPayment(
            Subscription subscription,
            PeriodOption paidPeriod,
            List<Payment> existingPayments) {
        if (subscription == null || paidPeriod == null || paidPeriod.dueDate() == null) {
            throw new ValidationException("Subscription and paid period are required to advance due date.");
        }
        Interval interval = Interval.fromCycle(subscription.cycle);
        Set<String> paidKeys = recordedPeriodKeys(subscription, existingPayments);
        paidKeys.add(paidPeriod.canonicalKey());
        LocalDate nextDue = advanceNextDueDate(paidPeriod.dueDate(), interval);
        int maxSteps = paidKeys.size() + 24;
        for (int step = 0; step < maxSteps; step++) {
            String key = canonicalPeriodKey(defaultPeriodLabel(nextDue, interval), interval);
            if (key.isEmpty() || !paidKeys.contains(key)) {
                break;
            }
            nextDue = advanceNextDueDate(nextDue, interval);
        }
        return nextDue;
    }

    /**
     * Returns an existing payment if {@code existingPayments} already contains a recorded payment
     * for the same subscription and canonical billing/renewal period.
     */
    public static Optional<Payment> findDuplicatePayment(
            Subscription subscription,
            String renewalPeriod,
            List<Payment> existingPayments) {
        if (subscription == null || existingPayments == null || existingPayments.isEmpty()) {
            return Optional.empty();
        }
        Interval interval = Interval.fromCycle(subscription.cycle);
        String targetKey = canonicalPeriodKey(renewalPeriod, interval);
        if (targetKey.isEmpty()) {
            return Optional.empty();
        }
        for (Payment existing : existingPayments) {
            if (existing == null || existing.sub == null || existing.sub.id != subscription.id) {
                continue;
            }
            String existingKey = canonicalPeriodKey(existing.period, interval);
            if (!existingKey.isEmpty() && existingKey.equals(targetKey)) {
                return Optional.of(existing);
            }
        }
        return Optional.empty();
    }

    /**
     * Validates that a recorded payment date is present and not set in the future beyond {@code today}.
     * Late payments for past/overdue renewal dates are allowed as long as {@code paymentDate <= today}.
     */
    public static void validatePaymentDate(LocalDate paymentDate, LocalDate today) {
        if (paymentDate == null) {
            throw new ValidationException("Payment date is required.");
        }
        LocalDate referenceToday = today != null ? today : LocalDate.now();
        if (paymentDate.isAfter(referenceToday)) {
            throw new ValidationException("Payment date (" + paymentDate
                    + ") cannot be in the future (must be on or before " + referenceToday + ").");
        }
    }

    /**
     * Validates that a recorded payment amount aligns with the subscription's current price
     * or one of its designated split shares (at 2-decimal peso precision).
     */
    public static void validatePaymentAmount(
            BigDecimal amount,
            BigDecimal subscriptionPrice,
            Collection<BigDecimal> allowedSplitShares) {
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Payment amount must be greater than zero.");
        }
        if (subscriptionPrice == null || subscriptionPrice.signum() <= 0) {
            throw new ValidationException("Subscription price must be greater than zero.");
        }
        BigDecimal normalizedAmount = money(amount);
        BigDecimal normalizedPrice = money(subscriptionPrice);
        if (normalizedAmount.compareTo(normalizedPrice) == 0) {
            return;
        }

        Set<BigDecimal> validShares = new LinkedHashSet<>();
        if (allowedSplitShares != null) {
            for (BigDecimal share : allowedSplitShares) {
                if (share != null && share.signum() > 0) {
                    BigDecimal normShare = money(share);
                    if (normalizedAmount.compareTo(normShare) == 0) {
                        return;
                    }
                    if (normShare.compareTo(normalizedPrice) != 0) {
                        validShares.add(normShare);
                    }
                }
            }
        }

        if (validShares.isEmpty()) {
            throw new ValidationException("Payment amount (" + peso(normalizedAmount)
                    + ") must match the subscription price (" + peso(normalizedPrice) + ").");
        }
        String shareText = validShares.stream()
                .map(BillingRules::peso)
                .reduce((a, b) -> a + " or " + b)
                .orElse("");
        throw new ValidationException("Payment amount (" + peso(normalizedAmount)
                + ") must match the subscription price (" + peso(normalizedPrice)
                + ") or a designated split share (" + shareText + ").");
    }

    public static BigDecimal money(double value) {
        if (!Double.isFinite(value)) {
            throw new ValidationException("Amount must be a finite number.");
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal money(BigDecimal value) {
        if (value == null) {
            throw new ValidationException("Amount is required.");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    static String peso(BigDecimal amount) {
        return "\u20B1" + money(amount).toPlainString();
    }

    private static YearMonth tryParseYearMonth(String text) {
        for (DateTimeFormatter fmt : new DateTimeFormatter[]{
                MONTH_YEAR_FULL, MONTH_YEAR_SHORT, ISO_YEAR_MONTH, SLASH_MONTH_YEAR}) {
            try {
                return YearMonth.parse(text, fmt);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }

    private static LocalDate tryParseDate(String text) {
        for (DateTimeFormatter fmt : new DateTimeFormatter[]{
                WEEK_LABEL_DATE, DateTimeFormatter.ISO_LOCAL_DATE}) {
            try {
                return LocalDate.parse(text, fmt);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }
}
