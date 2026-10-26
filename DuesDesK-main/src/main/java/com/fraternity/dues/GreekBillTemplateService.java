package com.fraternity.dues;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Template for Greekbill charges": for a chosen period, every member's dues balance at the start and at the end, then
 * each charge applied in that period with every member it was applied to, so the same charges can be entered in
 * GreekBill. Only the dues roster is covered; rent charges and the rent roster are left out.
 */
public class GreekBillTemplateService {
    public static final String TITLE = "Template for Greekbill charges";
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    public static final Map<String, String> PERIODS;

    static {
        Map<String, String> periods = new LinkedHashMap<>();
        periods.put("today", "Today");
        periods.put("last-7", "Last 7 days");
        periods.put("last-14", "Last 14 days");
        periods.put("last-30", "Last 30 days");
        periods.put("this-month", "This month");
        periods.put("last-month", "Last month");
        periods.put("last-90", "Last 90 days");
        periods.put("this-term", "This semester (since the last rollover)");
        periods.put("ytd", "Year to date");
        periods.put("all", "All time");
        periods.put("custom", "Custom dates");
        PERIODS = Collections.unmodifiableMap(periods);
    }

    /** The chosen time period; {@code from} is null for all time. Both ends are included. */
    public record Period(String key, LocalDate from, LocalDate to) {
        public String getLabel() { return PERIODS.getOrDefault(key, "Last 30 days"); }
        public String getFromText() { return from == null ? "" : from.toString(); }
        public String getToText() { return to == null ? "" : to.toString(); }

        public String getRange() {
            if (from == null) return "through " + to.format(DISPLAY_DATE);
            if (from.equals(to)) return from.format(DISPLAY_DATE);
            return from.format(DISPLAY_DATE) + " to " + to.format(DISPLAY_DATE);
        }

        boolean contains(LocalDate date) {
            return date != null && (from == null || !date.isBefore(from)) && !date.isAfter(to);
        }

        boolean contains(LocalDateTime time) {
            return time != null && contains(time.toLocalDate());
        }
    }

    public record MemberRow(String memberId, String name, double before, double after, int chargesApplied) {
        public double getChange() { return Money.cents(after - before); }
    }

    public record ChargeLine(String memberId, String memberName, String date, String dueDate, double amount) {}

    public record ChargeGroup(String description, double amount, List<ChargeLine> lines) {
        public double getTotal() { return Money.cents(lines.stream().mapToDouble(ChargeLine::amount).sum()); }
        public int getCount() { return lines.size(); }
    }

    public record Report(Period period, List<MemberRow> members, List<ChargeGroup> applied, List<ChargeGroup> removed,
                         List<ChargeGroup> credits, boolean endsToday) {
        public int getChargeCount() { return applied.stream().mapToInt(ChargeGroup::getCount).sum(); }
        public double getTotalCharged() { return Money.cents(applied.stream().mapToDouble(ChargeGroup::getTotal).sum()); }
        public double getTotalRemoved() { return Money.cents(removed.stream().mapToDouble(ChargeGroup::getTotal).sum()); }
        public double getTotalCredits() { return Money.cents(credits.stream().mapToDouble(ChargeGroup::getTotal).sum()); }
        public long getMembersCharged() {
            return applied.stream().flatMap(group -> group.lines().stream()).map(ChargeLine::memberId).distinct().count();
        }
        public long getMembersChanged() { return members.stream().filter(row -> Math.abs(row.getChange()) >= Money.HALF_CENT).count(); }
        public double getNetChange() { return Money.cents(members.stream().mapToDouble(MemberRow::getChange).sum()); }
    }

    private final Store store;

    public GreekBillTemplateService(Store store) {
        this.store = store;
    }

    /** Turns the dropdown choice into dates. Unknown keys mean the last 30 days; custom dates swap if reversed. */
    public static Period resolvePeriod(String key, String from, String to, LocalDate today, String lastRolloverDate) {
        String normalized = key == null || !PERIODS.containsKey(key) ? "last-30" : key;
        return switch (normalized) {
            case "today" -> new Period(normalized, today, today);
            case "last-7" -> new Period(normalized, today.minusDays(6), today);
            case "last-14" -> new Period(normalized, today.minusDays(13), today);
            case "this-month" -> new Period(normalized, today.withDayOfMonth(1), today);
            case "last-month" -> {
                YearMonth last = YearMonth.from(today).minusMonths(1);
                yield new Period(normalized, last.atDay(1), last.atEndOfMonth());
            }
            case "last-90" -> new Period(normalized, today.minusDays(89), today);
            case "this-term" -> {
                LocalDate start = parse(lastRolloverDate);
                yield start == null || start.isAfter(today) ? new Period("all", null, today) : new Period(normalized, start, today);
            }
            case "ytd" -> new Period(normalized, today.withDayOfYear(1), today);
            case "all" -> new Period(normalized, null, today);
            case "custom" -> {
                LocalDate start = parse(from);
                LocalDate end = parse(to);
                if (end == null || end.isAfter(today)) end = today;
                if (start != null && start.isAfter(end)) {
                    LocalDate swap = start;
                    start = end;
                    end = swap;
                }
                yield new Period(normalized, start, end);
            }
            default -> new Period("last-30", today.minusDays(29), today);
        };
    }

