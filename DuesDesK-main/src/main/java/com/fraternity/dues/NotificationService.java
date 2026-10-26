package com.fraternity.dues;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Desktop notifications for the things a treasurer must not miss: bills coming due, payment plan installments due
 * today, and a missed or failed automatic backup. In the desktop app they appear as operating system notifications
 * (each alert once); in a browser the page shows them with the browser's notifications. The header bell lists the
 * alerts that are current either way.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    /** A daily backup older than this counts as missed (a day plus a few hours of slack for a sleeping laptop). */
    static final Duration BACKUP_MISSED_AFTER = Duration.ofHours(26);

    /** One alert. {@code key} identifies it so each is shown once; {@code href} is where clicking it goes. */
    public record Alert(String key, String kind, String title, String body, String href, String tone) {
        public String getIcon() {
            return switch (kind) {
                case "bill" -> "bi-receipt";
                case "installment" -> "bi-calendar-check";
                case "backup" -> "bi-cloud-slash";
                default -> "bi-bell";
            };
        }
    }

    /** Shows one notification on the computer. */
    public interface DesktopNotifier {
        boolean show(String title, String body, String href);
    }

    private final Store store;
    private final CloudBackupService backups;
    private volatile DesktopNotifier notifier;

    public NotificationService(Store store, CloudBackupService backups) {
        this.store = store;
        this.backups = backups;
    }

    public void setNotifier(DesktopNotifier notifier) {
        this.notifier = notifier;
    }

    public boolean hasDesktopNotifier() {
        return notifier != null;
    }

    /** Every alert that applies today, most urgent first. */
    public List<Alert> currentAlerts(LocalDate today) {
        AppSettings settings = store.getSettings();
        List<Alert> alerts = new ArrayList<>();
        if (settings.isNotifyBackup()) backupAlert(settings).ifPresent(alerts::add);
        if (settings.isNotifyInstallments()) installmentAlert(today).ifPresent(alerts::add);
        if (settings.isNotifyBills()) alerts.addAll(billAlerts(settings, today));
        return alerts;
    }

    private Optional<Alert> backupAlert(AppSettings settings) {
        if (!settings.isCloudBackupEnabled() || settings.getCloudBackupFolder().isBlank()) return Optional.empty();
        String status = settings.getCloudBackupStatus();
        String day = LocalDate.now().toString();
        if (status.startsWith("Last backup failed")) {
            return Optional.of(new Alert("backup:failed:" + day, "backup", "Automatic backup failed",
                    status + ". Open Settings to check the backup folder.", "/settings#cloudBackup", "danger"));
        }
        Optional<Instant> latest = backups == null ? Optional.empty()
                : backups.latestSnapshotTime(Path.of(settings.getCloudBackupFolder().trim()));
        if (latest.isPresent() && latest.get().isAfter(Instant.now().minus(BACKUP_MISSED_AFTER))) return Optional.empty();
        String when = latest.map(instant -> "The last snapshot was " + ChronoUnit.DAYS.between(instant, Instant.now()) + " day(s) ago.")
                .orElse("No snapshot has been written to the backup folder yet.");
        return Optional.of(new Alert("backup:missed:" + day, "backup", "Daily backup missed",
                when + " Run a backup now from Settings.", "/settings#cloudBackup", "warning"));
    }

    private Optional<Alert> installmentAlert(LocalDate today) {
        List<String> due = new ArrayList<>();
        double total = 0;
        for (Member member : store.getMembers()) {
            for (PaymentInstallment installment : member.getInstallments() == null ? List.<PaymentInstallment>of() : member.getInstallments()) {
                if (installment.isPaid() || !today.toString().equals(installment.getDueDate())) continue;
                due.add(member.getName() + " " + ExecutivePdf.money(installment.getRemainingAmount()));
                total += installment.getRemainingAmount();
            }
        }
        if (due.isEmpty()) return Optional.empty();
        String list = String.join(", ", due.subList(0, Math.min(3, due.size()))) + (due.size() > 3 ? " and " + (due.size() - 3) + " more" : "");
        return Optional.of(new Alert("installments:" + today, "installment",
                due.size() == 1 ? "1 installment is due today" : due.size() + " installments are due today",
                list + " (" + ExecutivePdf.money(total) + " in all).", "/#duesLedgerView", "info"));
    }

    private List<Alert> billAlerts(AppSettings settings, LocalDate today) {
        List<Alert> alerts = new ArrayList<>();
        int ahead = Math.max(0, settings.getNotifyBillDaysAhead());
        for (RecurringBill bill : store.getRecurringBills()) {
            LocalDate next = bill.nextPending();
            if (next == null) continue;
            long days = ChronoUnit.DAYS.between(today, next);
            if (days > ahead) continue;
            if (days < 0 && bill.isAutomatic()) continue;
            String phase = days < 0 ? "overdue" : days == 0 ? "today" : "ahead";
            String verb = bill.isAutomatic() ? "is charged" : "is due";
            String when = days < 0 ? (days == -1 ? "was due yesterday" : "is " + (-days) + " days overdue")
                    : days == 0 ? verb + " today" : days == 1 ? verb + " tomorrow" : verb + " " + next.format(SHORT);
            alerts.add(new Alert("bill:" + bill.getId() + ":" + next + ":" + phase, "bill",
                    bill.getName() + " " + when, ExecutivePdf.money(bill.getAmount())
                    + (bill.getPayee() == null || bill.getPayee().isBlank() ? "" : " to " + bill.getPayee())
                    + (bill.isAutomatic() ? " (automatic)." : ". Mark it paid on the Calendar once it is."),
                    "/calendar#billReminders", days < 0 ? "danger" : days <= 1 ? "warning" : "info"));
        }
        // One-off bills typed into Settings ("name | date | amount").
        for (String row : settings.getUpcomingBills().lines().map(String::trim).filter(line -> !line.isBlank()).toList()) {
            String[] fields = row.split("\\|", -1);
            if (fields.length != 3) continue;
            try {
                LocalDate due = LocalDate.parse(fields[1].trim());
                long days = ChronoUnit.DAYS.between(today, due);
                if (days < 0 || days > ahead) continue;
                double amount = Double.parseDouble(fields[2].trim());
                String name = fields[0].trim();
                alerts.add(new Alert("upcoming:" + name.toLowerCase(Locale.ROOT) + ":" + due + ":" + (days == 0 ? "today" : "ahead"), "bill",
                        name + (days == 0 ? " is due today" : days == 1 ? " is due tomorrow" : " is due " + due.format(SHORT)),
                        ExecutivePdf.money(amount) + ".", "/settings#treasury", days <= 1 ? "warning" : "info"));
            } catch (DateTimeParseException | NumberFormatException ignored) {
                // Settings validation keeps these well formed.
            }
        }
        return alerts;
    }

    /** Alerts not shown yet, which are then remembered as shown. Old memories are pruned after 45 days. */
    synchronized List<Alert> takeUnshown(LocalDate today) {
        List<Alert> fresh;
        synchronized (store) {
            Map<String, String> shown = new LinkedHashMap<>(store.getSettings().getNotificationsShown());
            fresh = currentAlerts(today).stream().filter(alert -> !shown.containsKey(alert.key())).toList();
            boolean pruned = shown.entrySet().removeIf(entry -> {
                try {
                    return LocalDate.parse(entry.getValue()).isBefore(today.minusDays(45));
                } catch (DateTimeParseException e) {
                    return true;
                }
            });
            if (fresh.isEmpty() && !pruned) return List.of();
            fresh.forEach(alert -> shown.put(alert.key(), today.toString()));
            store.getSettings().setNotificationsShown(shown);
            Store.withActivitySource("Desktop notifications", () -> {
                store.save();
                return null;
            });
        }
        return fresh;
    }

    /** Every few minutes in the desktop app: shows each new alert once as an operating system notification. */
    @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)
    public void deliver() {
        DesktopNotifier target = notifier;
        if (target == null || !store.getSettings().isNotificationsEnabled()) return;
        try {
            List<Alert> fresh = takeUnshown(LocalDate.now());
            // Several at once read better as one summary than as a stack of pop-ups.
            if (fresh.size() > 3) {
                target.show(fresh.size() + " DuesDesk alerts", String.join("; ", fresh.stream().limit(4).map(Alert::title).toList())
                        + (fresh.size() > 4 ? "; and more" : "") + ".", "/");
                return;
            }
            for (Alert alert : fresh) target.show(alert.title(), alert.body(), alert.href());
        } catch (RuntimeException e) {
            log.warn("Unable to show desktop notifications", e);
        }
    }

    /** Sends a sample notification so the treasurer can check they appear. Returns false outside the desktop app. */
    public boolean sendTest() {
        DesktopNotifier target = notifier;
        return target != null && target.show("DuesDesk notifications are on",
                "Bills coming due, installments due today and missed backups will appear like this.", "/settings#notifications");
    }
}
