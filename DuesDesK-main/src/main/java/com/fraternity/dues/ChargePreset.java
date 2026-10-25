package com.fraternity.dues;

/** A named fine or discount the treasurer saved to reuse, e.g. "Missed chapter - $25 fine". */
public class ChargePreset {
    public static final String FINE = "FINE";
    public static final String DISCOUNT = "DISCOUNT";

    private String id;
    private String name = "";
    private double amount;
    private String type = FINE;

    public ChargePreset() {}

    public ChargePreset(String id, String name, double amount, String type) {
        this.id = id;
        this.name = name;
        this.amount = Money.cents(amount);
        this.type = type;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name == null ? "" : name; }
    public void setName(String name) { this.name = name; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Money.cents(amount); }
    /** FINE adds to the balance; DISCOUNT takes it off. Anything unrecognised reads as a fine. */
    public String getType() { return DISCOUNT.equals(type) ? DISCOUNT : FINE; }
    public void setType(String type) { this.type = type; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isDiscount() { return DISCOUNT.equals(getType()); }
}
