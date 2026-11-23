package beenewed.model;

import beenewed.model.Models.Contribution;
import beenewed.model.Models.Member;
import beenewed.model.Models.Payment;
import beenewed.model.Models.Subscription;
import com.beenewed.backend.exception.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Family &amp; shared billing split engine plus zero-migration metadata codec.
 * <p>
 * Handles equal splitting with exact cent-level remainder distribution, custom split sum
 * validation, Group Head / Payer paid-vs-pending settlement summaries, and transparent
 * persistence of payer/split metadata inside {@code members.role_notes} and {@code payments.notes}.
 */
public final class SplitAllocator {
    private SplitAllocator() {}

    public static final String OWNER_KEY = "owner";
    public static final String OWNER_LABEL = "You (Account Owner)";

    public enum SplitMode {
        EQUAL("Equal Split"),
        CUSTOM("Custom Split");
        public final String label;
        SplitMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum SettlementStatus {
        PAYER("Paid (Payer)"),
        PAID("Paid"),
        PARTIAL("Partial"),
        PENDING("Pending");
        public final String label;
        SettlementStatus(String label) { this.label = label; }
    }

    public record Participant(String key, String name, boolean owner, Member member) {
        public static Participant ownerParticipant() {
            return new Participant(OWNER_KEY, OWNER_LABEL, true, null);
        }

        public static Participant ofMember(Member member) {
            Objects.requireNonNull(member, "member must not be null");
            return new Participant(memberKey(member.id), member.name, false, member);
        }
    }

    public record ParticipantShare(Participant participant, BigDecimal share, boolean payer) {}

    public record DistributionRow(
            Participant participant,
            BigDecimal expectedShare,
            BigDecimal paidAmount,
            BigDecimal pendingAmount,
            SettlementStatus status) {}

    public record DistributionSummary(
            Payment payment,
            String payerKey,
            String payerName,
            BigDecimal totalPayment,
            BigDecimal payerShare,
            BigDecimal totalToCollect,
            BigDecimal totalCollected,
            BigDecimal totalPending,
            List<DistributionRow> rows) {}

    public record MemberSplitMeta(
            boolean payer,
            BigDecimal customShare,
            boolean includeOwner,
            SplitMode mode) {
        public static final MemberSplitMeta DEFAULT = new MemberSplitMeta(false, null, true, SplitMode.EQUAL);
    }

    public record PaymentSplitMeta(
            String payerKey,
            boolean splitShareOnly,
            Map<String, BigDecimal> shareSnapshot) {
        public static final PaymentSplitMeta DEFAULT = new PaymentSplitMeta(null, false, Collections.emptyMap());
    }

    public record SplitConfig(
            String payerKey,
            boolean includeOwner,
            SplitMode mode,
            List<ParticipantShare> shares) {}

    private static final Pattern META_BLOCK = Pattern.compile("\\s*\\[\\[beenewed:([^\\]]+)\\]\\]\\s*$");

    public static String memberKey(int memberId) {
        return "member:" + memberId;
    }

    // ---------------------------------------------------------------------
    // Split Math & Validation
    // ---------------------------------------------------------------------

    /**
     * Splits {@code total} equally across {@code participants}, distributing any fractional
     * remainder cents ({@code 0.01} each) deterministically from the first participant onward
     * so that the sum of shares always equals {@code total} to the exact cent.
     */
    public static List<BigDecimal> splitEqually(BigDecimal total, int participantCount) {
        if (total == null || total.signum() <= 0) {
            throw new ValidationException("Total amount to split must be greater than zero.");
        }
        if (participantCount <= 0) {
            throw new ValidationException("At least one participant is required to split a payment.");
        }
        BigDecimal scaled = BillingRules.money(total);
        long totalCents = scaled.unscaledValue().longValueExact();
        long baseCents = totalCents / participantCount;
        long remainder = totalCents % participantCount;

        List<BigDecimal> result = new ArrayList<>(participantCount);
        for (int i = 0; i < participantCount; i++) {
            long cents = baseCents + (i < remainder ? 1 : 0);
            result.add(BigDecimal.valueOf(cents, 2));
        }
        return result;
    }

