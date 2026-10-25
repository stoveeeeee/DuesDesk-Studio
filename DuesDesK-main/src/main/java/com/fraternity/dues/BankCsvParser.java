package com.fraternity.dues;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BankCsvParser {
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US),
            DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.US),
            DateTimeFormatter.ofPattern("M-d-uuuu", Locale.US)
    );

    private BankCsvParser() {}

    public static List<FlowEntry> parse(String text) {
        if (text != null && text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> records = GreekBillCsvParser.readRecords(text == null ? "" : text);
        if (records.size() < 2 || records.size() > 10_001) {
            throw new IllegalArgumentException("Bank CSV must contain a header and no more than 10,000 rows.");
        }
        Map<String, Integer> columns = new java.util.HashMap<>();
        List<String> header = records.getFirst();
        for (int i = 0; i < header.size(); i++) {
            columns.putIfAbsent(header.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        Integer dateCol = first(columns, "date", "transaction date", "posted date");
        Integer amountCol = first(columns, "amount", "transaction amount");
        Integer debitCol = first(columns, "debit", "withdrawal");
        Integer creditCol = first(columns, "credit", "deposit");
        Integer descriptionCol = first(columns, "description", "memo", "details", "transaction description");
        Integer partyCol = first(columns, "name", "counterparty", "payee", "merchant");
        if (dateCol == null || (amountCol == null && debitCol == null && creditCol == null)) {
            throw new IllegalArgumentException(
                    "Bank CSV needs a Date column and an Amount column, or separate Debit and Credit columns.");
        }

        List<FlowEntry> entries = new ArrayList<>();
        // How many times each identical transaction has appeared so far in this file. The duplicate-check ID uses
        // this count instead of the row number, so re-importing an overlapping statement (rows shifted by newer
        // activity) recognizes transactions already imported, while two genuinely identical rows stay separate.
        Map<String, Integer> occurrences = new java.util.HashMap<>();
        for (List<String> row : records.subList(1, records.size())) {
            String date = cell(row, dateCol);
            if (date.isBlank()) continue;
            LocalDate parsedDate = parseDate(date);
            double amount;
            if (amountCol != null) {
                amount = signedAmount(cell(row, amountCol));
            } else {
                double credit = parseAmount(cell(row, creditCol));
                double debit = parseAmount(cell(row, debitCol));
                amount = credit - debit;
            }
            if (!Double.isFinite(amount) || amount == 0) continue;
            String description = cell(row, descriptionCol);
            String counterparty = cell(row, partyCol);
            FlowEntry entry = new FlowEntry(null, parsedDate.toString(), description, counterparty, amount, "Bank");
            String identity = contentKey(entry);
            int occurrence = occurrences.merge(identity, 1, Integer::sum);
            entry.setId("bank-" + hash(identity + "|" + occurrence));
            entries.add(entry);
        }
        return entries;
    }

    private static Integer first(Map<String, Integer> columns, String... names) {
        for (String name : names) {
            Integer index = columns.get(name);
            if (index != null) return index;
        }
        return null;
    }

    private static String cell(List<String> row, Integer index) {
        return index == null || index >= row.size() ? "" : row.get(index).trim();
    }

    private static LocalDate parseDate(String value) {
        String normalized = value.trim();
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next common statement date format.
            }
        }
        throw new IllegalArgumentException("Bank CSV contains an unsupported transaction date: " + normalized);
    }

    /** The same transaction's identity whatever its ID: date, amount, description and counterparty. */
    static String contentKey(FlowEntry entry) {
        return entry.getDate() + "|" + Money.cents(entry.getAmount()) + "|"
                + normalizeText(entry.getDescription()) + "|" + normalizeText(entry.getCounterparty());
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** "-25.00", "-$25.00", "$-25.00", "USD -25.00", "(25.00)" and "25.00-" are all outflows. */
    private static double signedAmount(String value) {
        String normalized = value.trim();
        int firstDigit = 0;
        while (firstDigit < normalized.length() && !Character.isDigit(normalized.charAt(firstDigit))) firstDigit++;
        String beforeDigits = normalized.substring(0, firstDigit);
        boolean negative = beforeDigits.contains("-") || beforeDigits.contains("(") || normalized.endsWith("-");
        double amount = parseAmount(normalized);
        return negative ? -amount : amount;
    }

    private static double parseAmount(String value) {
        if (value == null || value.isBlank()) return 0;
        String numeric = value.trim().replaceAll("[^0-9.]", "");
        if (numeric.isBlank()) return 0;
        try {
            double amount = Double.parseDouble(numeric);
            if (!Double.isFinite(amount)) throw new NumberFormatException("Non-finite amount");
            return amount;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bank CSV contains an invalid transaction amount.");
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }
}
