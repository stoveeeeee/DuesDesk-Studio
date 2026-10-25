package com.fraternity.dues;

public class Resident {
    private String id;
    private String name;
    private String phoneNumber;
    private String room;
    private double monthlyRent;
    private double balanceDue;
    private double paidThisMonth;
    private String lastTextedDate = "Never";
    private boolean muteReminders;
    // Itemized extras (utilities, parking, fines...). Each one is already included in balanceDue.
    private java.util.List<ChargeRecord> charges = new java.util.ArrayList<>();
    /**
     * The roster member this resident is, for social clearance. null = not decided yet (DuesDesk links by matching
     * name); "" = explicitly not a member; otherwise a member id.
     */
    private String linkedMemberId;
    /** Optional, for "Open in Email" reminders. */
    private String email = "";

    public Resident() {}

    public Resident(String id, String name, String phoneNumber, String room,
                    double monthlyRent, double balanceDue, double paidThisMonth) {
        this.id = id;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.room = room;
        this.monthlyRent = Money.cents(monthlyRent);
        this.balanceDue = Money.cents(balanceDue);
        this.paidThisMonth = Money.cents(paidThisMonth);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getStatus() { return Money.positive(balanceDue) ? "Balance Due" : "Paid Up"; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLinkedMemberId() { return linkedMemberId; }
    public void setLinkedMemberId(String linkedMemberId) { this.linkedMemberId = linkedMemberId; }
    public String getEmail() { return email == null ? "" : email; }
    public void setEmail(String email) { this.email = email; }
    /** True once someone decided: linked to a member, or marked as not a member. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isLinkDecided() { return linkedMemberId != null; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getRoom() { return room; }
    public void setRoom(String room) { this.room = room; }
    public double getMonthlyRent() { return monthlyRent; }
    public void setMonthlyRent(double monthlyRent) { this.monthlyRent = Money.cents(monthlyRent); }
    public double getBalanceDue() { return balanceDue; }
    public void setBalanceDue(double balanceDue) { this.balanceDue = Money.cents(balanceDue); }
    public double getPaidThisMonth() { return paidThisMonth; }
    public void setPaidThisMonth(double paidThisMonth) { this.paidThisMonth = Money.cents(paidThisMonth); }
    public String getLastTextedDate() { return lastTextedDate; }
    public void setLastTextedDate(String lastTextedDate) { this.lastTextedDate = lastTextedDate; }
    public boolean isMuteReminders() { return muteReminders; }
    public void setMuteReminders(boolean muteReminders) { this.muteReminders = muteReminders; }
    public java.util.List<ChargeRecord> getCharges() {
        if (charges == null) charges = new java.util.ArrayList<>();
        return charges;
    }
    public void setCharges(java.util.List<ChargeRecord> charges) { this.charges = charges; }
}
