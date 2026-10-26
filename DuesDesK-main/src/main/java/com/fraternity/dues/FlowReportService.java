package com.fraternity.dues;

import org.apache.pdfbox.pdmodel.font.PDFont;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Inflow/Outflow timeline: every dues payment plus every ledger entry that hasn't been reconciled into a payment,
 * filtered to a time period, with its CSV and PDF exports. Shared by the page, its download endpoints and the
 * desktop bridge so the table, the totals and both exports always agree.
 */
public class FlowReportService {

    public record FlowRow(String date, String type, String description, String party, String source, double amount) {}

    public record Totals(double inflow, double outflow, double net, int count) {}

    /** Time periods offered on the page, in display order. */
    public static final Map<String, String> PRESETS;
    static {
        Map<String, String> presets = new LinkedHashMap<>();
        presets.put("all", "All time");
        presets.put("this-month", "This month");
        presets.put("last-month", "Last month");
        presets.put("last-30", "Last 30 days");
        presets.put("last-90", "Last 90 days");
        presets.put("ytd", "Year to date");
        presets.put("last-year", "Last year");
        presets.put("custom", "Custom range");
        PRESETS = java.util.Collections.unmodifiableMap(presets);
    }

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    /** A resolved time period. {@code from} and {@code to} are inclusive; null means open-ended on that side. */
    public record Period(String key, LocalDate from, LocalDate to) {
        public String getLabel() { return PRESETS.getOrDefault(key, "All time"); }
        public boolean isBounded() { return from != null || to != null; }
        public String getFromText() { return from == null ? "" : from.toString(); }
        public String getToText() { return to == null ? "" : to.toString(); }

        /** "Oct 1, 2026 to Oct 31, 2026", "from Oct 1, 2026", "through Oct 31, 2026" or "all dates". */
        public String getRange() {
            if (from != null && to != null) return from.format(DISPLAY_DATE) + " to " + to.format(DISPLAY_DATE);
            if (from != null) return "from " + from.format(DISPLAY_DATE);
            if (to != null) return "through " + to.format(DISPLAY_DATE);
            return "all dates";
        }

        /** "All time" or e.g. "This month (Oct 1, 2026 to Oct 31, 2026)". */
        public String getDescription() { return isBounded() ? getLabel() + " (" + getRange() + ")" : getLabel(); }

        boolean contains(LocalDate start, LocalDate end) {
            return (to == null || !start.isAfter(to)) && (from == null || !end.isBefore(from));
        }
    }

    private final Store store;

    public FlowReportService(Store store) {
        this.store = store;
    }

