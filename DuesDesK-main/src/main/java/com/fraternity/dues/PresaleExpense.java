package com.fraternity.dues;

/** A cost of putting on a pre-sale event (venue, DJ, shirts), set against its revenue. */
public class PresaleExpense {
    private String id;
    private String date;
    private String description;
    private double amount;

    public PresaleExpense() {}

    public PresaleExpense(String id, String date, String description, double amount) {
        this.id = id;
        this.date = date;
        this.description = description;
        this.amount = Money.cents(amount);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Money.cents(amount); }
}
