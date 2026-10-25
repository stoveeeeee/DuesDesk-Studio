package com.fraternity.dues;

import java.util.*;

/**
 * Reads a Venmo account statement export. The file starts with a few title lines before the real header row
 * (",ID,Datetime,Type,Status,Note,From,To,Amount (total),..."), so the header is located by name.
 * Transfers to a bank and anything not completed are skipped.
 */
public final class VenmoCsvParser {

    public static final int MAX_ROWS = 10_000;
    private static final String SOURCE = "Venmo";

    public record Result(List<FlowEntry> entries, int skipped) {}

    private VenmoCsvParser() {}

    public static Result parse(String text) {
        if (text != null && text.startsWith("﻿")) text = text.substring(1);
        List<List<String>> records = GreekBillCsvParser.readRecords(text == null ? "" : text);
        if (records.size() > MAX_ROWS + 10) {
            throw new IllegalArgumentException("CSV exceeds the 10,000-row import limit.");
        }

        int headerIndex = -1;
        Map<String, Integer> cols = new HashMap<>();
        for (int i = 0; i < records.size() && headerIndex < 0; i++) {
            Map<String, Integer> candidate = new HashMap<>();
            List<String> row = records.get(i);
            for (int c = 0; c < row.size(); c++) candidate.putIfAbsent(row.get(c).trim().toLowerCase(Locale.ROOT), c);
            if (candidate.containsKey("datetime") && candidate.containsKey("amount (total)")) {
                headerIndex = i;
                cols = candidate;
            }
        }
        if (headerIndex < 0) {
            throw new IllegalArgumentException(
                    "This doesn't look like a Venmo statement. Download it from Venmo (Statements > Download CSV) and try again.");
        }

        Integer idCol = cols.get("id"), dateCol = cols.get("datetime"), typeCol = cols.get("type"),
                statusCol = cols.get("status"), noteCol = cols.get("note"), fromCol = cols.get("from"),
                toCol = cols.get("to"), amountCol = cols.get("amount (total)");

        List<FlowEntry> entries = new ArrayList<>();
        int skipped = 0;
        for (List<String> row : records.subList(headerIndex + 1, records.size())) {
            String id = cell(row, idCol);
            String amountText = cell(row, amountCol);
            if (id.isBlank() || amountText.isBlank()) continue; // balance / footer rows
            String type = cell(row, typeCol).toLowerCase(Locale.ROOT);
            String status = cell(row, statusCol).toLowerCase(Locale.ROOT);
            if (!status.equals("complete") || type.contains("transfer")) { skipped++; continue; }

            Double amount = signedAmount(amountText);
            String date = cell(row, dateCol);
            if (amount == null || amount == 0 || date.length() < 10) { skipped++; continue; }

            String counterparty = cell(row, amount > 0 ? fromCol : toCol);
            entries.add(new FlowEntry("venmo-" + id, date, cell(row, noteCol), counterparty, amount, SOURCE));
        }
        return new Result(entries, skipped);
    }

    /** "+ $1,200.00" -> 1200.0, "- $12.50" -> -12.5; null when unparseable. */
    static Double signedAmount(String text) {
        String t = text.trim();
        boolean negative = t.startsWith("-") || (t.startsWith("(") && t.endsWith(")"));
        try {
            double d = Double.parseDouble(t.replaceAll("[^0-9.]", ""));
            if (!Double.isFinite(d)) return null;
            return negative ? -d : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cell(List<String> row, Integer idx) {
        return idx == null || idx >= row.size() ? "" : row.get(idx).trim();
    }
}
