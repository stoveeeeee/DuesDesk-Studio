package com.fraternity.dues;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public class PaymentRecord {
    private String date;
    private double amount;
    private String description;
    private String idempotencyKey;
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    public PaymentRecord() {}

    public PaymentRecord(String date, double amount, String description) {
        this(date, amount, description, null);
    }

    public PaymentRecord(String date, double amount, String description, String idempotencyKey) {
        this.date = date;
        this.amount = Money.cents(amount);
        this.description = description;
        this.idempotencyKey = idempotencyKey;
    }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Money.cents(amount); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String monthLabel() {
        if (date == null || date.isBlank()) return "Unspecified month";

        String normalizedDate = date.trim();
        try {
            String localDate = normalizedDate.length() >= 10
                    ? normalizedDate.substring(0, 10)
                    : normalizedDate;
            return LocalDate.parse(localDate).format(MONTH_FORMATTER);
        } catch (DateTimeParseException ignored) {
            try {
                return YearMonth.parse(normalizedDate).format(MONTH_FORMATTER);
            } catch (DateTimeParseException ignoredMonth) {
                return "Unspecified month";
            }
        }
    }
}
