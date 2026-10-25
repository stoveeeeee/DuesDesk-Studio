package com.fraternity.dues;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Works out the history behind the Numbers page graphs from the ledger itself: every dated payment and charge, for
 * current members and for members a semester rollover moved to the archive. Because it is calculated from the
 * ledger, a graph exists for any date the ledger covers, including data that was imported rather than entered day
 * by day.
 *
 * <p>The rules match the live stats. A member's balance on a past date is today's balance with the later payments
 * added back and the later charges taken off. A charge is past due once its due date has passed and it is unpaid,
 * with payments applied to the oldest due date first (as {@link Store} does when a payment is recorded).
 */
public final class MetricHistoryCalculator {
    public static final String COLLECTION_VELOCITY = "collection-velocity-run-rate";
    public static final String NET_COLLECTION_RATE = "net-collection-rate";
    public static final String DELINQUENCY_RATE = "delinquency-default-rate";
    public static final String AVERAGE_DUES_PER_MEMBER = "average-dues-per-member-adpm";
    public static final List<String> METRICS = List.of(
            COLLECTION_VELOCITY, NET_COLLECTION_RATE, DELINQUENCY_RATE, AVERAGE_DUES_PER_MEMBER);

    /** A graph never needs more points than this; longer ranges are sampled at wider steps. */
    static final int MAX_POINTS = 120;
    /** A member counts toward the stats from a little before their first ledger entry. */
    static final int LEAD_DAYS = 14;
    /** An archived member counts until a little after their last ledger entry (their last term's wrap-up). */
    static final int GRACE_DAYS = 45;

    public record Point(String date, double value) {}

    private MetricHistoryCalculator() {}

    private record Payment(LocalDate date, double amount) {}

    private record Charge(LocalDate issued, LocalDate due, double amount) {
        /** The date payments are applied by: the due date, or the issue date for a charge without one. */
        LocalDate applyOrder() { return due == null ? issued : due; }
    }

    private static final class Ledger {
        boolean archived;
        double owedNow;
        double pastDueNow;
        final List<Payment> payments = new ArrayList<>();
        final List<Charge> charges = new ArrayList<>();
        LocalDate first;
        LocalDate last;

        boolean activeOn(LocalDate day) {
            if (first == null) return !archived; // a current member with no dated entries is always on the roster
            if (day.isBefore(first.minusDays(LEAD_DAYS))) return false;
            return !archived || !day.isAfter(last.plusDays(GRACE_DAYS));
        }
    }

    private record Totals(double recorded, double owed, double pastDue, double paidThisMonth, int members) {}

    /**
     * The history of {@code metric} between {@code start} and {@code end} (never past {@code today}). Returns no points
     * for an unknown metric or when no member has ledger entries in the range.
     */
    public static List<Point> history(String metric, List<Member> members, List<Member> archivedMembers,
                                      LocalDate start, LocalDate end, LocalDate today) {
        if (!METRICS.contains(metric)) return List.of();
        List<Ledger> ledgers = new ArrayList<>();
        if (members != null) members.forEach(member -> ledgers.add(ledgerOf(member, false)));
        if (archivedMembers != null) archivedMembers.forEach(member -> ledgers.add(ledgerOf(member, true)));
        if (ledgers.stream().noneMatch(ledger -> ledger.first != null)) return List.of();

        LocalDate to = end.isAfter(today) ? today : end;
        if (start.isAfter(to)) return List.of();
        List<Point> points = new ArrayList<>();
        for (LocalDate day : sampleDays(start, to)) {
            Totals totals = totalsOn(ledgers, day);
            if (totals.members() == 0) continue;
            points.add(new Point(day.toString(), valueOf(metric, totals, day)));
        }
        return points;
    }

