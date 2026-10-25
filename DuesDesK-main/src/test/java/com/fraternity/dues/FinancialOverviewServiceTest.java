package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinancialOverviewServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    private static FlowEntry flow(String id, double amount, String status) {
        FlowEntry entry = new FlowEntry(id, "2026-10-20", "Activity", "Counterparty", amount, "Test");
        entry.setReconciliationStatus(status);
        return entry;
    }

    @Test
    void snapshotCombinesCashObligationsAndNextWeekInstallmentsWithoutDoubleCounting() {
        Member active = new Member("active", "Active Member", "555", 0, 0, 0, "Payment Plan", "Never", false);
        active.setPaymentHistory(List.of(new PaymentRecord("2026-10-01", 300, "Dues")));
        PaymentInstallment dueToday = new PaymentInstallment("today", TODAY.toString(), "Today", 100);
        dueToday.setPaidAmount(25);
        PaymentInstallment paid = new PaymentInstallment("paid", TODAY.plusDays(2).toString(), "Paid", 30);
        paid.setPaidAmount(30);
        active.setInstallments(List.of(
                dueToday,
                new PaymentInstallment("week", TODAY.plusDays(7).toString(), "This week", 50),
                new PaymentInstallment("later", TODAY.plusDays(8).toString(), "Later", 60),
                new PaymentInstallment("overdue", TODAY.minusDays(1).toString(), "Overdue", 70),
                new PaymentInstallment("invalid", "bad-date", "Invalid", 40),
                paid));
        Member archived = new Member("archived", "Archived Member", "555", 0, 0, 0, "Paid in Full", "Never", false);
        archived.setPaymentHistory(List.of(new PaymentRecord("2026-09-01", 100, "Dues")));
        archived.setInstallments(List.of(new PaymentInstallment("archived-plan", TODAY.plusDays(3).toString(), "Old", 500)));

        AppSettings settings = new AppSettings();
        settings.setOpeningCashBalance(1000);
        settings.setUpcomingBills("Insurance | 2026-11-01 | 100\nCleaning | 2026-10-20 | 50");
        settings.setIhqBillAmount(500);
        settings.setIhqStartDate("2026-01-01");
        RecurringBill recurring = new RecurringBill("bill", "Venue", 200, "MONTHLY", "2026-11-01", "",
                "Venue & rentals", "", "MANUAL", List.of(), "2026-10-01");
        Store store = mock(Store.class);
        when(store.getMembers()).thenReturn(List.of(active));
        when(store.getArchivedMembers()).thenReturn(List.of(archived));
        when(store.getSettings()).thenReturn(settings);
        when(store.getRecurringBills()).thenReturn(List.of(recurring));
        when(store.getFlowEntries()).thenReturn(List.of(
                flow("in", 50, "MANUAL"), flow("out", -25, "MANUAL"),
                flow("reconciled", 75, "RECONCILED"), flow("ignored", 999, "IGNORED"),
                flow("pending", 10, "PENDING")));

        FinancialOverviewService.FinancialSnapshot snapshot = new FinancialOverviewService(store).snapshot(TODAY);

        assertEquals(1435, snapshot.overallCash(), 0.001);
        assertEquals(150, snapshot.upcomingBillFees(), 0.001);
        assertEquals(200, snapshot.recurringBillFees(), 0.001);
        assertEquals(350, snapshot.scheduledFees(), 0.001);
        assertEquals(200, snapshot.ihqRemaining(), 0.001);
        assertEquals(885, snapshot.spendableCash(), 0.001);
        assertEquals(125, snapshot.scheduledDuesNextWeek(), 0.001);
        assertEquals(TODAY, snapshot.asOf());
        assertEquals(TODAY.plusDays(7), snapshot.scheduledDuesThrough());
        assertEquals(1, snapshot.pendingTransactions());
    }

    @Test
    void invalidUpcomingBillsFailInsteadOfShowingAMisleadingBalance() {
        AppSettings settings = new AppSettings();
        settings.setUpcomingBills("not a valid bill");
        Store store = mock(Store.class);
        when(store.getMembers()).thenReturn(List.of());
        when(store.getArchivedMembers()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of());
        when(store.getRecurringBills()).thenReturn(List.of());
        when(store.getSettings()).thenReturn(settings);

        assertThrows(IllegalStateException.class, () -> new FinancialOverviewService(store).snapshot(TODAY));
    }
}
