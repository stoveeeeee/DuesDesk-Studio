package com.fraternity.dues;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One step of the automatic reminder sequence, kept on the member's account: a friendly note before the due date, a
 * firmer one after the grace period, a past-due notice, then a flag for officer review. RESOLVED closes a review.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReminderStep {
    public static final String FRIENDLY = "FRIENDLY";
    public static final String FIRM = "FIRM";
    public static final String NOTICE = "NOTICE";
    public static final String REVIEW = "REVIEW";
    public static final String RESOLVED = "RESOLVED";

    private String id;
    /** ISO local date-time the step ran. */
    private String at;
    private String stage;
    /** Identifies the unpaid item the sequence is following, so each step runs once per item. */
    private String episodeKey;
    private String dueDate;
    /** What was due on the followed item, and the member's balance and past-due amount when the step ran. */
    private double amount;
    private double balance;
    private double pastDue;
    /** How the step went out: "Text sent", "Simulated text", "Notice ready to print", "Flagged for officer review". */
    private String outcome;
    private String detail;

    public ReminderStep() {}

    public ReminderStep(String id, String at, String stage, String episodeKey, String dueDate, double amount,
                        double balance, double pastDue, String outcome, String detail) {
        this.id = id;
        this.at = at;
        this.stage = stage;
        this.episodeKey = episodeKey;
        this.dueDate = dueDate;
        this.amount = Money.cents(amount);
        this.balance = Money.cents(balance);
        this.pastDue = Money.cents(pastDue);
        this.outcome = outcome;
        this.detail = detail;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAt() { return at; }
    public void setAt(String at) { this.at = at; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getEpisodeKey() { return episodeKey; }
    public void setEpisodeKey(String episodeKey) { this.episodeKey = episodeKey; }
    public String getDueDate() { return dueDate; }
    public void setDueDate(String dueDate) { this.dueDate = dueDate; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = Money.cents(amount); }
    public double getBalance() { return balance; }
    public void setBalance(double balance) { this.balance = Money.cents(balance); }
    public double getPastDue() { return pastDue; }
    public void setPastDue(double pastDue) { this.pastDue = Money.cents(pastDue); }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    /** Step number shown to people: 1 friendly, 2 firm, 3 notice, 4 officer review. */
    @JsonIgnore
    public int getRank() { return rank(stage); }

    @JsonIgnore
    public String getTitle() { return title(stage); }

    @JsonIgnore
    public String getDay() { return at == null || at.length() < 10 ? "" : at.substring(0, 10); }

    static int rank(String stage) {
        return switch (stage == null ? "" : stage) {
            case FRIENDLY -> 1;
            case FIRM -> 2;
            case NOTICE -> 3;
            case REVIEW -> 4;
            default -> 0;
        };
    }

    static String title(String stage) {
        return switch (stage == null ? "" : stage) {
            case FRIENDLY -> "Friendly reminder";
            case FIRM -> "Firm reminder";
            case NOTICE -> "Past-due notice";
            case REVIEW -> "Officer review";
            case RESOLVED -> "Review closed";
            default -> "Reminder";
        };
    }
}
