package com.fraternity.dues;

public class SmsLog {
    private String timestamp;
    private String memberName;
    private String phoneNumber;
    private String message;
    private String deliveryMode;
    private String date;

    public SmsLog() {}

    public SmsLog(String timestamp, String memberName, String phoneNumber, String message, String deliveryMode) {
        this(timestamp, memberName, phoneNumber, message, deliveryMode, java.time.LocalDate.now().toString());
    }

    public SmsLog(String timestamp, String memberName, String phoneNumber, String message, String deliveryMode, String date) {
        this.timestamp = timestamp;
        this.memberName = memberName;
        this.phoneNumber = phoneNumber;
        this.message = message;
        this.deliveryMode = deliveryMode;
        this.date = date;
    }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getMemberName() { return memberName; }
    public void setMemberName(String memberName) { this.memberName = memberName; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getDeliveryMode() { return deliveryMode; }
    public void setDeliveryMode(String deliveryMode) { this.deliveryMode = deliveryMode; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
}
