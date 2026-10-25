package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Numbers page graphs are calculated from the ledger's dated payments and charges. */
class MetricHistoryCalculatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    private static Member member(double owed, double pastDue, List<ChargeRecord> charges, List<PaymentRecord> payments) {
        Member member = new Member();
        member.setId("m-" + System.nanoTime());
        member.setName("Test Member");
        member.setTotalOwed(owed);
        member.setPastDueBalance(pastDue);
        member.setCharges(new ArrayList<>(charges));
        member.setPaymentHistory(new ArrayList<>(payments));
        return member;
    }

    /** $900 charged Jan 10 and due Feb 10, then $300 paid Mar 1 (so $600 is owed and past due today). */
    private static Member partlyPaid() {
        ChargeRecord charge = new ChargeRecord("c1", "2026-01-10", "Spring dues", 900, 600);
        charge.setDueDate("2026-02-10");
        charge.setPaidAmount(300);
        return member(600, 600, List.of(charge), List.of(new PaymentRecord("2026-03-01", 300, "Dues", "k1")));
    }

    private static double valueOn(String metric, List<Member> members, List<Member> archived, LocalDate day) {
        return MetricHistoryCalculator.history(metric, members, archived, day, day, TODAY).get(0).value();
    }

    @Test
    void balancesOnPastDatesAreTodaysBalanceWithLaterPaymentsAddedBack() {
        List<Member> members = List.of(partlyPaid());
        // Before the charge falls due: $900 owed, none past due, nothing collected yet.
        assertEquals(0, valueOn(MetricHistoryCalculator.NET_COLLECTION_RATE, members, List.of(), LocalDate.of(2026, 2, 1)));
        assertEquals(0, valueOn(MetricHistoryCalculator.DELINQUENCY_RATE, members, List.of(), LocalDate.of(2026, 2, 1)));
        // After the due date and before any payment: all $900 is past due, 100% of the pool.
        assertEquals(100, valueOn(MetricHistoryCalculator.DELINQUENCY_RATE, members, List.of(), LocalDate.of(2026, 2, 20)));
        // After the $300 payment: $300 collected, $600 still owed and past due.
        LocalDate march5 = LocalDate.of(2026, 3, 5);
        assertEquals(33.33, valueOn(MetricHistoryCalculator.NET_COLLECTION_RATE, members, List.of(), march5));
        assertEquals(66.67, valueOn(MetricHistoryCalculator.DELINQUENCY_RATE, members, List.of(), march5));
        assertEquals(300, valueOn(MetricHistoryCalculator.AVERAGE_DUES_PER_MEMBER, members, List.of(), march5));
        assertEquals(60, valueOn(MetricHistoryCalculator.COLLECTION_VELOCITY, members, List.of(), march5)); // $300 over 5 days
    }

    @Test
    void todayMatchesTheLiveStatsForAFullyLedgeredMember() {
        List<Member> members = List.of(partlyPaid());
        assertEquals(33.33, valueOn(MetricHistoryCalculator.NET_COLLECTION_RATE, members, List.of(), TODAY));
        assertEquals(66.67, valueOn(MetricHistoryCalculator.DELINQUENCY_RATE, members, List.of(), TODAY));
    }

    @Test
    void graduatedMembersCountWhileTheyWereOnTheRosterAndDropOffAfter() {
        ChargeRecord oldCharge = new ChargeRecord("c0", "2025-01-10", "Spring 2025 dues", 800, 0);
        oldCharge.setDueDate("2025-02-10");
        oldCharge.setPaidAmount(800);
        Member graduate = member(0, 0, List.of(oldCharge), List.of(new PaymentRecord("2025-02-01", 800, "Dues", "g1")));
        List<Member> current = List.of(partlyPaid());
        List<Member> archived = List.of(graduate);

        // In March 2025 the graduate is on the roster: $800 collected across 1 member.
        assertEquals(800, valueOn(MetricHistoryCalculator.AVERAGE_DUES_PER_MEMBER, current, archived, LocalDate.of(2025, 3, 1)));
        // A year later only the current member counts, so the graduate no longer lifts the average.
        assertEquals(300, valueOn(MetricHistoryCalculator.AVERAGE_DUES_PER_MEMBER, current, archived, LocalDate.of(2026, 3, 5)));
    }

    @Test
    void aMemberWithoutChargesKeepsTheirSavedBalanceAndPastDue() {
        // Entered by hand: $400 owed, $150 past due, and a $100 payment on Mar 1 recorded since.
        Member legacy = member(400, 150, List.of(), List.of(new PaymentRecord("2026-03-01", 100, "Dues", "l1")));
        List<Member> members = List.of(legacy);
        // Before that payment the same member owed $500, $250 of it past due: 250 / (0 + 500) = 50%.
        assertEquals(50, valueOn(MetricHistoryCalculator.DELINQUENCY_RATE, members, List.of(), LocalDate.of(2026, 2, 20)));
        assertEquals(30, valueOn(MetricHistoryCalculator.DELINQUENCY_RATE, members, List.of(), TODAY)); // 150 / (100 + 400)
    }

    @Test
    void longRangesAreSampledAndAlwaysEndOnTheLastDay() {
        List<LocalDate> days = MetricHistoryCalculator.sampleDays(LocalDate.of(2024, 8, 1), LocalDate.of(2026, 10, 25));
        assertTrue(days.size() <= MetricHistoryCalculator.MAX_POINTS + 1, "sampled " + days.size());
        assertEquals(LocalDate.of(2026, 10, 25), days.get(days.size() - 1));
        // A 90-day window stays daily.
        assertEquals(91, MetricHistoryCalculator.sampleDays(TODAY.minusDays(90), TODAY).size());
    }

    @Test
    void noDatedLedgerEntriesOrAnUnknownStatGivesNoPoints() {
        Member undated = member(100, 0, List.of(), List.of());
        assertTrue(MetricHistoryCalculator.history(MetricHistoryCalculator.NET_COLLECTION_RATE, List.of(undated), List.of(),
                TODAY.minusDays(30), TODAY, TODAY).isEmpty());
        assertTrue(MetricHistoryCalculator.history("not-a-stat", List.of(partlyPaid()), List.of(),
                TODAY.minusDays(30), TODAY, TODAY).isEmpty());
        // Dates after today are never invented.
        assertTrue(MetricHistoryCalculator.history(MetricHistoryCalculator.NET_COLLECTION_RATE, List.of(partlyPaid()), List.of(),
                TODAY.plusDays(1), TODAY.plusDays(30), TODAY).isEmpty());
    }
}