    /** Every day for short ranges, widening to keep a graph near {@link #MAX_POINTS}; the last day is always included. */
    static List<LocalDate> sampleDays(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        int step = (int) Math.max(1, Math.ceil(days / (double) (MAX_POINTS - 1)));
        List<LocalDate> result = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(step)) result.add(day);
        if (!result.get(result.size() - 1).equals(to)) result.add(to);
        return result;
    }

    private static double valueOf(String metric, Totals totals, LocalDate day) {
        double pool = totals.recorded() + totals.owed();
        double value = switch (metric) {
            case COLLECTION_VELOCITY -> totals.paidThisMonth() / day.getDayOfMonth();
            case NET_COLLECTION_RATE -> pool > 0 ? totals.recorded() / pool * 100 : 0;
            case DELINQUENCY_RATE -> pool > 0 ? totals.pastDue() / pool * 100 : 0;
            default -> totals.recorded() / totals.members();
        };
        return Math.round(value * 100) / 100.0;
    }

    private static Totals totalsOn(List<Ledger> ledgers, LocalDate day) {
        double recorded = 0, owed = 0, pastDue = 0, paidThisMonth = 0;
        int members = 0;
        YearMonth month = YearMonth.from(day);
        for (Ledger ledger : ledgers) {
            if (!ledger.activeOn(day)) continue;
            members++;
            double paidTo = 0, paidAfter = 0;
            for (Payment payment : ledger.payments) {
                if (payment.date().isAfter(day)) {
                    paidAfter += payment.amount();
                } else {
                    paidTo += payment.amount();
                    if (YearMonth.from(payment.date()).equals(month)) paidThisMonth += payment.amount();
                }
            }
            double chargedAfter = ledger.charges.stream().filter(c -> c.issued().isAfter(day)).mapToDouble(Charge::amount).sum();
            double owedOn = Math.max(0, ledger.owedNow + paidAfter - chargedAfter);

            double pastDueOn;
            if (ledger.charges.isEmpty()) {
                // No charges to age: keep the saved past-due balance, reduced as later payments are added back.
                pastDueOn = ledger.pastDueNow + paidAfter;
            } else {
                double unapplied = paidTo;
                pastDueOn = 0;
                for (Charge charge : ledger.charges) {
                    if (charge.issued().isAfter(day)) continue;
                    double applied = Math.min(unapplied, charge.amount());
                    unapplied -= applied;
                    if (charge.due() != null && charge.due().isBefore(day)) pastDueOn += charge.amount() - applied;
                }
            }
            recorded += paidTo;
            owed += owedOn;
            pastDue += Math.min(owedOn, pastDueOn);
        }
        return new Totals(recorded, owed, pastDue, paidThisMonth, members);
    }

    private static Ledger ledgerOf(Member member, boolean archived) {
        Ledger ledger = new Ledger();
        ledger.archived = archived;
        ledger.owedNow = member.getTotalOwed();
        ledger.pastDueNow = member.getPastDueBalance();
        if (member.getPaymentHistory() != null) {
            for (PaymentRecord payment : member.getPaymentHistory()) {
                LocalDate date = parse(payment.getDate());
                if (date != null) ledger.payments.add(new Payment(date, payment.getAmount()));
            }
        }
        if (member.getCharges() != null) {
            for (ChargeRecord charge : member.getCharges()) {
                LocalDate issued = parse(charge.getDate());
                if (issued != null) ledger.charges.add(new Charge(issued, parse(charge.getDueDate()), charge.getAmount()));
            }
        }
        ledger.charges.sort(Comparator.comparing(Charge::applyOrder).thenComparing(Charge::issued));
        ledger.payments.forEach(payment -> extend(ledger, payment.date()));
        ledger.charges.forEach(charge -> extend(ledger, charge.issued()));
        return ledger;
    }

    private static void extend(Ledger ledger, LocalDate date) {
        if (ledger.first == null || date.isBefore(ledger.first)) ledger.first = date;
        if (ledger.last == null || date.isAfter(ledger.last)) ledger.last = date;
    }

    /** Reads the day from a date or date-time ("2026-03-01" or "2026-03-01T10:15"); month-only values are skipped. */
    private static LocalDate parse(String value) {
        if (value == null || value.length() < 10) return null;
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