    public Report build(Period period, LocalDate today) {
        List<Member> roster = new ArrayList<>(store.getMembers());
        roster.sort(Comparator.comparing(member -> member.getName() == null ? "" : member.getName(), String.CASE_INSENSITIVE_ORDER));
        List<ActivityEntry> log = store.getActivityLog();
        LocalDateTime logStart = store.getActivityLogStartedAt();
        Map<String, List<ActivityEntry>> byMember = new HashMap<>();
        for (ActivityEntry entry : log) {
            if (entry.getMemberId() != null && "members".equals(entry.getSection())) {
                byMember.computeIfAbsent(entry.getMemberId(), key -> new ArrayList<>()).add(entry);
            }
        }
        byMember.values().forEach(list -> list.sort(Comparator.comparing(ActivityEntry::getTime)));
        boolean endsToday = !period.to().isBefore(today);
        LocalDateTime startTime = period.from() == null ? null : period.from().atStartOfDay();
        LocalDateTime endTime = period.to().plusDays(1).atStartOfDay();

        List<MemberRow> rows = new ArrayList<>();
        Map<String, ChargeGroup> applied = new LinkedHashMap<>();
        Map<String, ChargeGroup> credits = new LinkedHashMap<>();
        for (Member member : roster) {
            List<ActivityEntry> entries = byMember.getOrDefault(member.getId(), List.of());
            double before = startTime == null ? 0 : balanceAt(member, startTime, entries, logStart);
            double after = endsToday ? member.getTotalOwed() : balanceAt(member, endTime, entries, logStart);
            int count = 0;
            for (ChargeRecord charge : member.getCharges() == null ? List.<ChargeRecord>of() : member.getCharges()) {
                if (!period.contains(parse(charge.getDate()))) continue;
                count++;
                group(applied, charge.getDescription(), charge.getAmount())
                        .lines().add(new ChargeLine(member.getId(), member.getName(), charge.getDate(), charge.getDueDate(), charge.getAmount()));
            }
            for (MemberCredit credit : member.getCredits()) {
                if (!period.contains(parse(credit.getDate()))) continue;
                group(credits, credit.getDescription(), credit.getAmount())
                        .lines().add(new ChargeLine(member.getId(), member.getName(), credit.getDate(), null, credit.getAmount()));
            }
            rows.add(new MemberRow(member.getId(), member.getName(), Money.cents(before), Money.cents(after), count));
        }

        Map<String, ChargeGroup> removed = new LinkedHashMap<>();
        for (ActivityEntry entry : log) {
            if (!entry.isRemoval() || entry.isUndone() || !"members".equals(entry.getSection())
                    || !"charges".equals(entry.getCollection()) || !period.contains(entry.getTime())) continue;
            double amount = entry.getAmount() == null ? 0 : entry.getAmount();
            String dueDate = null;
            try {
                dueDate = new com.fasterxml.jackson.databind.ObjectMapper().readTree(entry.getItemJson()).path("dueDate").asText(null);
            } catch (Exception ignored) {
                // The due date is optional on the template.
            }
            group(removed, entry.getEntityLabel(), amount).lines().add(new ChargeLine(entry.getMemberId(), entry.getMemberName(),
                    entry.getAt() == null ? "" : entry.getAt().substring(0, Math.min(10, entry.getAt().length())), dueDate, amount));
        }
        return new Report(period, rows, sorted(applied), sorted(removed), sorted(credits), endsToday);
    }

