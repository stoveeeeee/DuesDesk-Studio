package com.fraternity.dues;

public class MemberCredit {
    private String id;
    private String date;
    private String description;
    private double amount;
    private String reimbursementId;

    public MemberCredit() {}

    public MemberCredit(String id, String date, String description, double amount, String reimbursementId) {
        this.id = id;
        this.date = date;
        this.description = description;
        this.amount = Money.cents(amount);
        this.reimbursementId = reimbursementId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Money.cents(amount); }
    public String getReimbursementId() { return reimbursementId; }
    public void setReimbursementId(String reimbursementId) { this.reimbursementId = reimbursementId; }
}
