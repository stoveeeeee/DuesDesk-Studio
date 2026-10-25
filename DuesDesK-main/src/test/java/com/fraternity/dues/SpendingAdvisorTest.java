package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpendingAdvisorTest {

    private Member memberWithPayments(PaymentRecord... payments) {
        Member member = new Member("m", "Alex", "555", 0, 0, 0, "Paid in Full", "Never", false);
        member.setPaymentHistory(List.of(payments));
        return member;
    }

    @Test
    void duesPaymentsComeOffIhqBillAndCapAtTheBill() {
        Member member = memberWithPayments(new PaymentRecord("2026-09-01", 300, "a"),
                new PaymentRecord("2026-10-01", 200, "b"));
        SpendingAdvisor.IhqProgress half = SpendingAdvisor.ihqProgress(List.of(member), 1000, "");
        assertEquals(50.0, half.percent(), 0.001);
        assertEquals(500.0, half.remaining(), 0.001);
        assertEquals(100.0, SpendingAdvisor.ihqProgress(List.of(member), 400, "").percent(), 0.001);
        assertEquals(0.0, SpendingAdvisor.ihqProgress(List.of(member), 0, "").percent(), 0.001);
    }

    @Test
    void semesterStartDateExcludesEarlierPayments() {
        Member member = memberWithPayments(new PaymentRecord("2026-08-01", 300, "old"),
                new PaymentRecord("2026-10-01", 200, "new"));
        assertEquals(200.0, SpendingAdvisor.ihqProgress(List.of(member), 1000, "2026-09-01").paidOff(), 0.001);
    }

    @Test
    void averageIncomeUsesMonthsWithPayments() {
        Member member = memberWithPayments(new PaymentRecord("2026-09-01", 300, "a"),
                new PaymentRecord("2026-10-01", 100, "b"), new PaymentRecord("2026-10-05", 100, "c"));
        assertEquals(250.0, SpendingAdvisor.averageMonthlyIncome(List.of(member)), 0.001);
    }

    @Test
    void smallExpenseIsApprovedAndLargeOneIsNot() {
        assertTrue(SpendingAdvisor.evaluate(100, 1000, 95, 0, 0).approve());
        assertFalse(SpendingAdvisor.evaluate(600, 1000, 95, 0, 0).approve());
    }

    @Test
    void weakFinancesLowerTheSpendingLimit() {
        assertTrue(SpendingAdvisor.evaluate(200, 1000, 95, 0, 0).approve());
        assertFalse(SpendingAdvisor.evaluate(200, 1000, 60, 800, 5000).approve());
    }

    @Test
    void noIncomeMeansDoNotSpend() {
        assertFalse(SpendingAdvisor.evaluate(50, 0, 100, 0, 0).approve());
    }

    @Test
    void expenseThatCreatesAnOperatingShortfallIncludesARecoveryPlan() {
        SpendingAdvisor.Advice advice = SpendingAdvisor.evaluate(
                300, 1000, 95, 0, 0, 100, "Chapter supplies", "routine");

        assertFalse(advice.approve());
        assertEquals(-200, advice.projectedCash(), 0.001);
        assertEquals(200, advice.recoveryAmount(), 0.001);
        assertTrue(advice.recommendation().contains("Do not purchase now"));
        assertTrue(advice.recoveryActions().stream().anyMatch(action -> action.contains("$200.00")));
        assertTrue(advice.recoveryActions().stream().anyMatch(action -> action.contains("count dues only after payment is received")));
    }

    @Test
    void emergencyShortfallIsFlaggedWithoutSuggestingItIsFinanciallySafe() {
        SpendingAdvisor.Advice advice = SpendingAdvisor.evaluate(
                100, 1000, 95, 0, 0, -250, "Emergency furnace repair", "emergency");

        assertFalse(advice.approve());
        assertEquals(-350, advice.projectedCash(), 0.001);
        assertEquals(350, advice.recoveryAmount(), 0.001);
        assertTrue(advice.recommendation().contains("Do not approve the full purchase"));
        assertEquals("Safety-critical or emergency repair", advice.purposeCategory());
        assertTrue(advice.recoveryActions().stream().anyMatch(action -> action.contains("safety or critical-operations emergency")));
        assertTrue(advice.reasons().stream().anyMatch(reason -> reason.contains("already operating $250.00 below")));
    }

    @Test
    void soonDeadlineRecommendsNegotiatingAnExtension() {
        SpendingAdvisor.Advice advice = SpendingAdvisor.evaluate(
                300, 1000, 95, 0, 0, 100, "Required equipment", "soon");

        assertTrue(advice.verdict().contains("Defer or renegotiate"));
        assertTrue(advice.reasons().stream().anyMatch(reason -> reason.contains("Urgency selected: Needed soon")));
        assertTrue(advice.recoveryActions().stream().anyMatch(action -> action.contains("Confirm the real deadline")));
    }

    @Test
    void expenseCoveredByOperatingCashDoesNotCreateARecoveryGap() {
        SpendingAdvisor.Advice advice = SpendingAdvisor.evaluate(
                100, 1000, 95, 0, 0, 600, "Routine supplies", "routine");

        assertTrue(advice.approve());
        assertEquals(500, advice.projectedCash(), 0.001);
        assertEquals(0, advice.recoveryAmount(), 0.001);
        assertTrue(advice.recoveryActions().isEmpty());
    }

    @Test
    void unclearPurposeRequiresReviewEvenWhenThePurchaseAppearsAffordable() {
        SpendingAdvisor.Advice advice = SpendingAdvisor.evaluate(
                100, 1000, 95, 0, 0, 600, "miscellaneous purchase", "routine");

        assertFalse(advice.approve());
        assertEquals("Needs officer review", advice.purposeCategory());
        assertTrue(advice.recommendation().contains("clarify the purchase purpose"));
        assertFalse(advice.proceedActions().isEmpty());
    }

    @Test
    void discretionaryExpenseCannotBeMarkedAsAnEmergencyWithoutReview() {
        SpendingAdvisor.Advice advice = SpendingAdvisor.evaluate(
                100, 1000, 95, 0, 0, 600, "optional social decorations", "emergency");

        assertFalse(advice.approve());
        assertEquals("Discretionary or member-experience expense", advice.purposeCategory());
        assertTrue(advice.recommendation().contains("verify why this discretionary purchase cannot wait"));
    }

    @Test
    void revenueGeneratingExpenseRequiresAReviewedReturnAndBudgetCap() {
        SpendingAdvisor.Advice advice = SpendingAdvisor.evaluate(
                100, 1000, 95, 0, 0, 600, "fundraiser ticket sales campaign", "soon");

        assertTrue(advice.approve());
        assertEquals("Revenue-generating activity", advice.purposeCategory());
        assertTrue(advice.recommendation().contains("expected net return"));
        assertTrue(advice.proceedActions().stream().anyMatch(action -> action.contains("spending cap")));
    }
}
