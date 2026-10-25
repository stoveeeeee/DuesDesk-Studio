package com.fraternity.dues;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A bill the chapter pays on a schedule (rent, insurance, a software subscription). It is either {@link #AUTOMATIC}
 * (the bank or card pays it, and DuesDesk records the payment when the due date arrives) or {@link #MANUAL} (someone
 * must pay it, and DuesDesk reminds them until it is marked paid). Reminders are customizable lead times in days.
 */
public class RecurringBill {

    public static final String AUTOMATIC = "AUTOMATIC";
    public static final String MANUAL = "MANUAL";

    /** Repeat schedules offered, with the label shown on the page. */
    public static final Map<String, String> FREQUENCIES = Map.of(
            "WEEKLY", "Every week", "BIWEEKLY", "Every 2 weeks", "MONTHLY", "Every month",
            "QUARTERLY", "Every 3 months", "YEARLY", "Every year");
    /** Display order for {@link #FREQUENCIES}. */
    public static final List<String> FREQUENCY_ORDER = List.of("WEEKLY", "BIWEEKLY", "MONTHLY", "QUARTERLY", "YEARLY");

    public static final int MAX_REMINDERS = 8;
    public static final int MAX_LEAD_DAYS = 365;

    /** One due date that has been dealt with: paid (by hand or automatically) or skipped. */
    public static class Settlement {
        private String dueDate;
        /** "PAID" or "SKIPPED". */
        private String status = "PAID";
        private String paidDate;
        private double amount;
        /** True when DuesDesk recorded it itself because the bill is automatic. */
        private boolean automatic;
        private String flowEntryId;

        public Settlement() {}

        public Settlement(String dueDate, String status, String paidDate, double amount, boolean automatic, String flowEntryId) {
            this.dueDate = dueDate;
            this.status = status;
            this.paidDate = paidDate;
            this.amount = Money.cents(amount);
            this.automatic = automatic;
            this.flowEntryId = flowEntryId;
        }

        public String getDueDate() { return dueDate; }
        public void setDueDate(String dueDate) { this.dueDate = dueDate; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getPaidDate() { return paidDate; }
        public void setPaidDate(String paidDate) { this.paidDate = paidDate; }
        public double getAmount() { return amount; }
        public void setAmount(double amount) { this.amount = Money.cents(amount); }
        public boolean isAutomatic() { return automatic; }
        public void setAutomatic(boolean automatic) { this.automatic = automatic; }
        public String getFlowEntryId() { return flowEntryId; }
        public void setFlowEntryId(String flowEntryId) { this.flowEntryId = flowEntryId; }
        @JsonIgnore
        public boolean isSkipped() { return "SKIPPED".equals(status); }
    }

    /** A bill that needs attention now: due within its reminder window, or a manual bill that is overdue. */
    public record Reminder(RecurringBill bill, LocalDate dueDate, long daysUntil, int triggeredLeadDays) {
        public boolean isOverdue() { return daysUntil < 0; }
        /** The reminder that fired, e.g. "1 week before". */
        public String getLeadLabel() { return leadLabel(triggeredLeadDays).toLowerCase(java.util.Locale.ROOT); }
        /** "danger" once overdue or due within a day, "warning" within 3 days, otherwise "info". */
        public String getTone() { return daysUntil <= 1 ? "danger" : daysUntil <= 3 ? "warning" : "info"; }
        public String getWhen() {
            if (daysUntil < -1) return -daysUntil + " days overdue";
            if (daysUntil == -1) return "1 day overdue";
            if (daysUntil == 0) return "Due today";
            if (daysUntil == 1) return "Due tomorrow";
            return "Due in " + daysUntil + " days";
        }
    }

    private String id;
    private String name;
    private double amount;
    /** One of {@link #FREQUENCIES}' keys. */
    private String frequency = "MONTHLY";
    /** The first due date; later ones follow from the frequency. */
    private String startDate;
    /** Optional last day the bill can fall due; blank means it repeats until deleted. */
    private String endDate = "";
    /** An outflow category from {@link TransactionCategories#OUTFLOW}. */
    private String category;
    private String payee = "";
    private String paymentMode = MANUAL;
    /** Days before the due date to start reminding (0 = on the due date), most advance notice first. */
    private List<Integer> reminderDays = new ArrayList<>();
    /** Due dates before this day are not tracked, so a start date in the past doesn't flood the page with overdue bills. */
    private String createdDate;
    private List<Settlement> settlements = new ArrayList<>();

    public RecurringBill() {}

    public RecurringBill(String id, String name, double amount, String frequency, String startDate, String endDate,
                         String category, String payee, String paymentMode, List<Integer> reminderDays, String createdDate) {
        this.id = id;
        this.name = name;
        this.amount = Money.cents(amount);
        this.frequency = frequency;
        this.startDate = startDate;
        this.endDate = endDate == null ? "" : endDate;
        this.category = category;
        this.payee = payee == null ? "" : payee;
        this.paymentMode = paymentMode;
        this.reminderDays = reminderDays == null ? new ArrayList<>() : new ArrayList<>(reminderDays);
        this.createdDate = createdDate;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Money.cents(amount); }
    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate == null ? "" : endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPayee() { return payee == null ? "" : payee; }
    public void setPayee(String payee) { this.payee = payee; }
    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String paymentMode) { this.paymentMode = paymentMode; }
    public List<Integer> getReminderDays() {
        if (reminderDays == null) reminderDays = new ArrayList<>();
        return reminderDays;
    }
    public void setReminderDays(List<Integer> reminderDays) { this.reminderDays = reminderDays; }
    public String getCreatedDate() { return createdDate; }
    public void setCreatedDate(String createdDate) { this.createdDate = createdDate; }
    public List<Settlement> getSettlements() {
        if (settlements == null) settlements = new ArrayList<>();
        return settlements;
    }
    public void setSettlements(List<Settlement> settlements) { this.settlements = settlements; }

    @JsonIgnore
    public boolean isAutomatic() { return AUTOMATIC.equals(paymentMode); }
    @JsonIgnore
    public String getFrequencyLabel() { return FREQUENCIES.getOrDefault(frequency, frequency); }

    /** The due date number {@code n} (0 = the first); a monthly bill on the 31st lands on each month's last day. */
    public LocalDate dueDate(int n) {
        LocalDate start = LocalDate.parse(startDate);
        return switch (frequency) {
            case "WEEKLY" -> start.plusWeeks(n);
            case "BIWEEKLY" -> start.plusWeeks(2L * n);
            case "QUARTERLY" -> start.plusMonths(3L * n);
            case "YEARLY" -> start.plusYears(n);
            default -> start.plusMonths(n);
        };
    }

    private static final int SCAN_LIMIT = 6000;

    /** Every due date from {@code from} to {@code to} inclusive (and not past the end date), settled or not. */
    public List<LocalDate> dueDatesBetween(LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate end = getEndDate().isBlank() ? null : LocalDate.parse(getEndDate());
        for (int n = 0; n < SCAN_LIMIT; n++) {
            LocalDate due = dueDate(n);
            if (due.isAfter(to) || (end != null && due.isAfter(end))) break;
            if (!due.isBefore(from)) dates.add(due);
        }
        return dates;
    }

    public Settlement settlementFor(LocalDate due) {
        return getSettlements().stream().filter(item -> due.toString().equals(item.getDueDate())).findFirst().orElse(null);
    }

    /** Whether this due date is one DuesDesk keeps track of: not before the bill was created. */
    public boolean isTracked(LocalDate due) {
        return createdDate == null || !due.isBefore(LocalDate.parse(createdDate));
    }

    /** Tracked due dates in the range that have not been paid or skipped yet. */
    public List<LocalDate> pendingBetween(LocalDate from, LocalDate to) {
        return dueDatesBetween(from, to).stream().filter(due -> isTracked(due) && settlementFor(due) == null).toList();
    }

    /** The earliest tracked due date still waiting to be paid or skipped (possibly already overdue); null when none is left. */
    public LocalDate nextPending() {
        LocalDate end = getEndDate().isBlank() ? null : LocalDate.parse(getEndDate());
        for (int n = 0; n < SCAN_LIMIT; n++) {
            LocalDate due = dueDate(n);
            if (end != null && due.isAfter(end)) return null;
            if (isTracked(due) && settlementFor(due) == null) return due;
        }
        return null;
    }

    /**
     * The reminder to show today, or null. A bill is reminded once its next due date is within its largest reminder
     * lead time; a manual bill that is overdue is always shown, since a missed payment is more than a reminder.
     */
    public Reminder reminder(LocalDate today) {
        LocalDate due = nextPending();
        if (due == null) return null;
        long daysUntil = ChronoUnit.DAYS.between(today, due);
        List<Integer> leads = getReminderDays();
        boolean overdueManual = daysUntil < 0 && !isAutomatic();
        boolean inWindow = !leads.isEmpty() && daysUntil >= 0 && daysUntil <= leads.stream().max(Integer::compare).orElse(0);
        if (!overdueManual && !inWindow) return null;
        // The most specific reminder reached: the smallest lead time that is still at least the days remaining.
        int triggered = leads.stream().filter(lead -> lead >= daysUntil).min(Comparator.naturalOrder()).orElse(0);
        return new Reminder(this, due, daysUntil, triggered);
    }

    /** "On the due date", "1 day before", "7 days before", ... */
    public static String leadLabel(int days) {
        return days == 0 ? "On the due date" : days == 1 ? "1 day before" : days == 7 ? "1 week before"
                : days == 14 ? "2 weeks before" : days + " days before";
    }
}
