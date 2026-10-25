package com.fraternity.dues;

import com.twilio.Twilio;
import com.twilio.http.NetworkHttpClient;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.apache.http.client.config.RequestConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private final Store store;

    @Value("${twilio.simulation.mode:true}")
    private boolean simulationMode;

    @Value("${twilio.account.sid:}")
    private String accountSid;

    @Value("${twilio.auth.token:}")
    private String authToken;

    @Value("${twilio.from.number:}")
    private String fromNumber;

    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("MMM dd, hh:mm a");
    public record ReminderCandidate(Member member, String dueDate, String reason, double amount) {}

    public SmsService(Store store) {
        this.store = store;
    }

    public String formatMessage(Member member) {
        return formatMessage(member, null);
    }

    private String formatMessage(Member member, ReminderCandidate candidate) {
        String template = store.getSettings().getSmsTemplate();
        String dueDate = candidate == null || candidate.dueDate().isBlank()
                ? "as soon as possible" : candidate.dueDate();
        String reason = candidate == null ? "past-due balance" : candidate.reason();
        double amount = candidate == null ? member.getPastDueBalance() : candidate.amount();
        String dueAmount = String.format(java.util.Locale.US, "%.2f", amount);
        return payLinks(template, payAmount(member, amount), member.getName() + " - " + (candidate == null ? "Dues" : candidate.reason()))
                .replace("{name}", member.getName())
                .replace("{pastDue}", String.format(java.util.Locale.US, "%.2f", member.getPastDueBalance()))
                .replace("{totalOwed}", String.format(java.util.Locale.US, "%.2f", member.getTotalOwed()))
                .replace("{dueDate}", dueDate)
                .replace("{reason}", reason)
                .replace("{dueAmount}", dueAmount);
    }

    public String formatRentMessage(Resident resident) {
        return payLinks(store.getSettings().getRentSmsTemplate(), resident.getBalanceDue(), rentNote(resident))
                .replace("{name}", resident.getName())
                .replace("{room}", resident.getRoom() == null || resident.getRoom().isBlank() ? "your room" : resident.getRoom())
                .replace("{balanceDue}", String.format(java.util.Locale.US, "%.2f", resident.getBalanceDue()));
    }

    /** The amount a pay link asks for: what is due now, or the whole balance when nothing is past due yet. */
    private static double payAmount(Member member, double due) {
        return Money.positive(due) ? due : member.getTotalOwed();
    }

    private static String rentNote(Resident resident) {
        return resident.getName() + " - Rent" + (resident.getRoom() == null || resident.getRoom().isBlank() ? "" : " (" + resident.getRoom() + ")");
    }

    /** Fills {{venmo_link}}, {{cashapp_link}}, {{paypal_link}} and {{pay_links}} from the chapter's payment handles. */
    private String payLinks(String message, double amount, String note) {
        AppSettings settings = store.getSettings();
        return PaymentLinks.fill(message, settings == null ? java.util.List.of() : settings.getChapterCashServiceHandles(), amount, note);
    }

    public synchronized boolean sendReminderToMember(Member member) {
        return sendReminderToMember(member, null);
    }

    public record BulkMessageResult(int eligible, int sent, int failed) {}

    public synchronized BulkMessageResult sendMessageToMembersByStatus(String status, String message) {
        java.util.List<Member> recipients = store.getMembers().stream()
                .filter(member -> status.equals(member.getStatus()) && !member.isMuteReminders())
                .toList();
        int sent = 0;
        int failed = 0;
        for (Member member : recipients) {
            if (store.getSmsRemainingToday() <= 0) break;
            if (sendCustomMessageToMember(member, message)) sent++;
            else failed++;
        }
        return new BulkMessageResult(recipients.size(), sent, failed);
    }

    public synchronized boolean sendCustomMessageToMember(Member member, String message) {
        if (message == null || message.isBlank()) return false;
        String messageBody = payLinks(message, payAmount(member, member.getPastDueBalance()), member.getName() + " - Dues")
                .replace("{name}", member.getName());
        synchronized (store) {
            if (!store.reserveSmsSlot()) {
                log.warn("Daily SMS limit reached; custom message not sent.");
                return false;
            }
        }
        return sendReminderLocked(member::setLastTextedDate, member.getName(), member.getPhoneNumber(), messageBody);
    }

    private synchronized boolean sendReminderToMember(Member member, ReminderCandidate candidate) {
        String messageBody;
        synchronized (store) {
            if (!store.reserveSmsSlot()) {
                log.warn("Daily SMS limit reached; reminder not sent.");
                return false;
            }
            messageBody = formatMessage(member, candidate);
        }
        return sendReminderLocked(member::setLastTextedDate, member.getName(), member.getPhoneNumber(), messageBody);
    }

    public synchronized boolean sendRentReminder(Resident resident) {
        return sendToResident(resident, null);
    }

    /** Sends {@code customMessage}, or the rent reminder template when it is null/blank. */
    public synchronized boolean sendToResident(Resident resident, String customMessage) {
        String messageBody;
        synchronized (store) {
            if (!store.reserveSmsSlot()) {
                log.warn("Daily SMS limit reached; message not sent.");
                return false;
            }
            messageBody = customMessage == null || customMessage.isBlank()
                    ? formatRentMessage(resident)
                    : payLinks(customMessage, resident.getBalanceDue(), rentNote(resident)).replace("{name}", resident.getName());
        }
        return sendReminderLocked(resident::setLastTextedDate, resident.getName(), resident.getPhoneNumber(), messageBody);
    }

    public synchronized int sendRentRemindersToAllWithBalance() {
        int count = 0;
        for (Resident resident : store.getResidents()) {
            if (Money.positive(resident.getBalanceDue()) && !resident.isMuteReminders() && sendRentReminder(resident)) count++;
        }
        return count;
    }

    public synchronized int broadcastToResidents(String message) {
        int count = 0;
        for (Resident resident : store.getResidents()) {
            if (!resident.isMuteReminders() && sendToResident(resident, message)) count++;
        }
        return count;
    }

    private boolean sendReminderLocked(java.util.function.Consumer<String> markTexted, String memberName, String phoneNumber, String messageBody) {
        String nowStr = LocalDateTime.now().format(DISPLAY_FMT);
        Store.TwilioCredentials savedCredentials = store.getTwilioCredentials();
        boolean useSavedCredentials = savedCredentials != null && savedCredentials.enabled();
        boolean simulate = simulationMode && !useSavedCredentials;
        String activeAccountSid = useSavedCredentials ? savedCredentials.accountSid() : accountSid;
        String activeAuthToken = useSavedCredentials ? savedCredentials.authToken() : authToken;
        String activeFromNumber = useSavedCredentials ? savedCredentials.fromNumber() : fromNumber;

        if (simulate) {
            log.info("Simulated SMS reminder dispatched.");
            synchronized (store) {
                markTexted.accept(nowStr);
                store.addSmsLog(new SmsLog(nowStr, memberName, phoneNumber, messageBody, "SIMULATED"));
            }
            return true;
        } else {
            try {
                RequestConfig timeoutConfig = RequestConfig.custom()
                        .setConnectTimeout(5_000)
                        .setConnectionRequestTimeout(5_000)
                        .setSocketTimeout(10_000)
                        .build();
                NetworkHttpClient timeoutClient = new NetworkHttpClient(timeoutConfig) {
                    @Override
                    public com.twilio.http.Response reliableRequest(com.twilio.http.Request request) {
                        return super.reliableRequest(request, new int[0], 0, 0);
                    }
                };
                Twilio.setRestClient(new TwilioRestClient.Builder(activeAccountSid, activeAuthToken)
                        .httpClient(timeoutClient)
                        .build());
                Message.creator(
                        new PhoneNumber(phoneNumber),
                        new PhoneNumber(activeFromNumber),
                        messageBody
                ).create();

                synchronized (store) {
                    markTexted.accept(nowStr);
                    store.addSmsLog(new SmsLog(nowStr, memberName, phoneNumber, messageBody, "TWILIO LIVE"));
                }
                return true;
            } catch (Exception e) {
                log.error("Twilio reminder request failed.", e);
                synchronized (store) {
                    store.addSmsLog(new SmsLog(nowStr, memberName, phoneNumber, "ERROR: " + e.getMessage(), "FAILED"));
                }
                return false;
            }
        }
    }

    public boolean isLiveConfigured() {
        Store.TwilioCredentials saved = store.getTwilioCredentials();
        if (saved != null && saved.enabled()) return true;
        return !simulationMode && isComplete(accountSid, authToken, fromNumber);
    }

    private boolean isComplete(String sid, String token, String number) {
        return sid != null && !sid.isBlank()
                && token != null && !token.isBlank()
                && number != null && !number.isBlank();
    }

    public synchronized java.util.List<ReminderCandidate> reminderPreview() {
        LocalDate today = LocalDate.now();
        AppSettings settings = store.getSettings();
        LocalDate beforeDate = today.plusDays(Math.max(0, settings.getReminderDaysBeforeDue()));
        LocalDate afterDate = today.minusDays(Math.max(0, settings.getReminderDaysAfterDue()));
        java.util.Map<String, ReminderCandidate> candidates = new java.util.LinkedHashMap<>();
        for (Member member : store.getMembers()) {
            if (member.isMuteReminders()) continue;
            if (member.getCharges() != null) {
                for (ChargeRecord charge : member.getCharges()) {
                    if (charge.getDueDate() == null || charge.getDueDate().isBlank()) continue;
                    LocalDate dueDate;
                    try {
                        dueDate = LocalDate.parse(charge.getDueDate());
                    } catch (java.time.format.DateTimeParseException e) {
                        log.warn("Skipping invalid charge due date for member {}", member.getId());
                        continue;
                    }
                    boolean upcoming = dueDate.equals(beforeDate);
                    boolean overdue = dueDate.equals(afterDate);
                    if ((upcoming || overdue) && Money.positive(member.getTotalOwed()) && Money.positive(charge.getRemainingAmount())) {
                        candidates.putIfAbsent(member.getId(), new ReminderCandidate(member, dueDate.toString(),
                                upcoming ? "Due soon: " + charge.getDescription() : "Past due: " + charge.getDescription(),
                                charge.getRemainingAmount()));
                    }
                }
            }
            if (member.getInstallments() != null) {
                for (PaymentInstallment installment : member.getInstallments()) {
                    if (installment.isPaid() || !Money.positive(member.getTotalOwed())) continue;
                    try {
                        LocalDate dueDate = LocalDate.parse(installment.getDueDate());
                        boolean upcoming = dueDate.equals(beforeDate);
                        boolean overdue = dueDate.equals(afterDate);
                        if (upcoming || overdue) {
                            candidates.putIfAbsent(member.getId(), new ReminderCandidate(member, dueDate.toString(),
                                    upcoming ? "Installment due soon" : "Installment past due",
                                    installment.getRemainingAmount()));
                        }
                    } catch (java.time.format.DateTimeParseException e) {
                        log.warn("Skipping invalid installment due date for member {}", member.getId());
                    }
                }
            }
            boolean hasActiveDatedArrangement = (member.getCharges() != null
                    && member.getCharges().stream().anyMatch(charge -> Money.positive(charge.getRemainingAmount())
                    && charge.getDueDate() != null && !charge.getDueDate().isBlank()))
                    || (member.getInstallments() != null
                    && member.getInstallments().stream().anyMatch(installment -> !installment.isPaid()));
            if (Money.positive(member.getPastDueBalance()) && !hasActiveDatedArrangement) {
                candidates.putIfAbsent(member.getId(), new ReminderCandidate(member, "", "Past-due balance", member.getPastDueBalance()));
            }
        }
        return java.util.List.copyOf(candidates.values());
    }

    public synchronized int sendReminderPreview() {
        int sent = 0;
        for (ReminderCandidate candidate : reminderPreview()) {
            if (sendReminderToMember(candidate.member(), candidate)) sent++;
        }
        return sent;
    }

    @Scheduled(fixedRate = 30000)
    public synchronized void checkAutomatedSchedule() {
        boolean enabled;
        String frequency;
        String dayOfWeek;
        String sendTime;
        String lastAutoRunDate;
        synchronized (store) {
            AppSettings settings = store.getSettings();
            enabled = settings.isAutoSendEnabled();
            frequency = settings.getFrequency();
            dayOfWeek = settings.getDayOfWeek();
            sendTime = settings.getSendTime();
            lastAutoRunDate = settings.getLastAutoRunDate();
        }
        if (!enabled) return;

        LocalDate today = LocalDate.now();
        String todayStr = today.toString();

        if (todayStr.equals(lastAutoRunDate)) return;

        LocalTime nowTime = LocalTime.now();
        LocalTime targetTime;
        try {
            targetTime = LocalTime.parse(sendTime);
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("Invalid configured SMS dispatch time {}; using 10:00", sendTime);
            targetTime = LocalTime.of(10, 0);
        }

        if (nowTime.getHour() == targetTime.getHour() && nowTime.getMinute() == targetTime.getMinute()) {
            boolean shouldFireToday = false;
            String freq = frequency == null ? "" : frequency.toUpperCase();

            switch (freq) {
                case "DAILY":
                    shouldFireToday = true;
                    break;
                case "WEEKLY":
                    shouldFireToday = today.getDayOfWeek().name().equalsIgnoreCase(dayOfWeek);
                    break;
                case "BIWEEKLY":
                    if (today.getDayOfWeek().name().equalsIgnoreCase(dayOfWeek)) {
                        if (lastAutoRunDate == null || lastAutoRunDate.isEmpty()) {
                            shouldFireToday = true;
                        } else {
                            LocalDate lastRun = LocalDate.parse(lastAutoRunDate);
                            shouldFireToday = ChronoUnit.DAYS.between(lastRun, today) >= 14;
                        }
                    }
                    break;
                case "MONTHLY":
                    shouldFireToday = (today.getDayOfMonth() == 1);
                    break;
            }

            if (shouldFireToday) {
                sendReminderPreview();
                synchronized (store) {
                    store.getSettings().setLastAutoRunDate(todayStr);
                    store.save();
                }
            }
        }
    }
}
