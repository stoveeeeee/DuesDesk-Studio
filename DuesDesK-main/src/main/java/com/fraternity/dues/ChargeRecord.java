package com.fraternity.dues;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class ChargeRecord {
    private String id;
    private String date;
    private String description;
    private double amount;
    private double pastDueAmount;
    private String dueDate;
    private double paidAmount;
    private String lateFeeForChargeId;

    public ChargeRecord() {}

    public ChargeRecord(String id, String date, String description, double amount, double pastDueAmount) {
        this.id = id;
        this.date = date;
        this.description = description;
        this.amount = Money.cents(amount);
        this.pastDueAmount = Money.cents(pastDueAmount);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Money.cents(amount); }
    public double getPastDueAmount() { return pastDueAmount; }
    public void setPastDueAmount(double pastDueAmount) { this.pastDueAmount = Money.cents(pastDueAmount); }
    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    public double getPaidAmount() { return paidAmount; }
    public void setPaidAmount(double paidAmount) { this.paidAmount = Money.cents(paidAmount); }
    public String getLateFeeForChargeId() { return lateFeeForChargeId; }
    public void setLateFeeForChargeId(String lateFeeForChargeId) { this.lateFeeForChargeId = lateFeeForChargeId; }
    @JsonIgnore
    public double getRemainingAmount() { return Money.cents(Math.max(0, amount - paidAmount)); }
}
