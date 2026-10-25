package com.fraternity.dues;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

public class ChairBudget {
    /**
     * An event this chair runs (an Events page event whose chair position is this one). Its sales count as money
     * raised and its expenses as money spent, live, so the chair's account always matches the event.
     */
    public record EventLink(String id, String name, String eventDate, boolean completed, double revenue, double expenses) {}

    private String id;
    private String chairName;
    /** The member who holds this chair position; null when no member is assigned. (Older saves used it for dues credits.) */
    private String chairMemberId;
    /** Filled in by Store from the linked events whenever budgets are read; never saved. */
    @JsonIgnore
    private transient List<EventLink> linkedEvents = List.of();
    private double budgetCap;
    /** Optional money-raised goal for chairs that bring money in as well as spend it; 0 means no goal. */
    private double raiseGoal;
    /** Stable card accent used to tell chair positions apart; assigned by Store when missing. */
    private String accentColor;
    private List<ReimbursementRecord> reimbursements = new ArrayList<>();
    private List<RaisedRecord> raised = new ArrayList<>();

    public ChairBudget() {}

    public ChairBudget(String id, String chairName, double budgetCap) {
        this.id = id;
        this.chairName = chairName;
        this.budgetCap = Money.cents(budgetCap);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getChairName() { return chairName; }
    public void setChairName(String chairName) { this.chairName = chairName; }
    public String getChairMemberId() { return chairMemberId; }
    public void setChairMemberId(String chairMemberId) { this.chairMemberId = chairMemberId; }
    public double getBudgetCap() { return budgetCap; }
    public void setBudgetCap(double budgetCap) { this.budgetCap = Money.cents(budgetCap); }
    public double getRaiseGoal() { return raiseGoal; }
    public void setRaiseGoal(double raiseGoal) { this.raiseGoal = Money.cents(raiseGoal); }
    public String getAccentColor() { return accentColor; }
    public void setAccentColor(String accentColor) { this.accentColor = accentColor; }
    @JsonIgnore
    public String getInitials() {
        if (chairName == null || chairName.isBlank()) return "?";
        String[] words = chairName.trim().split("\\s+");
        String initials = words[0].substring(0, 1);
        if (words.length > 1) initials += words[words.length - 1].substring(0, 1);
        return initials.toUpperCase(java.util.Locale.ROOT);
    }
    @JsonIgnore
    public String getAccentTextColor() {
        if (accentColor == null || !accentColor.matches("#[0-9a-fA-F]{6}")) return "#FFFFFF";
        int red = Integer.parseInt(accentColor.substring(1, 3), 16);
        int green = Integer.parseInt(accentColor.substring(3, 5), 16);
        int blue = Integer.parseInt(accentColor.substring(5, 7), 16);
        int brightness = (red * 299 + green * 587 + blue * 114) / 1000;
        return brightness >= 150 ? "#0F172A" : "#FFFFFF";
    }
    public List<ReimbursementRecord> getReimbursements() {
        if (reimbursements == null) reimbursements = new ArrayList<>();
        return reimbursements;
    }
    public void setReimbursements(List<ReimbursementRecord> reimbursements) { this.reimbursements = reimbursements; }
    public List<RaisedRecord> getRaised() {
        if (raised == null) raised = new ArrayList<>();
        return raised;
    }
    public void setRaised(List<RaisedRecord> raised) { this.raised = raised; }
    @JsonIgnore
    public List<EventLink> getLinkedEvents() { return linkedEvents == null ? List.of() : linkedEvents; }
    public void linkEvents(List<EventLink> events) { this.linkedEvents = events == null ? List.of() : List.copyOf(events); }
    /** Expenses logged on this chair's events. */
    @JsonIgnore
    public double getEventSpent() { return Money.cents(getLinkedEvents().stream().mapToDouble(EventLink::expenses).sum()); }
    /** Sales from this chair's events. */
    @JsonIgnore
    public double getEventRaised() { return Money.cents(getLinkedEvents().stream().mapToDouble(EventLink::revenue).sum()); }
    /** Charges and reimbursements plus the expenses of the chair's events. */
    @JsonIgnore
    public double getSpent() {
        return Money.cents(getReimbursements().stream().mapToDouble(ReimbursementRecord::getAmount).sum() + getEventSpent());
    }
    @JsonIgnore
    public double getRemaining() { return Money.cents(budgetCap - getSpent()); }
    /** Money raised entries plus the sales of the chair's events. */
    @JsonIgnore
    public double getRaisedTotal() {
        return Money.cents(getRaised().stream().mapToDouble(RaisedRecord::getAmount).sum() + getEventRaised());
    }
    /** How far the goal still is from being met (negative once it has been passed); 0 when no goal is set. */
    @JsonIgnore
    public double getRaiseRemaining() { return raiseGoal > 0 ? Money.cents(raiseGoal - getRaisedTotal()) : 0; }
}
