package com.fraternity.dues;

import java.util.*;

/**
 * Reads GreekBill roster exports by header name (so column order and extra columns don't matter),
 * and falls back to the original positional layout (Name, Phone, Total Owed, Past Due[, Paid This Month])
 * when the file has no recognizable header.
 */
public final class GreekBillCsvParser {

    public static final int MAX_ROWS = 10_000;

    public record Row(String name, String phone, double totalOwed, Double pastDue, Double paidThisMonth) {}

    private static final Set<String> NAME = Set.of("name", "membername", "fullname", "member");
    private static final Set<String> FIRST = Set.of("firstname", "first", "fname");
    private static final Set<String> LAST = Set.of("lastname", "last", "lname", "surname");
    private static final Set<String> PHONE = Set.of("phone", "phonenumber", "mobile", "mobilephone",
            "mobilenumber", "cell", "cellphone", "cellnumber");
    private static final Set<String> TOTAL = Set.of("totalowed", "totaldue", "totalbalance", "amountowed");
    private static final Set<String> CURRENT = Set.of("currentbalance", "balance", "balancedue");
    private static final Set<String> PAST_DUE = Set.of("pastdue", "pastduebalance", "pastdueamount",
            "overdue", "overduebalance");
    private static final Set<String> PAID = Set.of("paidthismonth");
    private static final Set<String> STATUS = Set.of("memberstatus", "status");
    private static final Set<String> TX_MARKERS = Set.of("amount", "transactiondate", "date", "referencenumber",
            "reference", "description", "chargename", "category");
    private static final Set<String> EXCLUDED_STATUSES = Set.of("inactive", "alumnus", "alumni", "alum",
            "resigned", "withdrawn", "deactivated");

    private GreekBillCsvParser() {}

    public static List<Row> parse(String text) {
        if (text != null && text.startsWith("﻿")) text = text.substring(1);
        List<List<String>> records = readRecords(text == null ? "" : text);
        records.removeIf(GreekBillCsvParser::isBlank);
        if (records.isEmpty()) return List.of();
        if (records.size() > MAX_ROWS + 1) {
            throw new IllegalArgumentException("CSV exceeds the 10,000-row import limit.");
        }

        List<String> first = records.get(0);
        Map<String, Integer> cols = headerColumns(first);
        boolean hasName = cols.keySet().stream().anyMatch(NAME::contains)
                || (cols.keySet().stream().anyMatch(FIRST::contains) && cols.keySet().stream().anyMatch(LAST::contains));
        boolean hasBalance = cols.keySet().stream().anyMatch(k -> TOTAL.contains(k) || CURRENT.contains(k) || PAST_DUE.contains(k));
        boolean hasOther = hasBalance || cols.keySet().stream().anyMatch(PHONE::contains);

        if (hasName && hasOther) {
            return parseWithHeader(records, cols, hasBalance);
        }
        if (hasName && cols.keySet().stream().anyMatch(TX_MARKERS::contains)) {
            throw transactionExportError();
        }
        return parsePositional(records);
    }

    private static List<Row> parseWithHeader(List<List<String>> records, Map<String, Integer> cols, boolean hasBalance) {
        if (!hasBalance) {
            if (cols.keySet().stream().anyMatch(TX_MARKERS::contains)) throw transactionExportError();
            throw new IllegalArgumentException("No balance columns found. Include Total Owed, Past Due Balance or Current Balance.");
        }
        Integer name = find(cols, NAME), firstCol = find(cols, FIRST), lastCol = find(cols, LAST);
        Integer phone = find(cols, PHONE), total = find(cols, TOTAL), current = find(cols, CURRENT);
        Integer pastDue = find(cols, PAST_DUE), paid = find(cols, PAID), status = find(cols, STATUS);
        // Transaction exports also carry an "Amount" column and a per-charge row; those are not balances.
        if (total == null && current == null && cols.keySet().stream().anyMatch(TX_MARKERS::contains)
                && cols.containsKey("amount")) {
            throw transactionExportError();
        }

        List<Row> rows = new ArrayList<>();
        for (List<String> r : records.subList(1, records.size())) {
            String memberName = name != null ? cell(r, name)
                    : (cell(r, firstCol) + " " + cell(r, lastCol)).trim();
            if (memberName.isBlank()) continue;
            if (status != null && EXCLUDED_STATUSES.contains(normalize(cell(r, status)))) continue;
            Integer ownedCol = total != null ? total : current;
            if (ownedCol != null && r.size() <= ownedCol) continue; // truncated/garbage row, don't zero a balance
            double owed = ownedCol != null ? money(cell(r, ownedCol)) : 0.0;
            Double past = pastDue != null ? money(cell(r, pastDue)) : null;
            if (ownedCol == null && past != null) owed = past;
            Double paidMonth = paid != null ? money(cell(r, paid)) : null;
            String phoneValue = phone != null ? cell(r, phone) : null;
            rows.add(new Row(memberName, phoneValue, owed, past, paidMonth));
        }
        return rows;
    }