    public static List<ParticipantShare> splitEqually(
            BigDecimal total,
            List<Participant> participants,
            String payerKey) {
        if (participants == null || participants.isEmpty()) {
            throw new ValidationException("At least one participant is required to split a payment.");
        }
        List<BigDecimal> amounts = splitEqually(total, participants.size());
        String effectivePayer = resolveEffectivePayer(payerKey, participants);
        List<ParticipantShare> rows = new ArrayList<>(participants.size());
        for (int i = 0; i < participants.size(); i++) {
            Participant p = participants.get(i);
            rows.add(new ParticipantShare(p, amounts.get(i), p.key().equals(effectivePayer)));
        }
        return rows;
    }

    /**
     * Validates that custom split allocations are non-negative, finite (2-decimal scale),
     * and sum to {@code expectedTotal} down to the exact cent.
     */
    public static void validateCustomSplits(BigDecimal expectedTotal, Map<String, BigDecimal> allocations) {
        if (expectedTotal == null || expectedTotal.signum() <= 0) {
            throw new ValidationException("Subscription total must be greater than zero.");
        }
        if (allocations == null || allocations.isEmpty()) {
            throw new ValidationException("At least one participant allocation is required.");
        }
        BigDecimal target = BillingRules.money(expectedTotal);
        BigDecimal sum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (Map.Entry<String, BigDecimal> entry : allocations.entrySet()) {
            BigDecimal val = entry.getValue();
            if (val == null || val.signum() < 0) {
                throw new ValidationException("Split allocations cannot be blank or negative.");
            }
            BigDecimal scaled = val.setScale(2, RoundingMode.HALF_UP);
            if (val.compareTo(scaled) != 0) {
                throw new ValidationException("Split allocations must use at most 2 decimal places.");
            }
            sum = sum.add(scaled);
        }
        int cmp = sum.compareTo(target);
        if (cmp < 0) {
            BigDecimal diff = target.subtract(sum);
            throw new ValidationException("Split total (" + BillingRules.peso(sum) + ") is "
                    + BillingRules.peso(diff) + " short of the total (" + BillingRules.peso(target) + ").");
        }
        if (cmp > 0) {
            BigDecimal diff = sum.subtract(target);
            throw new ValidationException("Split total (" + BillingRules.peso(sum) + ") exceeds the total ("
                    + BillingRules.peso(target) + ") by " + BillingRules.peso(diff) + ".");
        }
    }

    // ---------------------------------------------------------------------
    // Subscription Split Resolution & Allowed Payment Shares
    // ---------------------------------------------------------------------

    /** Resolves the active split configuration for a subscription from its members' metadata. */
    public static SplitConfig resolveSubscriptionSplit(Subscription sub, List<Member> members) {
        BigDecimal total = BillingRules.money(sub.price);
        if (members == null || members.isEmpty()) {
            Participant owner = Participant.ownerParticipant();
            return new SplitConfig(
                    OWNER_KEY,
                    true,
                    SplitMode.EQUAL,
                    List.of(new ParticipantShare(owner, total, true)));
        }

        boolean includeOwner = true;
        SplitMode mode = SplitMode.EQUAL;
        String payerKey = OWNER_KEY;
        Map<String, BigDecimal> savedCustomShares = new LinkedHashMap<>();

        for (Member m : members) {
            MemberSplitMeta meta = parseMemberMeta(m.notes);
            if (!meta.includeOwner()) {
                includeOwner = false;
            }
            if (meta.mode() == SplitMode.CUSTOM) {
                mode = SplitMode.CUSTOM;
            }
            if (meta.payer()) {
                payerKey = memberKey(m.id);
            }
            if (meta.customShare() != null) {
                savedCustomShares.put(memberKey(m.id), BillingRules.money(meta.customShare()));
            }
        }

        List<Participant> participants = buildParticipants(members, includeOwner);
        String effectivePayer = resolveEffectivePayer(payerKey, participants);

        if (mode == SplitMode.CUSTOM && savedCustomShares.size() == members.size()) {
            BigDecimal memberSum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            for (BigDecimal share : savedCustomShares.values()) {
                memberSum = memberSum.add(share);
            }
            Map<String, BigDecimal> fullMap = new LinkedHashMap<>();
            if (includeOwner) {
                BigDecimal ownerShare = total.subtract(memberSum);
                if (ownerShare.signum() >= 0) {
                    fullMap.put(OWNER_KEY, ownerShare);
                }
            }
            for (Member m : members) {
                fullMap.put(memberKey(m.id), savedCustomShares.get(memberKey(m.id)));
            }
            if (fullMap.size() == participants.size() && sumEquals(fullMap.values(), total)) {
                List<ParticipantShare> customRows = new ArrayList<>(participants.size());
                for (Participant p : participants) {
                    customRows.add(new ParticipantShare(p, fullMap.get(p.key()), p.key().equals(effectivePayer)));
                }
                return new SplitConfig(effectivePayer, includeOwner, SplitMode.CUSTOM, customRows);
            }
        }

        List<ParticipantShare> equalRows = splitEqually(total, participants, effectivePayer);
        return new SplitConfig(effectivePayer, includeOwner, SplitMode.EQUAL, equalRows);
    }

