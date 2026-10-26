package com.fraternity.dues;

public class AppSettings {
    private String smsTemplate = "Hey {name}, friendly reminder from the Treasurer: ${dueAmount} is due on {dueDate} ({reason}). Your current past-due balance is ${pastDue} (total semester balance: ${totalOwed}). Please reach out if you need help.";
    private boolean autoSendEnabled = false;
    private String frequency = "WEEKLY";
    private String dayOfWeek = "MONDAY";
    private String sendTime = "10:00";
    private String lastAutoRunDate = "";
    private int reminderDaysBeforeDue = 3;
    private int reminderDaysAfterDue = 1;
    private double ihqBillAmount = 0;
    private String ihqStartDate = "";
    private String rentSmsTemplate = "Hey {name}, quick reminder from the house: you have ${balanceDue} of rent outstanding for {room}. Please get it paid soon. Thanks!";
    private String appearanceMode = "light";
    private java.util.Map<String, String> attentionSnoozes = new java.util.LinkedHashMap<>();
    private boolean cloudBackupEnabled;
    private String cloudBackupFolder = "";
    private int cloudBackupRetentionDays = 30;
    private boolean lateFeesEnabled;
    private int lateFeeGraceDays = 7;
    private String lateFeeType = "FIXED";
    private double lateFeeAmount = 25;
    private double socialClearanceTolerance;
    private boolean socialClearanceDuesOnly = true;
    private double openingCashBalance;
    private String upcomingBills = "";
    private String cloudBackupStatus = "No automatic backup has run.";
    /** The chapter's payment accounts (Venmo, Cash App, PayPal, Zelle) used for {{venmo_link}}-style reminder links. */
    private java.util.List<PaymentLinks.Handle> chapterCashServiceHandles = new java.util.ArrayList<>();
    /** Shown on payment plan agreements, e.g. "Alpha Beta Chapter". */
    private String chapterName = "";
    /** Set by the semester rollover: the term now underway, and when it started. */
    private String currentTermName = "";
    private String lastRolloverDate = "";
    /** Fines and discounts saved from the Dues page's "Member charges & discounts" section. */
    private java.util.List<ChargePreset> savedChargePresets = new java.util.ArrayList<>();

    /** Venmo email import: the chapter mailbox Venmo "paid you" emails are forwarded to. The app password is kept
     *  encrypted elsewhere (MailCredentialStore), never here. */
    private boolean venmoEmailEnabled;
    private String venmoImapHost = "imap.gmail.com";
    private String venmoEmailAddress = "";
    /** Emails received before this date (ISO) are left alone, so payments already recorded are not imported again. */
    private String venmoEmailImportFrom = "";

    /** Automatic reminder sequence. Step 1 uses smsTemplate and reminderDaysBeforeDue; later steps count days after due. */
    private boolean sequenceFriendlyEnabled = true;
    private boolean sequenceFirmEnabled = true;
    private int sequenceFirmDays = 7;
    private String sequenceFirmTemplate = "{name}, your dues payment of ${dueAmount} was due on {dueDate} and is now past the grace period. Your past-due balance is ${pastDue}. Please pay this week or reply to set up a payment plan: {{pay_links}}";
    private boolean sequenceNoticeEnabled = true;
    private int sequenceNoticeDays = 14;
    private String sequenceNoticeTemplate = "{name}, a formal past-due notice has been issued for your dues account. Past-due balance: ${pastDue} (total balance ${totalOwed}). Please pay or contact the Treasurer right away to keep your account in good standing.";
    private boolean sequenceReviewEnabled = true;
    private int sequenceReviewDays = 21;

    /** Desktop (operating system) notifications. */
    private boolean notificationsEnabled = true;
    private boolean notifyBills = true;
    private int notifyBillDaysAhead = 3;
    private boolean notifyInstallments = true;
    private boolean notifyBackup = true;
    /** Alerts already shown, by key, with the date they were shown, so each appears once. */
    private java.util.Map<String, String> notificationsShown = new java.util.LinkedHashMap<>();

    private java.util.List<String> dashboardStats = new java.util.ArrayList<>(
            java.util.List.of("inflow", "balance", "pastdue", "rate", "ihq"));

    public AppSettings() {}

    public java.util.List<String> getDashboardStats() {
        if (dashboardStats == null || dashboardStats.size() != 5) {
            dashboardStats = new java.util.ArrayList<>(java.util.List.of("inflow", "balance", "pastdue", "rate", "ihq"));
        }
        return dashboardStats;
    }
    public void setDashboardStats(java.util.List<String> dashboardStats) { this.dashboardStats = dashboardStats; }

