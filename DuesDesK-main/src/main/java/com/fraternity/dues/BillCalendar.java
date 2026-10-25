package com.fraternity.dues;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the Calendar page shows about recurring bills: reminders, the list of bills, and bills falling due each day. */
public final class BillCalendar {
    private BillCalendar() {}

    /** One line of the bills list: a bill, its next due date and how that stands today. */
    public record BillRow(RecurringBill bill, LocalDate nextDue, String status, String tone, String lastPaid) {}

    /** Reminders to show today, most urgent (soonest or most overdue) first. */
    public static List<RecurringBill.Reminder> reminders(List<RecurringBill> bills, LocalDate today) {
        return bills.stream().map(bill -> bill.reminder(today)).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingLong(RecurringBill.Reminder::daysUntil)).toList();
    }

    public static List<BillRow> rows(List<RecurringBill> bills, LocalDate today) {
        List<BillRow> rows = new ArrayList<>();
        for (RecurringBill bill : bills) {
            LocalDate next = bill.nextPending();
            String status;
            String tone = "muted";
            if (next == null) {
                status = "No more due dates";
            } else {
                long days = ChronoUnit.DAYS.between(today, next);
                if (days < 0) { status = days == -1 ? "1 day overdue" : -days + " days overdue"; tone = "danger"; }
                else if (days == 0) { status = bill.isAutomatic() ? "Charged today" : "Due today"; tone = "warning"; }
                else if (days == 1) { status = bill.isAutomatic() ? "Charged tomorrow" : "Due tomorrow"; tone = "warning"; }
                else { status = (bill.isAutomatic() ? "Charged in " : "Due in ") + days + " days"; tone = days <= 7 ? "info" : "muted"; }
            }
            String lastPaid = bill.getSettlements().stream().filter(item -> !item.isSkipped() && item.getPaidDate() != null)
                    .map(RecurringBill.Settlement::getPaidDate).max(String::compareTo).orElse("");
            rows.add(new BillRow(bill, next, status, tone, lastPaid));
        }
        rows.sort(Comparator.comparing(BillRow::nextDue, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> row.bill().getName(), String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    /**
     * Bills still waiting to be paid or skipped that fall due in the month, keyed by day: overdue manual bills show on
     * the day they were due, and future automatic bills show as upcoming charges.
     */
    public static Map<String, List<Map<String, Object>>> dueByDay(List<RecurringBill> bills, YearMonth month, LocalDate today) {
        Map<String, List<Map<String, Object>>> byDay = new LinkedHashMap<>();
        for (RecurringBill bill : bills) {
            for (LocalDate due : bill.pendingBetween(month.atDay(1), month.atEndOfMonth())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", bill.getId());
                item.put("name", bill.getName());
                item.put("amount", bill.getAmount());
                item.put("mode", bill.getPaymentMode());
                item.put("overdue", due.isBefore(today));
                byDay.computeIfAbsent(due.toString(), key -> new ArrayList<>()).add(item);
            }
        }
        return byDay;
    }

    /** The fields the edit form needs, so a bill can be changed in place. */
    public static List<Map<String, Object>> editData(List<RecurringBill> bills) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (RecurringBill bill : bills) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", bill.getId());
            item.put("name", bill.getName());
            item.put("amount", bill.getAmount());
            item.put("frequency", bill.getFrequency());
            item.put("startDate", bill.getStartDate());
            item.put("endDate", bill.getEndDate());
            item.put("category", bill.getCategory());
            item.put("payee", bill.getPayee());
            item.put("paymentMode", bill.getPaymentMode());
            item.put("reminderDays", bill.getReminderDays());
            data.add(item);
        }
        return data;
    }
}
