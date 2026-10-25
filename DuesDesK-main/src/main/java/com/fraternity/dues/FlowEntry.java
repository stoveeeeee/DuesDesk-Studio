package com.fraternity.dues;

/**
 * A single money movement, imported from an external statement (e.g. Venmo) or recorded by hand on the calendar
 * (status MANUAL, with a {@link TransactionCategories} category). Amount is signed: positive = inflow.
 */
public class FlowEntry {
    private String id;
    private String date;
    private String description;
    private String counterparty;
    private double amount;
    private String source;
    private String reconciliationStatus = "PENDING";
    private String reconciledMemberId;
    private String reconciledPaymentKey;
    private String category;

    public FlowEntry() {}

    public FlowEntry(String id, String date, String description, String counterparty, double amount, String source) {
        this.id = id;
        this.date = date;
        this.description = description;
        this.counterparty = counterparty;
        this.amount = Money.cents(amount);
        this.source = source;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCounterparty() { return counterparty; }
    public void setCounterparty(String counterparty) { this.counterparty = counterparty; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Money.cents(amount); }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getReconciliationStatus() { return reconciliationStatus == null ? "PENDING" : reconciliationStatus; }
    public void setReconciliationStatus(String reconciliationStatus) { this.reconciliationStatus = reconciliationStatus; }
    public String getReconciledMemberId() { return reconciledMemberId; }
    public void setReconciledMemberId(String reconciledMemberId) { this.reconciledMemberId = reconciledMemberId; }
    public String getReconciledPaymentKey() { return reconciledPaymentKey; }
    public void setReconciledPaymentKey(String reconciledPaymentKey) { this.reconciledPaymentKey = reconciledPaymentKey; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