    /**
     * A member's dues balance at {@code time}. While the activity log covers that time, the balance is read straight
     * from the log (the balance before the first change at or after it). Before the log began, charges, payments and
     * credits dated in between are taken back off the balance the log first saw.
     */
    static double balanceAt(Member member, LocalDateTime time, List<ActivityEntry> entries, LocalDateTime logStart) {
        LocalDateTime anchor = logStart != null && time.isBefore(logStart) ? logStart : time;
        double balance = member.getTotalOwed();
        for (ActivityEntry entry : entries) {
            if (entry.getBalanceBefore() != null && !entry.getTime().isBefore(anchor)) {
                balance = entry.getBalanceBefore();
                break;
            }
        }
        if (anchor.equals(time)) return balance;
        // Before the log began: walk back through the ledger between the requested time and the start of the log.
        Set<String> logged = new HashSet<>();
        for (ActivityEntry entry : entries) if (entry.getItemKey() != null) logged.add(entry.getItemKey());
        LocalDate from = time.toLocalDate();
        LocalDate until = anchor.toLocalDate();
        for (ChargeRecord charge : member.getCharges() == null ? List.<ChargeRecord>of() : member.getCharges()) {
            if (between(parse(charge.getDate()), from, until) && !logged.contains(charge.getId())) balance -= charge.getAmount();
        }
        for (PaymentRecord payment : member.getPaymentHistory() == null ? List.<PaymentRecord>of() : member.getPaymentHistory()) {
            if (between(parse(payment.getDate()), from, until) && !logged.contains(payment.getIdempotencyKey())) balance += payment.getAmount();
        }
        for (MemberCredit credit : member.getCredits()) {
            if (between(parse(credit.getDate()), from, until) && !logged.contains(credit.getId())) balance += credit.getAmount();
        }
        return Math.max(0, balance);
    }

    private static boolean between(LocalDate date, LocalDate from, LocalDate until) {
        return date != null && !date.isBefore(from) && !date.isAfter(until);
    }

    private static ChargeGroup group(Map<String, ChargeGroup> groups, String description, double amount) {
        String name = description == null || description.isBlank() ? "Charge" : description.trim();
        String key = name.toLowerCase(Locale.ROOT) + "|" + Money.cents(amount);
        return groups.computeIfAbsent(key, k -> new ChargeGroup(name, Money.cents(amount), new ArrayList<>()));
    }