    public String getSmsTemplate() { return smsTemplate; }
    public void setSmsTemplate(String smsTemplate) { this.smsTemplate = smsTemplate; }
    public boolean isAutoSendEnabled() { return autoSendEnabled; }
    public void setAutoSendEnabled(boolean autoSendEnabled) { this.autoSendEnabled = autoSendEnabled; }
    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }
    public String getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(String dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public String getSendTime() { return sendTime; }
    public void setSendTime(String sendTime) { this.sendTime = sendTime; }
    public double getIhqBillAmount() { return ihqBillAmount; }
    public void setIhqBillAmount(double ihqBillAmount) { this.ihqBillAmount = Money.cents(ihqBillAmount); }
    public String getIhqStartDate() { return ihqStartDate; }
    public void setIhqStartDate(String ihqStartDate) { this.ihqStartDate = ihqStartDate; }
    public String getRentSmsTemplate() { return rentSmsTemplate; }
    public void setRentSmsTemplate(String rentSmsTemplate) { this.rentSmsTemplate = rentSmsTemplate; }
    public String getAppearanceMode() {
        return "dark".equals(appearanceMode) || "system".equals(appearanceMode) ? appearanceMode : "light";
    }
    public void setAppearanceMode(String appearanceMode) { this.appearanceMode = appearanceMode; }
    public java.util.Map<String, String> getAttentionSnoozes() {
        if (attentionSnoozes == null) attentionSnoozes = new java.util.LinkedHashMap<>();
        return attentionSnoozes;
    }
    public void setAttentionSnoozes(java.util.Map<String, String> attentionSnoozes) {
        this.attentionSnoozes = attentionSnoozes;
    }
    public boolean isCloudBackupEnabled() { return cloudBackupEnabled; }
    public void setCloudBackupEnabled(boolean cloudBackupEnabled) { this.cloudBackupEnabled = cloudBackupEnabled; }
    public String getCloudBackupFolder() { return cloudBackupFolder == null ? "" : cloudBackupFolder; }
    public void setCloudBackupFolder(String cloudBackupFolder) { this.cloudBackupFolder = cloudBackupFolder; }
    public int getCloudBackupRetentionDays() { return cloudBackupRetentionDays; }
    public void setCloudBackupRetentionDays(int cloudBackupRetentionDays) { this.cloudBackupRetentionDays = cloudBackupRetentionDays; }
    public boolean isLateFeesEnabled() { return lateFeesEnabled; }
    public void setLateFeesEnabled(boolean lateFeesEnabled) { this.lateFeesEnabled = lateFeesEnabled; }
    public int getLateFeeGraceDays() { return lateFeeGraceDays; }
    public void setLateFeeGraceDays(int lateFeeGraceDays) { this.lateFeeGraceDays = lateFeeGraceDays; }
    public String getLateFeeType() { return "PERCENT".equals(lateFeeType) ? "PERCENT" : "FIXED"; }
    public void setLateFeeType(String lateFeeType) { this.lateFeeType = lateFeeType; }
    public double getLateFeeAmount() { return lateFeeAmount; }
    public void setLateFeeAmount(double lateFeeAmount) { this.lateFeeAmount = lateFeeAmount; }
    public double getSocialClearanceTolerance() { return socialClearanceTolerance; }
    public void setSocialClearanceTolerance(double socialClearanceTolerance) { this.socialClearanceTolerance = Money.cents(socialClearanceTolerance); }
    public boolean isSocialClearanceDuesOnly() { return socialClearanceDuesOnly; }
    public void setSocialClearanceDuesOnly(boolean socialClearanceDuesOnly) { this.socialClearanceDuesOnly = socialClearanceDuesOnly; }
    public double getOpeningCashBalance() { return openingCashBalance; }
    public void setOpeningCashBalance(double openingCashBalance) { this.openingCashBalance = Money.cents(openingCashBalance); }
    public String getUpcomingBills() { return upcomingBills == null ? "" : upcomingBills; }
    public void setUpcomingBills(String upcomingBills) { this.upcomingBills = upcomingBills; }
    public String getChapterName() { return chapterName == null ? "" : chapterName; }
    public void setChapterName(String chapterName) { this.chapterName = chapterName; }
    public String getCurrentTermName() { return currentTermName == null ? "" : currentTermName; }
    public void setCurrentTermName(String currentTermName) { this.currentTermName = currentTermName; }
    public String getLastRolloverDate() { return lastRolloverDate == null ? "" : lastRolloverDate; }
    public void setLastRolloverDate(String lastRolloverDate) { this.lastRolloverDate = lastRolloverDate; }
    public java.util.List<ChargePreset> getSavedChargePresets() {
        if (savedChargePresets == null) savedChargePresets = new java.util.ArrayList<>();
        return savedChargePresets;
    }
    public void setSavedChargePresets(java.util.List<ChargePreset> savedChargePresets) {
        this.savedChargePresets = savedChargePresets;
    }
    public java.util.List<PaymentLinks.Handle> getChapterCashServiceHandles() {
        if (chapterCashServiceHandles == null) chapterCashServiceHandles = new java.util.ArrayList<>();
        return chapterCashServiceHandles;
    }
    public void setChapterCashServiceHandles(java.util.List<PaymentLinks.Handle> chapterCashServiceHandles) {
        this.chapterCashServiceHandles = chapterCashServiceHandles;
    }
    public String getCloudBackupStatus() {
        return cloudBackupStatus == null ? "No automatic backup has run." : cloudBackupStatus;
    }
    public void setCloudBackupStatus(String cloudBackupStatus) { this.cloudBackupStatus = cloudBackupStatus; }
    public String getLastAutoRunDate() { return lastAutoRunDate; }
    public void setLastAutoRunDate(String lastAutoRunDate) { this.lastAutoRunDate = lastAutoRunDate; }
    public int getReminderDaysBeforeDue() { return reminderDaysBeforeDue; }
    public void setReminderDaysBeforeDue(int reminderDaysBeforeDue) { this.reminderDaysBeforeDue = reminderDaysBeforeDue; }
    public int getReminderDaysAfterDue() { return reminderDaysAfterDue; }
    public void setReminderDaysAfterDue(int reminderDaysAfterDue) { this.reminderDaysAfterDue = reminderDaysAfterDue; }
    public boolean isSequenceFriendlyEnabled() { return sequenceFriendlyEnabled; }
    public void setSequenceFriendlyEnabled(boolean sequenceFriendlyEnabled) { this.sequenceFriendlyEnabled = sequenceFriendlyEnabled; }
    public boolean isSequenceFirmEnabled() { return sequenceFirmEnabled; }
    public void setSequenceFirmEnabled(boolean sequenceFirmEnabled) { this.sequenceFirmEnabled = sequenceFirmEnabled; }
    public int getSequenceFirmDays() { return sequenceFirmDays; }
    public void setSequenceFirmDays(int sequenceFirmDays) { this.sequenceFirmDays = sequenceFirmDays; }
    public String getSequenceFirmTemplate() { return sequenceFirmTemplate == null ? "" : sequenceFirmTemplate; }
    public void setSequenceFirmTemplate(String sequenceFirmTemplate) { this.sequenceFirmTemplate = sequenceFirmTemplate; }
    public boolean isSequenceNoticeEnabled() { return sequenceNoticeEnabled; }
    public void setSequenceNoticeEnabled(boolean sequenceNoticeEnabled) { this.sequenceNoticeEnabled = sequenceNoticeEnabled; }
    public int getSequenceNoticeDays() { return sequenceNoticeDays; }
    public void setSequenceNoticeDays(int sequenceNoticeDays) { this.sequenceNoticeDays = sequenceNoticeDays; }
    public String getSequenceNoticeTemplate() { return sequenceNoticeTemplate == null ? "" : sequenceNoticeTemplate; }
    public void setSequenceNoticeTemplate(String sequenceNoticeTemplate) { this.sequenceNoticeTemplate = sequenceNoticeTemplate; }
    public boolean isSequenceReviewEnabled() { return sequenceReviewEnabled; }
    public void setSequenceReviewEnabled(boolean sequenceReviewEnabled) { this.sequenceReviewEnabled = sequenceReviewEnabled; }
    public int getSequenceReviewDays() { return sequenceReviewDays; }
    public void setSequenceReviewDays(int sequenceReviewDays) { this.sequenceReviewDays = sequenceReviewDays; }
    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean notificationsEnabled) { this.notificationsEnabled = notificationsEnabled; }
    public boolean isNotifyBills() { return notifyBills; }
    public void setNotifyBills(boolean notifyBills) { this.notifyBills = notifyBills; }
    public int getNotifyBillDaysAhead() { return notifyBillDaysAhead; }
    public void setNotifyBillDaysAhead(int notifyBillDaysAhead) { this.notifyBillDaysAhead = notifyBillDaysAhead; }
    public boolean isNotifyInstallments() { return notifyInstallments; }
    public void setNotifyInstallments(boolean notifyInstallments) { this.notifyInstallments = notifyInstallments; }
    public boolean isNotifyBackup() { return notifyBackup; }
    public void setNotifyBackup(boolean notifyBackup) { this.notifyBackup = notifyBackup; }
    public java.util.Map<String, String> getNotificationsShown() {
        if (notificationsShown == null) notificationsShown = new java.util.LinkedHashMap<>();
        return notificationsShown;
    }
    public void setNotificationsShown(java.util.Map<String, String> notificationsShown) { this.notificationsShown = notificationsShown; }
    public boolean isVenmoEmailEnabled() { return venmoEmailEnabled; }
    public void setVenmoEmailEnabled(boolean venmoEmailEnabled) { this.venmoEmailEnabled = venmoEmailEnabled; }
    public String getVenmoImapHost() { return venmoImapHost == null || venmoImapHost.isBlank() ? "imap.gmail.com" : venmoImapHost; }
    public void setVenmoImapHost(String venmoImapHost) { this.venmoImapHost = venmoImapHost; }
    public String getVenmoEmailAddress() { return venmoEmailAddress == null ? "" : venmoEmailAddress; }
    public void setVenmoEmailAddress(String venmoEmailAddress) { this.venmoEmailAddress = venmoEmailAddress; }
    public String getVenmoEmailImportFrom() { return venmoEmailImportFrom == null ? "" : venmoEmailImportFrom; }
    public void setVenmoEmailImportFrom(String venmoEmailImportFrom) { this.venmoEmailImportFrom = venmoEmailImportFrom; }
}
