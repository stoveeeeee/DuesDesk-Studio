package com.fraternity.dues;

import java.util.ArrayList;
import java.util.List;

public class Member {
    private String id;
    private String name;
    private String phoneNumber;
    /** Optional, for "Open in Email" reminders. */
    private String email = "";
    private double totalOwed;
    private double pastDueBalance;
    private double paidThisMonth;
    private String status;
    private String lastTextedDate;
    private boolean muteReminders;
    private List<PaymentRecord> paymentHistory = new ArrayList<>();
    private List<ChargeRecord> charges = new ArrayList<>();
    private List<PaymentInstallment> installments = new ArrayList<>();
    private String clearedUntilDate;
    private List<MemberCredit> credits = new ArrayList<>();
    /** Other names this member's payments arrive under (a parent, a Venmo handle), remembered from confirmed matches. */
    private List<String> recognizedPayerAliases = new ArrayList<>();
    /** The name shown on this member's Venmo payments; an exact match applies a Venmo email payment automatically. */
    private String venmoDisplayName = "";
    /** A code such as DUES-JSMITH; a Venmo note containing it applies the payment to this member automatically. */
    private String duesCode = "";
    /** Which roster the member appears on in the Dues ledger: Member (active), Inactive or Alumni. */
    private String membershipStatus = MEMBER;
    public static final String MEMBER = "Member";
    public static final String INACTIVE = "Inactive";
    public static final String ALUMNI = "Alumni";
    public static final java.util.List<String> MEMBERSHIP_STATUSES = java.util.List.of(MEMBER, INACTIVE, ALUMNI);
    /** Every step the automatic reminder sequence has taken on this account, oldest first. */
    private List<ReminderStep> reminderLog = new ArrayList<>();
    /** Set (ISO date) while the account is flagged for officer review by the reminder sequence. */
    private String officerReviewSince;
    private String officerReviewReason;
    /** When a past-due balance with no dated charge behind it was first seen (ISO date), so the sequence can time it. */
    private String pastDueSince;

    public Member() {}

    public Member(String id, String name, String phoneNumber, double totalOwed, double pastDueBalance, double paidThisMonth, String status, String lastTextedDate, boolean muteReminders) {
        this.id = id;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.totalOwed = Money.cents(totalOwed);
        this.pastDueBalance = Money.cents(pastDueBalance);
        this.paidThisMonth = Money.cents(paidThisMonth);
        this.status = status;
        this.lastTextedDate = lastTextedDate;
        this.muteReminders = muteReminders;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public double getTotalOwed() { return totalOwed; }
    public void setTotalOwed(double totalOwed) { this.totalOwed = Money.cents(totalOwed); }
    public double getPastDueBalance() { return pastDueBalance; }
    public void setPastDueBalance(double pastDueBalance) { this.pastDueBalance = Money.cents(pastDueBalance); }
    public double getPaidThisMonth() { return paidThisMonth; }
    public void setPaidThisMonth(double paidThisMonth) { this.paidThisMonth = Money.cents(paidThisMonth); }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastTextedDate() { return lastTextedDate; }
    public void setLastTextedDate(String lastTextedDate) { this.lastTextedDate = lastTextedDate; }
    public boolean isMuteReminders() { return muteReminders; }
    public void setMuteReminders(boolean muteReminders) { this.muteReminders = muteReminders; }
    public List<PaymentRecord> getPaymentHistory() { return paymentHistory; }
    public void setPaymentHistory(List<PaymentRecord> paymentHistory) { this.paymentHistory = paymentHistory; }
    public List<ChargeRecord> getCharges() { return charges; }
    public void setCharges(List<ChargeRecord> charges) { this.charges = charges; }
    public List<PaymentInstallment> getInstallments() { return installments; }
    public void setInstallments(List<PaymentInstallment> installments) { this.installments = installments; }
    public String getClearedUntilDate() { return clearedUntilDate; }
    public void setClearedUntilDate(String clearedUntilDate) { this.clearedUntilDate = clearedUntilDate; }
    public List<MemberCredit> getCredits() {
        if (credits == null) credits = new ArrayList<>();
        return credits;
    }
    public void setCredits(List<MemberCredit> credits) { this.credits = credits; }
    public List<String> getRecognizedPayerAliases() {
        if (recognizedPayerAliases == null) recognizedPayerAliases = new ArrayList<>();
        return recognizedPayerAliases;
    }
    public void setRecognizedPayerAliases(List<String> recognizedPayerAliases) { this.recognizedPayerAliases = recognizedPayerAliases; }
    public String getEmail() { return email == null ? "" : email; }
    public void setEmail(String email) { this.email = email; }
    public String getVenmoDisplayName() { return venmoDisplayName == null ? "" : venmoDisplayName; }
    public void setVenmoDisplayName(String venmoDisplayName) { this.venmoDisplayName = venmoDisplayName; }
    public String getDuesCode() { return duesCode == null ? "" : duesCode; }
    public void setDuesCode(String duesCode) { this.duesCode = duesCode; }
    public String getMembershipStatus() { return normalizeMembershipStatus(membershipStatus); }
    public void setMembershipStatus(String membershipStatus) { this.membershipStatus = normalizeMembershipStatus(membershipStatus); }
    /** "Inactive" and "Alumni" (any case) are kept; anything else, including blank, is an active Member. */
    public static String normalizeMembershipStatus(String value) {
        if (value == null) return MEMBER;
        for (String status : MEMBERSHIP_STATUSES) if (status.equalsIgnoreCase(value.trim())) return status;
        return MEMBER;
    }
    public List<ReminderStep> getReminderLog() {
        if (reminderLog == null) reminderLog = new ArrayList<>();
        return reminderLog;
    }
    public void setReminderLog(List<ReminderStep> reminderLog) { this.reminderLog = reminderLog; }
    public String getOfficerReviewSince() { return officerReviewSince; }
    public void setOfficerReviewSince(String officerReviewSince) { this.officerReviewSince = officerReviewSince; }
    public String getOfficerReviewReason() { return officerReviewReason; }
    public void setOfficerReviewReason(String officerReviewReason) { this.officerReviewReason = officerReviewReason; }
    public String getPastDueSince() { return pastDueSince; }
    public void setPastDueSince(String pastDueSince) { this.pastDueSince = pastDueSince; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isOfficerReviewFlagged() { return officerReviewSince != null && !officerReviewSince.isBlank(); }
}