    private static List<Row> parsePositional(List<List<String>> records) {
        List<Row> rows = new ArrayList<>();
        boolean firstRow = true;
        for (List<String> r : records) {
            String head = r.isEmpty() ? "" : r.get(0).toLowerCase(Locale.ROOT);
            if (firstRow && (head.contains("name") || head.contains("member"))) {
                firstRow = false;
                continue;
            }
            firstRow = false;
            if (r.size() < 4) continue;
            String name = r.get(0).trim();
            if (name.isEmpty()) continue;
            rows.add(new Row(name, r.get(1).trim(), money(r.get(2)), money(r.get(3)),
                    r.size() >= 5 ? money(r.get(4)) : null));
        }
        return rows;
    }

    private static IllegalArgumentException transactionExportError() {
        return new IllegalArgumentException("This looks like a GreekBill transaction/charge export. "
                + "Import the Member Roster export (with Total Owed / Past Due / Current Balance) instead.");
    }

    private static Map<String, Integer> headerColumns(List<String> header) {
        Map<String, Integer> cols = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) cols.putIfAbsent(normalize(header.get(i)), i);
        return cols;
    }

    private static Integer find(Map<String, Integer> cols, Set<String> aliases) {
        for (Map.Entry<String, Integer> e : cols.entrySet()) {
            if (aliases.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String cell(List<String> row, Integer idx) {
        return idx == null || idx >= row.size() ? "" : row.get(idx).trim();
    }

    private static boolean isBlank(List<String> row) {
        return row.stream().allMatch(c -> c.trim().isEmpty());
    }

    /** Parses "$1,234.50", "(150.00)" (negative), "-20"; blanks and junk become 0. Negatives clamp to 0 (credits). */
    static double money(String val) {
        if (val == null) return 0.0;
        String v = val.trim();
        boolean negative = v.startsWith("(") && v.endsWith(")");
        try {
            double d = Double.parseDouble(v.replaceAll("[^0-9.-]", ""));
            if (negative) d = -Math.abs(d);
            return Double.isFinite(d) ? Math.max(0.0, d) : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** RFC 4180-style reader: quoted fields, escaped quotes, embedded delimiters/newlines; auto-detects , ; | or tab. */
    static List<List<String>> readRecords(String text) {
        char delim = detectDelimiter(text);
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == delim) {
                row.add(field.toString()); field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString()); field.setLength(0);
                records.add(row); row = new ArrayList<>();
            } else field.append(c);
        }
        if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); records.add(row); }
        return records;
    }

    private static char detectDelimiter(String text) {
        int end = text.indexOf('\n');
        String line = end < 0 ? text : text.substring(0, end);
        char best = ',';
        int bestCount = 0;
        boolean quoted = false;
        int[] counts = new int[4];
        char[] candidates = {',', ';', '|', '\t'};
        for (char c : line.toCharArray()) {
            if (c == '"') quoted = !quoted;
            else if (!quoted) for (int i = 0; i < candidates.length; i++) if (c == candidates[i]) counts[i]++;
        }
        for (int i = 0; i < candidates.length; i++) {
            if (counts[i] > bestCount) { bestCount = counts[i]; best = candidates[i]; }
        }
        return best;
    }
}
