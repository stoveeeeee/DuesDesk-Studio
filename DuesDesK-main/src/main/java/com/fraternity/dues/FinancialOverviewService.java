package com.fraternity.dues;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Cash and near-term obligation calculations shown above the Financial Overview spending calculator. */
public class FinancialOverviewService {
    public record FinancialSnapshot(
            double overallCash,
            double upcomingBillFees,
            double recurringBillFees,
            double scheduledFees,
            double ihqRemaining,
            double spendableCash,
            double scheduledDuesNextWeek,
            LocalDate asOf,
            LocalDate scheduledDuesThrough,
            long pendingTransactions) {}

    private final Store store;

    public FinancialOverviewService(Store store) {
        this.store = store;
    }

    public FinancialSnapshot snapshot(LocalDate today) {
        AppSettings storedSettings = store.getSettings();
        AppSettings settings = storedSettings == null ? new AppSettings() : storedSettings;
        List<Member> activeMembers = store.getMembers();
        List<Member> allMembers = new ArrayList<>(activeMembers);
        allMembers.addAll(store.getArchivedMembers());

        double recordedDues = allMembers.stream()
                .filter(member -> member.getPaymentHistory() != null)
                .flatMap(member -> member.getPaymentHistory().stream())
                .mapToDouble(PaymentRecord::getAmount).sum();
        double otherLedgerActivity = store.getFlowEntries().stream()
                // Reconciled deposits are already represented by their member payment; ignored entries are excluded.
                .filter(entry -> !"RECONCILED".equals(entry.getReconciliationStatus())
                        && !"IGNORED".equals(entry.getReconciliationStatus()))
                .mapToDouble(FlowEntry::getAmount).sum();
        double overallCash = Money.cents(settings.getOpeningCashBalance() + recordedDues + otherLedgerActivity);

        double upcomingBillFees = upcomingBillFees(settings.getUpcomingBills());
        double recurringBillFees = store.getRecurringBills().stream()
                .filter(bill -> bill.nextPending() != null)
                .mapToDouble(RecurringBill::getAmount).sum();
        recurringBillFees = Money.cents(recurringBillFees);
        double scheduledFees = Money.cents(upcomingBillFees + recurringBillFees);
        double ihqRemaining = SpendingAdvisor.ihqProgress(activeMembers,
                settings.getIhqBillAmount(), settings.getIhqStartDate()).remaining();
        double spendableCash = Money.cents(overallCash - scheduledFees - ihqRemaining);
        double scheduledDues = scheduledDuesBetween(activeMembers, today, today.plusDays(7));
        long pendingTransactions = store.getFlowEntries().stream()
                .filter(entry -> "PENDING".equals(entry.getReconciliationStatus()) && entry.getAmount() > 0).count();

        return new FinancialSnapshot(overallCash, upcomingBillFees, recurringBillFees, scheduledFees,
                ihqRemaining, spendableCash, scheduledDues, today, today.plusDays(7), pendingTransactions);
    }

    private static double upcomingBillFees(String rows) {
        double total = 0;
        for (String row : rows.lines().map(String::trim).filter(line -> !line.isBlank()).toList()) {
            String[] fields = row.split("\\|", -1);
            if (fields.length != 3 || fields[0].trim().isEmpty()) {
                throw new IllegalStateException("An upcoming bill is invalid. Use Description | YYYY-MM-DD | Amount.");
            }
            try {
                LocalDate.parse(fields[1].trim());
                double amount = Double.parseDouble(fields[2].trim());
                if (!Double.isFinite(amount) || amount < 0) throw new NumberFormatException();
                total += amount;
            } catch (RuntimeException e) {
                throw new IllegalStateException("An upcoming bill has an invalid date or amount.", e);
            }
        }
        return Money.cents(total);
    }

    private static double scheduledDuesBetween(List<Member> members, LocalDate from, LocalDate through) {
        double total = 0;
        for (Member member : members) {
            if (member.getInstallments() == null) continue;
            for (PaymentInstallment installment : member.getInstallments()) {
                LocalDate due = parseDate(installment.getDueDate());
                if (due != null && !due.isBefore(from) && !due.isAfter(through)) {
                    total += installment.getRemainingAmount();
                }
            }
        }
        return Money.cents(total);
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
