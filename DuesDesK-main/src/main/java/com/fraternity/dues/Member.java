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
}