    private static List<ChargeGroup> sorted(Map<String, ChargeGroup> groups) {
        List<ChargeGroup> list = new ArrayList<>(groups.values());
        list.forEach(group -> group.lines().sort(Comparator.comparing((ChargeLine line) -> line.memberName() == null ? "" : line.memberName(),
                String.CASE_INSENSITIVE_ORDER)));
        list.sort(Comparator.comparing((ChargeGroup group) -> group.lines().isEmpty() ? "" : nz(group.lines().getFirst().date()))
                .thenComparing(ChargeGroup::description, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    // ------------------------------------------------------------------ PDF

    public byte[] pdf(Report report) throws IOException {
        Period period = report.period();
        ExecutivePdf pdf = new ExecutivePdf(ExecutivePdf.chapterOf(store), "GreekBill charge template", false);
        pdf.letterhead(TITLE + "  |  " + period.getLabel(), "GreekBill Charge Template", List.of(
                new String[] {"Period", period.getRange()},
                new String[] {"Generated", LocalDate.now().format(DISPLAY_DATE)},
                new String[] {"Members", String.valueOf(report.members().size())}
        ), report.getChargeCount() == 0 ? "No new charges" : report.getChargeCount() + (report.getChargeCount() == 1 ? " charge" : " charges"),
                report.getChargeCount() == 0 ? ExecutivePdf.Tone.NEUTRAL : ExecutivePdf.Tone.INDIGO);
        pdf.kpiBand(List.of(
                new ExecutivePdf.Kpi("Charges applied", String.valueOf(report.getChargeCount())),
                new ExecutivePdf.Kpi("Total charged", money(report.getTotalCharged()), ExecutivePdf.INDIGO),
                new ExecutivePdf.Kpi("Members charged", String.valueOf(report.getMembersCharged())),
                new ExecutivePdf.Kpi("Net balance change", signed(report.getNetChange()),
                        report.getNetChange() < 0 ? ExecutivePdf.GREEN : ExecutivePdf.INK)
        ));
        pdf.paragraph("Enter each charge below in GreekBill for the members listed under it. Dues accounts only: rent charges "
                + "and the rent roster are not included.", 9, ExecutivePdf.MUTED);
        pdf.space(6);

        float width = pdf.width();
        String beforeLabel = period.from() == null ? "Starting balance" : "Balance on " + period.from().format(DateTimeFormatter.ofPattern("MMM d", Locale.US));
        String afterLabel = report.endsToday() ? "Current balance" : "Balance on " + period.to().format(DateTimeFormatter.ofPattern("MMM d", Locale.US));
        pdf.section("Member balances");
        List<ExecutivePdf.Column> balanceColumns = List.of(
                new ExecutivePdf.Column("#", width * 0.06f, false),
                new ExecutivePdf.Column("Member", width * 0.34f, false),
                new ExecutivePdf.Column(beforeLabel, width * 0.2f, true),
                new ExecutivePdf.Column(afterLabel, width * 0.2f, true),
                new ExecutivePdf.Column("Change", width * 0.2f, true));
        List<List<ExecutivePdf.Cell>> balanceRows = new ArrayList<>();
        int number = 1;
        for (MemberRow row : report.members()) {
            double change = row.getChange();
            boolean moved = Math.abs(change) >= Money.HALF_CENT;
            balanceRows.add(List.of(
                    ExecutivePdf.Cell.muted(String.valueOf(number++)),
                    ExecutivePdf.Cell.bold(row.name()),
                    ExecutivePdf.Cell.of(money(row.before())),
                    ExecutivePdf.Cell.bold(money(row.after())),
                    moved ? ExecutivePdf.Cell.colored(signed(change), change < 0 ? ExecutivePdf.GREEN : ExecutivePdf.INDIGO)
                            : ExecutivePdf.Cell.muted("No change")));
        }
        pdf.table(balanceColumns, balanceRows, "There are no members on the roster.");

        pdf.section("Charges applied");
        if (report.applied().isEmpty()) {
            pdf.note("No dues charges were applied in this period.", ExecutivePdf.MUTED);
        }
        for (ChargeGroup group : report.applied()) chargeGroup(pdf, group, "Applied");

        if (!report.removed().isEmpty()) {
            pdf.section("Charges removed");
            pdf.paragraph("These charges were taken off members' accounts in this period. Remove them in GreekBill too.", 9, ExecutivePdf.MUTED);
            for (ChargeGroup group : report.removed()) chargeGroup(pdf, group, "Removed");
        }
        if (!report.credits().isEmpty()) {
            pdf.section("Credits and discounts");
            for (ChargeGroup group : report.credits()) chargeGroup(pdf, group, "Credited");
        }

        pdf.space(4);
        List<ExecutivePdf.SummaryLine> summary = new ArrayList<>();
        summary.add(new ExecutivePdf.SummaryLine("Charges applied", money(report.getTotalCharged())));
        if (!report.removed().isEmpty()) summary.add(new ExecutivePdf.SummaryLine("Charges removed", "-" + money(report.getTotalRemoved())));
        if (!report.credits().isEmpty()) summary.add(new ExecutivePdf.SummaryLine("Credits and discounts", "-" + money(report.getTotalCredits())));
        summary.add(new ExecutivePdf.SummaryLine("Net balance change", signed(report.getNetChange()), true));
        pdf.totals("Notes", List.of(
                "Balances are dues balances only. Payments received in the period are part of the balance change.",
                "Balances before the activity log began are worked out from dated charges, payments and credits."
        ), List.of(), summary);
        return pdf.finish();
    }

    private static void chargeGroup(ExecutivePdf pdf, ChargeGroup group, String dateLabel) throws IOException {
        pdf.groupHeading(group.description(), money(group.amount()) + " each  |  " + group.getCount()
                + (group.getCount() == 1 ? " member" : " members") + "  |  " + money(group.getTotal()) + " total");
        float width = pdf.width();
        List<ExecutivePdf.Column> columns = List.of(
                new ExecutivePdf.Column("#", width * 0.06f, false),
                new ExecutivePdf.Column("Member", width * 0.4f, false),
                new ExecutivePdf.Column(dateLabel, width * 0.18f, false),
                new ExecutivePdf.Column("Due date", width * 0.18f, false),
                new ExecutivePdf.Column("Amount", width * 0.18f, true));
        List<List<ExecutivePdf.Cell>> rows = new ArrayList<>();
        int number = 1;
        for (ChargeLine line : group.lines()) {
            rows.add(List.of(
                    ExecutivePdf.Cell.muted(String.valueOf(number++)),
                    ExecutivePdf.Cell.bold(line.memberName()),
                    ExecutivePdf.Cell.of(dateText(line.date())),
                    ExecutivePdf.Cell.muted(line.dueDate() == null || line.dueDate().isBlank() ? "-" : dateText(line.dueDate())),
                    ExecutivePdf.Cell.of(money(line.amount()))));
        }
        pdf.table(columns, rows, null);
    }

    public static String fileName(Period period, LocalDate today) {
        String range = period.from() == null ? "all-time-to-" + period.to() : period.from() + "-to-" + period.to();
        return "DuesDesk-GreekBill-charges-" + range + ".pdf";
    }

    private static String dateText(String value) {
        LocalDate date = parse(value);
        return date == null ? nz(value) : date.format(DISPLAY_DATE);
    }

    static LocalDate parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim().length() >= 10 ? value.trim().substring(0, 10) : value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    static String money(double value) {
        return ExecutivePdf.money(value);
    }

    static String signed(double value) {
        if (Math.abs(value) < Money.HALF_CENT) return money(0);
        return (value > 0 ? "+" : "-") + money(Math.abs(value));
    }
}
