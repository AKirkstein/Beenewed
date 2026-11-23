package beenewed.model;

import beenewed.model.AnalyticsEngine.UsageEfficiency;
import beenewed.model.Models.Payment;
import beenewed.model.Models.Subscription;
import beenewed.model.SplitAllocator.DistributionRow;
import beenewed.model.SplitAllocator.DistributionSummary;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * RFC-4180 CSV exporter for historical payment ledgers, active subscriptions, and family split
 * distributions, with formula-injection neutralization and metadata stripping on free-text cells.
 */
public final class CsvExporter {
    private CsvExporter() {}

    private static final String CRLF = "\r\n";

    /** Exports recorded payment ledger entries (clean notes, zero internal metadata). */
    public static String exportPaymentLedger(
            List<Payment> payments,
            Function<Payment, String> payerNameResolver) {
        StringBuilder sb = new StringBuilder();
        sb.append("Payment ID,Subscription,Category,Payment Date,Renewal Period,Amount Paid (PHP),Payment Method,Paid By,Notes")
                .append(CRLF);
        if (payments != null) {
            for (Payment p : payments) {
                if (p == null || p.sub == null) continue;
                String payer = payerNameResolver == null ? SplitAllocator.OWNER_LABEL : payerNameResolver.apply(p);
                String method = p.method == null || p.method.isBlank() ? "Not specified" : p.method;
                String notes = SplitAllocator.cleanNotes(p.notes);
                row(sb,
                        raw(String.valueOf(p.id)),
                        text(p.sub.name),
                        text(p.sub.category),
                        raw(p.date == null ? "" : p.date.toString()),
                        text(p.period),
                        raw(money(p.amount)),
                        text(method),
                        text(payer),
                        text(notes));
            }
        }
        return sb.toString();
    }

    /** Exports active subscriptions together with normalized recurring costs and usage efficiency metrics. */
    public static String exportActiveSubscriptions(List<UsageEfficiency> efficiencyRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("Subscription ID,Name,Category,Status,Price (PHP),Billing Cycle,Monthly Equivalent (PHP),Annual Equivalent (PHP),Next Renewal Date,Uses (30d),Cost Per Day (PHP),Cost Per Use (PHP),Utilization")
                .append(CRLF);
        if (efficiencyRows != null) {
            for (UsageEfficiency row : efficiencyRows) {
                if (row == null || row.subscription() == null) continue;
                Subscription s = row.subscription();
                row(sb,
                        raw(String.valueOf(s.id)),
                        text(s.name),
                        text(s.category),
                        raw(s.status.label),
                        raw(money(s.price)),
                        raw(s.cycle.label),
                        raw(money(row.monthlyCost())),
                        raw(money(AnalyticsEngine.annualEquivalent(s))),
                        raw(s.nextRenewal == null ? "" : s.nextRenewal.toString()),
                        raw(String.valueOf(row.usesInWindow())),
                        raw(money(row.costPerDay())),
                        raw(row.costPerUse() == null ? "" : money(row.costPerUse())),
                        raw(row.level().label));
            }
        }
        return sb.toString();
    }

    /** Exports per-participant family split distributions (paid vs. pending) for shared payments. */
    public static String exportFamilySplits(List<DistributionSummary> distributions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Payment ID,Subscription,Payment Date,Renewal Period,Total Payment (PHP),Designated Payer,Participant,Expected Share (PHP),Paid (PHP),Pending (PHP),Settlement Status")
                .append(CRLF);
        if (distributions != null) {
            for (DistributionSummary summary : distributions) {
                if (summary == null || summary.payment() == null || summary.payment().sub == null) continue;
                Payment p = summary.payment();
                for (DistributionRow dr : summary.rows()) {
                    row(sb,
                            raw(String.valueOf(p.id)),
                            text(p.sub.name),
                            raw(p.date == null ? "" : p.date.toString()),
                            text(p.period),
                            raw(money(summary.totalPayment())),
                            text(summary.payerName()),
                            text(dr.participant().name()),
                            raw(money(dr.expectedShare())),
                            raw(money(dr.paidAmount())),
                            raw(money(dr.pendingAmount())),
                            raw(dr.status().label));
                }
            }
        }
        return sb.toString();
    }

    /** Writes a UTF-8 CSV file to disk. */
    public static void writeUtf8(Path target, String csvContent) throws IOException {
        Files.writeString(target, csvContent == null ? "" : csvContent, StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cells[i]);
        }
        sb.append(CRLF);
    }

    /** Escapes free-text values according to RFC-4180 and neutralizes spreadsheet formula injection. */
    static String text(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = value;
        char first = safe.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            safe = "'" + safe;
        }
        return raw(safe);
    }

    /** RFC-4180 quoting for trusted structured values (IDs, ISO dates, numeric amounts). */
    static String raw(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String money(double amount) {
        return BillingRules.money(amount).toPlainString();
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "" : BillingRules.money(amount).toPlainString();
    }
}