    /**
     * Returns distinct positive split share amounts allowed when recording a payment
     * (in addition to the full subscription price) for a shared subscription.
     */
    public static Set<BigDecimal> allowedSplitShares(Subscription sub, List<Member> members) {
        if (members == null || members.isEmpty()) {
            return Collections.emptySet();
        }
        SplitConfig config = resolveSubscriptionSplit(sub, members);
        Set<BigDecimal> shares = new LinkedHashSet<>();
        for (ParticipantShare ps : config.shares()) {
            if (ps.share().signum() > 0) {
                shares.add(ps.share());
            }
        }
        return shares;
    }

    /** Returns the account owner's designated split share (or {@code null} if not shared or excluded). */
    public static BigDecimal ownerSplitShare(Subscription sub, List<Member> members) {
        if (members == null || members.isEmpty()) {
            return null;
        }
        SplitConfig config = resolveSubscriptionSplit(sub, members);
        for (ParticipantShare ps : config.shares()) {
            if (ps.participant().owner()) {
                return ps.share();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Group Head / Payer Distribution Computation
    // ---------------------------------------------------------------------

    /**
     * Computes paid vs. pending distributions for a recorded payment from the Group Head / Payer perspective.
     */
    public static DistributionSummary computeDistribution(
            Payment payment,
            List<Member> members,
            List<Contribution> paymentContributions) {
        Objects.requireNonNull(payment, "payment must not be null");
        BigDecimal totalPayment = BillingRules.money(payment.amount);
        SplitConfig currentConfig = resolveSubscriptionSplit(payment.sub, members);
        PaymentSplitMeta payMeta = parsePaymentMeta(payment.notes);

        List<Participant> participants = buildParticipants(members, currentConfig.includeOwner());
        String payerKey = resolveEffectivePayer(
                payMeta.payerKey() != null ? payMeta.payerKey() : currentConfig.payerKey(),
                participants);

        List<ParticipantShare> effectiveShares = resolvePaymentShares(
                totalPayment, participants, payerKey, currentConfig, payMeta);

        return computeDistribution(payment, payerKey, effectiveShares, paymentContributions);
    }

    /**
     * Pure overload that computes paid vs. pending settlement rows directly from explicit shares
     * and recorded contributions.
     */
    public static DistributionSummary computeDistribution(
            Payment payment,
            String payerKey,
            List<ParticipantShare> shares,
            List<Contribution> paymentContributions) {
        Objects.requireNonNull(payment, "payment must not be null");
        BigDecimal totalPayment = BillingRules.money(payment.amount);

        Map<Integer, BigDecimal> contributedByMember = new LinkedHashMap<>();
        if (paymentContributions != null) {
            for (Contribution c : paymentContributions) {
                if (c == null || c.member == null) continue;
                if (c.payment != null && c.payment.id != payment.id) continue;
                contributedByMember.merge(c.member.id, BillingRules.money(c.amount), BigDecimal::add);
            }
        }

        String payerName = OWNER_LABEL;
        BigDecimal payerShare = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalToCollect = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalCollected = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPending = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<DistributionRow> rows = new ArrayList<>(shares.size());

        for (ParticipantShare ps : shares) {
            Participant participant = ps.participant();
            BigDecimal expected = BillingRules.money(ps.share());
            boolean isPayer = ps.payer() || participant.key().equals(payerKey);
            if (isPayer) {
                payerName = participant.name();
                payerShare = expected;
                rows.add(new DistributionRow(
                        participant,
                        expected,
                        expected,
                        BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                        SettlementStatus.PAYER));
                continue;
            }

            BigDecimal paid = participant.member() == null
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : contributedByMember.getOrDefault(
                            participant.member().id,
                            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            BigDecimal pending = expected.subtract(paid);
            if (pending.signum() < 0) {
                pending = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }

            SettlementStatus status;
            if (expected.signum() == 0 || pending.signum() == 0) {
                status = SettlementStatus.PAID;
            } else if (paid.signum() > 0) {
                status = SettlementStatus.PARTIAL;
            } else {
                status = SettlementStatus.PENDING;
            }

            totalToCollect = totalToCollect.add(expected);
            totalCollected = totalCollected.add(paid.min(expected));
            totalPending = totalPending.add(pending);

            rows.add(new DistributionRow(participant, expected, paid, pending, status));
        }

        return new DistributionSummary(
                payment,
                payerKey,
                payerName,
                totalPayment,
                payerShare,
                totalToCollect,
                totalCollected,
                totalPending,
                rows);
    }

    private static List<ParticipantShare> resolvePaymentShares(
            BigDecimal totalPayment,
            List<Participant> participants,
            String payerKey,
            SplitConfig currentConfig,
            PaymentSplitMeta payMeta) {
        // 1. Prefer the snapshot recorded at payment time if it matches all participants and totals
        if (!payMeta.shareSnapshot().isEmpty() && sumEquals(payMeta.shareSnapshot().values(), totalPayment)) {
            List<ParticipantShare> fromSnap = new ArrayList<>();
            boolean allMatched = true;
            for (Participant p : participants) {
                BigDecimal amt = payMeta.shareSnapshot().get(p.key());
                if (amt == null) {
                    allMatched = false;
                    break;
                }
                fromSnap.add(new ParticipantShare(p, amt, p.key().equals(payerKey)));
            }
            if (allMatched && fromSnap.size() == payMeta.shareSnapshot().size()) {
                return fromSnap;
            }
        }

        // 2. If this payment was recorded for only a single participant's split share, show it on the payer
        if (payMeta.splitShareOnly()) {
            List<ParticipantShare> single = new ArrayList<>(participants.size());
            for (Participant p : participants) {
                boolean isPayer = p.key().equals(payerKey);
                single.add(new ParticipantShare(
                        p,
                        isPayer ? totalPayment : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                        isPayer));
            }
            return single;
        }

        // 3. Reuse current subscription split if its sum matches the payment amount
        BigDecimal currentSum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (ParticipantShare ps : currentConfig.shares()) {
            currentSum = currentSum.add(ps.share());
        }
        if (currentSum.compareTo(totalPayment) == 0) {
            List<ParticipantShare> adjusted = new ArrayList<>(currentConfig.shares().size());
            for (ParticipantShare ps : currentConfig.shares()) {
                adjusted.add(new ParticipantShare(ps.participant(), ps.share(), ps.participant().key().equals(payerKey)));
            }
            return adjusted;
        }

        // 4. Fallback: equal split of the historical payment amount
        return splitEqually(totalPayment, participants, payerKey);
    }

    public static List<Participant> buildParticipants(List<Member> members, boolean includeOwner) {
        List<Participant> list = new ArrayList<>();
        if (includeOwner) {
            list.add(Participant.ownerParticipant());
        }
        if (members != null) {
            for (Member m : members) {
                list.add(Participant.ofMember(m));
            }
        }
        if (list.isEmpty()) {
            list.add(Participant.ownerParticipant());
        }
        return list;
    }

    private static String resolveEffectivePayer(String preferredKey, List<Participant> participants) {
        if (preferredKey != null) {
            for (Participant p : participants) {
                if (p.key().equals(preferredKey)) {
                    return preferredKey;
                }
            }
        }
        return participants.get(0).key();
    }

    private static boolean sumEquals(Collection<BigDecimal> values, BigDecimal target) {
        BigDecimal sum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (BigDecimal v : values) {
            if (v == null || v.signum() < 0) return false;
            sum = sum.add(BillingRules.money(v));
        }
        return sum.compareTo(BillingRules.money(target)) == 0;
    }

    // ---------------------------------------------------------------------
    // Zero-Migration Metadata Codec (stored in role_notes / payment.notes)
    // ---------------------------------------------------------------------

    /** Strips any internal {@code [[beenewed:...]]} metadata block so UI displays only user notes. */
    public static String cleanNotes(String rawNotes) {
        if (rawNotes == null) {
            return null;
        }
        String stripped = META_BLOCK.matcher(rawNotes).replaceFirst("").trim();
        return stripped.isEmpty() ? null : stripped;
    }

    public static MemberSplitMeta parseMemberMeta(String rawRoleNotes) {
        Map<String, String> kv = extractMetaMap(rawRoleNotes);
        if (kv.isEmpty()) {
            return MemberSplitMeta.DEFAULT;
        }
        boolean payer = "true".equalsIgnoreCase(kv.get("payer"));
        boolean includeOwner = !"false".equalsIgnoreCase(kv.get("owner"));
        SplitMode mode = "CUSTOM".equalsIgnoreCase(kv.get("mode")) ? SplitMode.CUSTOM : SplitMode.EQUAL;
        BigDecimal share = null;
        String shareStr = kv.get("share");
        if (shareStr != null && !shareStr.isBlank()) {
            try {
                BigDecimal parsed = new BigDecimal(shareStr).setScale(2, RoundingMode.HALF_UP);
                if (parsed.signum() >= 0) {
                    share = parsed;
                }
            } catch (NumberFormatException ignored) {
                // ignore malformed legacy text
            }
        }
        return new MemberSplitMeta(payer, share, includeOwner, mode);
    }

    public static String encodeMemberNotes(String userNotes, MemberSplitMeta meta) {
        String clean = cleanNotes(userNotes);
        if (meta == null || (!meta.payer() && meta.customShare() == null && meta.includeOwner() && meta.mode() == SplitMode.EQUAL)) {
            return clean;
        }
        List<String> parts = new ArrayList<>();
        if (meta.payer()) {
            parts.add("payer=true");
        }
        if (!meta.includeOwner()) {
            parts.add("owner=false");
        }
        if (meta.mode() == SplitMode.CUSTOM) {
            parts.add("mode=CUSTOM");
        }
        if (meta.customShare() != null) {
            parts.add("share=" + BillingRules.money(meta.customShare()).toPlainString());
        }
        String block = "[[beenewed:" + String.join(",", parts) + "]]";
        return clean == null ? block : clean + "\n" + block;
    }

    public static PaymentSplitMeta parsePaymentMeta(String rawPaymentNotes) {
        Map<String, String> kv = extractMetaMap(rawPaymentNotes);
        if (kv.isEmpty()) {
            return PaymentSplitMeta.DEFAULT;
        }
        String payerKey = kv.get("payer");
        boolean splitOnly = "split".equalsIgnoreCase(kv.get("scope"));
        Map<String, BigDecimal> snapshot = new LinkedHashMap<>();
        String sharesRaw = kv.get("shares");
        if (sharesRaw != null && !sharesRaw.isBlank()) {
            for (String item : sharesRaw.split(";")) {
                int eq = item.lastIndexOf('=');
                if (eq > 0 && eq < item.length() - 1) {
                    String key = item.substring(0, eq).trim();
                    String val = item.substring(eq + 1).trim();
                    try {
                        BigDecimal amt = new BigDecimal(val).setScale(2, RoundingMode.HALF_UP);
                        if (amt.signum() >= 0) {
                            snapshot.put(key, amt);
                        }
                    } catch (NumberFormatException ignored) {
                        // ignore malformed entry
                    }
                }
            }
        }
        return new PaymentSplitMeta(payerKey, splitOnly, snapshot);
    }

    public static String encodePaymentNotes(String userNotes, PaymentSplitMeta meta) {
        String clean = cleanNotes(userNotes);
        if (meta == null || (meta.payerKey() == null && !meta.splitShareOnly() && meta.shareSnapshot().isEmpty())) {
            return clean;
        }
        List<String> parts = new ArrayList<>();
        if (meta.payerKey() != null && !meta.payerKey().isBlank()) {
            parts.add("payer=" + meta.payerKey().trim());
        }
        if (meta.splitShareOnly()) {
            parts.add("scope=split");
        }
        if (meta.shareSnapshot() != null && !meta.shareSnapshot().isEmpty()) {
            List<String> encodedShares = new ArrayList<>(meta.shareSnapshot().size());
            for (Map.Entry<String, BigDecimal> entry : meta.shareSnapshot().entrySet()) {
                encodedShares.add(entry.getKey() + "=" + BillingRules.money(entry.getValue()).toPlainString());
            }
            parts.add("shares=" + String.join(";", encodedShares));
        }
        String block = "[[beenewed:" + String.join(",", parts) + "]]";
        return clean == null ? block : clean + "\n" + block;
    }

    private static Map<String, String> extractMetaMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        Matcher m = META_BLOCK.matcher(raw);
        if (!m.find()) {
            return Collections.emptyMap();
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (String pair : m.group(1).split(",")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && idx < pair.length() - 1) {
                kv.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
            }
        }
        return kv;
    }
}
