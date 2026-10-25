package com.fraternity.dues;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class PaymentInstallment {
    private String id;
    private String dueDate;
    private String description;
    private double amount;
    private double paidAmount;

    public PaymentInstallment() {}

    public PaymentInstallment(String id, String dueDate, String description, double amount) {
        this.id = id;
        this.dueDate = dueDate;
        this.description = description;
        this.amount = Money.cents(amount);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Money.cents(amount); }
    public double getPaidAmount() { return paidAmount; }
    public void setPaidAmount(double paidAmount) { this.paidAmount = Money.cents(paidAmount); }
    @JsonIgnore
    public double getRemainingAmount() { return Money.cents(Math.max(0, amount - paidAmount)); }
    @JsonIgnore
    public boolean isPaid() { return getRemainingAmount() < 0.005; }
    @JsonIgnore
    public boolean isOverdue() {
        if (isPaid() || dueDate == null) return false;
        try {
            return java.time.LocalDate.parse(dueDate).isBefore(java.time.LocalDate.now());
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }
}