    /**
     * Turns the page's period parameters into dates. Unknown keys mean all time; a custom range ignores unreadable
     * dates, swaps reversed ones, and falls back to all time when neither date is given.
     */
    public static Period resolvePeriod(String key, String from, String to, LocalDate today) {
        String normalized = key == null || !PRESETS.containsKey(key) ? "all" : key;
        return switch (normalized) {
            case "this-month" -> new Period(normalized, today.withDayOfMonth(1), YearMonth.from(today).atEndOfMonth());
            case "last-month" -> {
                YearMonth last = YearMonth.from(today).minusMonths(1);
                yield new Period(normalized, last.atDay(1), last.atEndOfMonth());
            }
            case "last-30" -> new Period(normalized, today.minusDays(29), today);
            case "last-90" -> new Period(normalized, today.minusDays(89), today);
            case "ytd" -> new Period(normalized, today.withDayOfYear(1), today);
            case "last-year" -> {
                int year = today.getYear() - 1;
                yield new Period(normalized, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
            }
            case "custom" -> {
                LocalDate start = parse(from);
                LocalDate end = parse(to);
                if (start == null && end == null) yield new Period("all", null, null);
                if (start != null && end != null && start.isAfter(end)) yield new Period(normalized, end, start);
                yield new Period(normalized, start, end);
            }
            default -> new Period("all", null, null);
        };
    }

    /** Every dues payment and unreconciled ledger entry, newest first. */
    public List<FlowRow> allRows() {
        List<FlowRow> rows = new ArrayList<>();
        // Archived members (moved off the roster by a semester rollover) keep their payments in the timeline.
        List<Member> members = new ArrayList<>(store.getMembers());
        members.addAll(store.getArchivedMembers());
        for (Member member : members) {
            if (member.getPaymentHistory() == null) continue;
            for (PaymentRecord payment : member.getPaymentHistory()) {
                rows.add(new FlowRow(payment.getDate() == null ? "" : payment.getDate(), "Dues payment",
                        payment.getDescription() == null || payment.getDescription().isBlank() ? "Dues payment" : payment.getDescription(),
                        member.getName(), "Dues", payment.getAmount()));
            }
        }
        for (FlowEntry entry : store.getFlowEntries()) {
            // Reconciled entries already appear above as the member payment they became.
            if ("RECONCILED".equals(entry.getReconciliationStatus())) continue;
            rows.add(new FlowRow(entry.getDate() == null ? "" : entry.getDate(),
                    entry.getCategory() != null && !entry.getCategory().isBlank() ? entry.getCategory()
                            : (entry.getAmount() >= 0 ? "Inflow" : "Purchase"),
                    entry.getDescription(), entry.getCounterparty(), entry.getSource(), entry.getAmount()));
        }
        rows.sort((a, b) -> b.date().compareTo(a.date()));
        return rows;
    }

    /**
     * The rows that fall inside the period. A month-only date (from older imports) counts when its month overlaps the
     * period; rows without a readable date only appear under all time.
     */
    public List<FlowRow> filter(List<FlowRow> rows, Period period) {
        if (!period.isBounded()) return rows;
        return rows.stream().filter(row -> {
            LocalDate[] span = span(row.date());
            return span != null && period.contains(span[0], span[1]);
        }).toList();
    }

    public List<FlowRow> rows(Period period) {
        return filter(allRows(), period);
    }

    /**
     * Narrows rows the way the page's quick filters do: {@code kind} is "in", "out" or "dues" (anything else keeps
     * all), and {@code query} must appear in the date, type, description, name or source (case-insensitive).
     */
    public static List<FlowRow> refine(List<FlowRow> rows, String kind, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return rows.stream().filter(row -> switch (kind == null ? "" : kind) {
            case "in" -> row.amount() > 0;
            case "out" -> row.amount() < 0;
            case "dues" -> "Dues payment".equals(row.type());
            default -> true;
        }).filter(row -> needle.isEmpty() || String.join(" ", dateText(row.date()), nullToEmpty(row.type()),
                nullToEmpty(row.description()), nullToEmpty(row.party()), nullToEmpty(row.source()))
                .toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    /** "Money out", "Money out, matching \"pizza\"" or "" for no refinement; shown in the PDF heading (ASCII only). */
    public static String refinementLabel(String kind, String query) {
        String kindLabel = switch (kind == null ? "" : kind) {
            case "in" -> "Money in";
            case "out" -> "Money out";
            case "dues" -> "Dues payments";
            default -> "";
        };
        String search = query == null || query.isBlank() ? "" : "matching \"" + query.trim() + "\"";
        if (kindLabel.isEmpty()) return search;
        return search.isEmpty() ? kindLabel : kindLabel + ", " + search;
    }

    /** Plain-text period summary for the clipboard: totals plus money in and out grouped by type. */
    public static String summary(Period period, List<FlowRow> rows) {
        Totals totals = totals(rows);
        long inCount = rows.stream().filter(row -> row.amount() > 0).count();
        long outCount = rows.stream().filter(row -> row.amount() < 0).count();
        StringBuilder text = new StringBuilder("DuesDesk cash flow: ").append(period.getDescription()).append('\n')
                .append("Money in: ").append(money(totals.inflow())).append(" (").append(inCount).append(inCount == 1 ? " transaction)" : " transactions)").append('\n')
                .append("Money out: ").append(money(totals.outflow())).append(" (").append(outCount).append(outCount == 1 ? " transaction)" : " transactions)").append('\n')
                .append("Net: ").append(totals.net() < 0 ? "-" : "+").append(money(Math.abs(totals.net()))).append('\n');
        appendByType(text, "Money in by type", rows.stream().filter(row -> row.amount() > 0).toList());
        appendByType(text, "Money out by type", rows.stream().filter(row -> row.amount() < 0).toList());
        return text.toString().stripTrailing();
    }

    private static void appendByType(StringBuilder text, String heading, List<FlowRow> rows) {
        if (rows.isEmpty()) return;
        Map<String, Double> byType = new LinkedHashMap<>();
        rows.forEach(row -> byType.merge(nullToEmpty(row.type()).isBlank() ? "Other" : row.type(), Math.abs(row.amount()), Double::sum));
        text.append('\n').append(heading).append(":\n");
        byType.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> text.append("- ").append(entry.getKey()).append(": ").append(money(Money.cents(entry.getValue()))).append('\n'));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static Totals totals(List<FlowRow> rows) {
        double inflow = Money.cents(rows.stream().mapToDouble(FlowRow::amount).filter(a -> a > 0).sum());
        double outflow = Money.cents(-rows.stream().mapToDouble(FlowRow::amount).filter(a -> a < 0).sum());
        return new Totals(inflow, outflow, Money.cents(inflow - outflow), rows.size());
    }

    /** e.g. DuesDesk-inflow-outflow-2026-10-01-to-2026-10-31.csv or DuesDesk-inflow-outflow-all-time-2026-10-24.pdf */
    public static String fileName(Period period, String extension, LocalDate today) {
        String range = !period.isBounded() ? "all-time-" + today
                : (period.from() == null ? "start" : period.from().toString()) + "-to-"
                    + (period.to() == null ? "present" : period.to().toString());
        return "DuesDesk-inflow-outflow-" + range + "." + extension;
    }

    /** Spreadsheet export: one header row and one row per transaction, amounts as plain signed numbers. */
    public byte[] csv(Period period) {
        return csv(period, null, null);
    }

    /** As {@link #csv(Period)}, narrowed by the page's quick filters (see {@link #refine}). */
    public byte[] csv(Period period, String kind, String query) {
        // The byte-order mark lets Excel open non-ASCII names correctly.
        StringBuilder out = new StringBuilder("﻿");
        out.append("Date,Type,Description,Name,Source,Amount\r\n");
        for (FlowRow row : refine(rows(period), kind, query)) {
            out.append(csv(dateText(row.date()))).append(',')
                    .append(csv(row.type())).append(',')
                    .append(csv(row.description())).append(',')
                    .append(csv(row.party())).append(',')
                    .append(csv(row.source())).append(',')
                    .append(String.format(Locale.US, "%.2f", row.amount())).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final String[] COLUMN_HEADERS = {"Date", "Type", "Description", "Name", "Source", "Amount"};
    private static final float[] COLUMN_SHARE = {0.11f, 0.15f, 0.31f, 0.20f, 0.10f, 0.13f};

    /** Printable export: executive letterhead, KPI scorecard, then the receipt-rule table across as many landscape pages as needed. */
    public byte[] pdf(Period period) throws IOException {
        return pdf(period, null, null);
    }

    /** As {@link #pdf(Period)}, narrowed by the page's quick filters (see {@link #refine}). */
    public byte[] pdf(Period period, String kind, String query) throws IOException {
        List<FlowRow> rows = refine(rows(period), kind, query);
        Totals totals = totals(rows);
        String refinement = refinementLabel(kind, query);
        String heading = refinement.isEmpty() ? period.getDescription() : period.getDescription() + " - " + refinement;
        long inCount = rows.stream().filter(row -> row.amount() > 0).count();
        long outCount = rows.stream().filter(row -> row.amount() < 0).count();

        ExecutivePdf pdf = new ExecutivePdf(ExecutivePdf.chapterOf(store), "Cash Flow", true);
        pdf.letterhead("Cash Flow Report  |  " + heading, "Cash Flow Report", List.of(
                new String[] {"Period", period.isBounded() ? period.getRange() : "All time"},
                new String[] {"Generated", LocalDate.now().format(DISPLAY_DATE)},
                new String[] {"Transactions", String.valueOf(totals.count())}
        ), totals.net() < 0 ? "Net outflow" : "Net inflow", totals.net() < 0 ? ExecutivePdf.Tone.DANGER : ExecutivePdf.Tone.SUCCESS);
        pdf.kpiBand(List.of(
                new ExecutivePdf.Kpi("Total inflow", money(totals.inflow()), ExecutivePdf.GREEN),
                new ExecutivePdf.Kpi("Total outflow", money(totals.outflow()), ExecutivePdf.RED),
                new ExecutivePdf.Kpi("Net", (totals.net() < 0 ? "-" : "") + money(Math.abs(totals.net())), totals.net() < 0 ? ExecutivePdf.RED : ExecutivePdf.GREEN),
                new ExecutivePdf.Kpi("Transactions", totals.count() + "  (" + inCount + " in / " + outCount + " out)")
        ));

        float width = pdf.width();
        List<ExecutivePdf.Column> columns = new ArrayList<>();
        for (int c = 0; c < COLUMN_HEADERS.length; c++) {
            columns.add(new ExecutivePdf.Column(COLUMN_HEADERS[c], width * COLUMN_SHARE[c], c == COLUMN_HEADERS.length - 1));
        }
        List<List<ExecutivePdf.Cell>> body = new ArrayList<>();
        for (FlowRow row : rows) {
            boolean in = row.amount() >= 0;
            body.add(List.of(
                    ExecutivePdf.Cell.muted(dateText(row.date())),
                    ExecutivePdf.Cell.pill(nullToEmpty(row.type()).isBlank() ? "Other" : row.type(), in ? ExecutivePdf.Tone.SUCCESS : ExecutivePdf.Tone.DANGER),
                    ExecutivePdf.Cell.bold(row.description()),
                    ExecutivePdf.Cell.of(row.party()),
                    ExecutivePdf.Cell.muted(row.source()),
                    ExecutivePdf.Cell.colored((row.amount() < 0 ? "-" : "+") + money(Math.abs(row.amount())), row.amount() < 0 ? ExecutivePdf.RED : ExecutivePdf.GREEN)));
        }
        pdf.table(columns, body, "No transactions in this period.");
        if (!rows.isEmpty()) {
            pdf.space(4);
            pdf.totals("Notes", List.of(
                    "Inflows are dues payments and other money received; outflows are bills, expenses and reimbursements recorded in the ledger.",
                    "Bank and Venmo imports that have not been reconciled into a member payment appear under their own source."
            ), List.of(), List.of(
                    new ExecutivePdf.SummaryLine("Total inflow", money(totals.inflow())),
                    new ExecutivePdf.SummaryLine("Total outflow", "-" + money(totals.outflow())),
                    new ExecutivePdf.SummaryLine("Net", (totals.net() < 0 ? "-" : "") + money(Math.abs(totals.net())), true)
            ));
        }
        return pdf.finish();
    }

    static float width(String value, PDFont font, float size) throws IOException {
        return ExecutivePdf.width(value, font, size);
    }

    /** Shortens text with "..." so it fits its column. */
    static String fit(String value, PDFont font, float size, float maxWidth) throws IOException {
        return ExecutivePdf.fit(value, font, size, maxWidth);
    }

    private static LocalDate parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The first and last day a row's date covers, or null when it has no readable date. */
    private static LocalDate[] span(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        LocalDate day = value.length() >= 10 ? parse(value.substring(0, 10)) : null;
        if (day != null) return new LocalDate[] {day, day};
        try {
            YearMonth month = YearMonth.parse(value.length() >= 7 ? value.substring(0, 7) : value);
            return new LocalDate[] {month.atDay(1), month.atEndOfMonth()};
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String dateText(String raw) {
        if (raw == null) return "";
        return raw.length() >= 10 ? raw.substring(0, 10) : raw;
    }

    private static String money(double value) { return String.format(Locale.US, "$%,.2f", value); }

    static String safe(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    /** Quoted CSV cell; a leading = + - @ is prefixed so spreadsheets don't run it as a formula. */
    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
