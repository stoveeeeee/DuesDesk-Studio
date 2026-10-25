package com.fraternity.dues;

public class ReimbursementRecord {
    private String id;
    private String date;
    private String description;
    private double amount;
    private String receiptFileName;
    private String creditedMemberId;

    public ReimbursementRecord() {}

    public ReimbursementRecord(String id, String date, String description, double amount,
                               String receiptFileName, String creditedMemberId) {
        this.id = id;
        this.date = date;
        this.description = description;
        this.amount = Money.cents(amount);
        this.receiptFileName = receiptFileName;
        this.creditedMemberId = creditedMemberId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Money.cents(amount); }
    public String getReceiptFileName() { return receiptFileName; }
    public void setReceiptFileName(String receiptFileName) { this.receiptFileName = receiptFileName; }
    public String getCreditedMemberId() { return creditedMemberId; }
    public void setCreditedMemberId(String creditedMemberId) { this.creditedMemberId = creditedMemberId; }
}
