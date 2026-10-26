package com.fraternity.dues;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Imports Venmo "paid you" emails from the chapter mailbox: on startup, then every 10 minutes while the app is open,
 * and when the treasurer clicks Sync now. Each email is read once (by Message-ID); verified payments that match a
 * member exactly are applied, everything else waits on the Unmatched Payments screen. Failures become a status
 * message and never touch saved data.
 */
@Service
public class VenmoEmailSyncService {
    private static final Logger log = LoggerFactory.getLogger(VenmoEmailSyncService.class);
    static final int QUIET_DAYS = 14;
    /** Each sync re-reads a few days before the newest email seen, in case one arrived late; IDs prevent repeats. */
    private static final int OVERLAP_DAYS = 3;
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.ENGLISH);

    /** Opens the email source for the saved mailbox settings; swapped out in tests. */
    @FunctionalInterface
    interface SourceFactory {
        EmailSource open(String host, String address, char[] password);
    }

    public record SyncResult(int checked, int added, int applied, int waiting, int duplicates, int ignored) {
        public String summary() {
            if (added == 0) {
                return "Checked " + checked + " email" + (checked == 1 ? "" : "s") + "; no new Venmo payments"
                        + (duplicates > 0 ? " (" + duplicates + " already imported)" : "") + ".";
            }
            return "Imported " + added + " Venmo payment" + (added == 1 ? "" : "s") + ": " + applied + " applied automatically"
                    + (waiting > 0 ? ", " + waiting + " waiting in Unmatched Payments" : "")
                    + (duplicates > 0 ? "; " + duplicates + " already imported" : "") + ".";
        }
    }

    /** What the Settings page shows. Times are already formatted; blank when unknown. */
    public record Status(boolean enabled, boolean configured, boolean hasPassword, String lastSyncAt, boolean lastSyncOk,
                         String lastSyncMessage, String lastPaymentAt, String lastPaymentSummary,
                         boolean quiet, String quietMessage, String credentialStoreName) {}

    private final PaymentStore payments;
    private final Store store;
    private final MailCredentialStore credentials;
    private final SourceFactory sources;
    private final VenmoEmailParser parser;
    private final Clock clock;

    private volatile Instant lastSyncAt;
    private volatile boolean lastSyncOk;
    private volatile String lastSyncMessage = "";

    @Autowired
    public VenmoEmailSyncService(PaymentStore payments, Store store, MailCredentialStore credentials) {
        this(payments, store, credentials, ImapEmailSource::new, new VenmoEmailParser(), Clock.systemDefaultZone());
    }

    VenmoEmailSyncService(PaymentStore payments, Store store, MailCredentialStore credentials, SourceFactory sources,
                          VenmoEmailParser parser, Clock clock) {
        this.payments = payments;
        this.store = store;
        this.credentials = credentials;
        this.sources = sources;
        this.parser = parser;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 20, fixedDelay = 600, timeUnit = TimeUnit.SECONDS)
    public void scheduledSync() {
        AppSettings settings = store.getSettings();
        if (settings == null || !settings.isVenmoEmailEnabled()) return;
        try {
            Store.withActivitySource("Venmo email import", this::sync);
        } catch (RuntimeException e) {
            // sync() already reports problems through the status; this is a last guard for the scheduler thread.
            log.error("Venmo email sync failed", e);
        }
    }

    /** Reads new emails and records them. Never throws for mailbox, network or save problems: see {@link #status()}. */
    public synchronized SyncResult sync() {
        AppSettings settings = store.getSettings();
        String host = settings.getVenmoImapHost().trim();
        String address = settings.getVenmoEmailAddress().trim();
        if (host.isEmpty() || address.isEmpty()) {
            finish(false, "Enter the chapter mailbox address in Settings first.");
            return null;
        }
        char[] password = null;
        EmailSource source = null;
        try {
            password = credentials.loadPassword();
            if (password == null) {
                finish(false, "Enter the mailbox app password in Settings first.");
                return null;
            }
            source = sources.open(host, address, password);
            List<EmailSource.RawEmail> emails = source.fetchSince(since(settings));
            SyncResult result = process(emails);
            finish(true, result.summary());
            return result;
        } catch (EmailSource.EmailSourceException e) {
            log.warn("Venmo email sync could not read the mailbox: {}", e.getMessage());
            finish(false, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("Venmo email sync could not save", e);
            finish(false, "Payments were read but could not be saved, so nothing was recorded. They will be read again "
                    + "next time. " + (e.getMessage() == null ? "" : e.getMessage()));
        } catch (Exception e) {
            log.error("Venmo email sync failed", e);
            finish(false, "Venmo email sync failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (source instanceof ImapEmailSource imap) imap.wipe();
        }
        return null;
    }

    /** Tries the saved settings without importing anything. Returns a message for the Settings page. */
    public String testConnection() {
        AppSettings settings = store.getSettings();
        char[] password = null;
        EmailSource source = null;
        try {
            password = credentials.loadPassword();
            if (password == null) return "Enter the mailbox app password first.";
            source = sources.open(settings.getVenmoImapHost(), settings.getVenmoEmailAddress(), password);
            return source.testConnection();
        } catch (EmailSource.EmailSourceException e) {
            return e.getMessage();
        } catch (Exception e) {
            log.warn("Venmo mailbox connection test failed", e);
            return "Connection test failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (source instanceof ImapEmailSource imap) imap.wipe();
        }
    }

    SyncResult process(List<EmailSource.RawEmail> emails) {
        Map<String, Store.ProcessedEmail> processed = new LinkedHashMap<>();
        List<FlowEntry> entries = new ArrayList<>();
        Map<String, VenmoEmailParser.Payment> verifiedByEntry = new LinkedHashMap<>();
        Instant newestPayment = null;
        String newestSummary = null;
        int duplicates = 0;
        int ignored = 0;
        int checked = 0;
        String now = Instant.now(clock).toString();
        for (EmailSource.RawEmail email : emails) {
            if (email.messageId() != null && (payments.isProcessed(email.messageId().trim())
                    || processed.containsKey(email.messageId().trim()))) continue;
            VenmoEmailParser.Result result = parser.parse(email.content());
            if (payments.isProcessed(result.messageId()) || processed.containsKey(result.messageId())) continue;
            checked++;
            String receivedAt = result.receivedAt() == null ? null : result.receivedAt().toString();
            if (result.outcome() != VenmoEmailParser.Outcome.PAYMENT) {
                ignored++;
                processed.put(result.messageId(), new Store.ProcessedEmail(now, receivedAt, "IGNORED", null, false, result.reason()));
                continue;
            }
            VenmoEmailParser.Payment payment = result.payment();
            String entryId = entryId(payment);
            if (payments.hasPayment(entryId) || entries.stream().anyMatch(entry -> entry.getId().equals(entryId))) {
                duplicates++;
                processed.put(result.messageId(), new Store.ProcessedEmail(now, receivedAt, "DUPLICATE", entryId,
                        payment.verified(), "Already imported."));
                continue;
            }
            String description = payment.note().isBlank() ? "Venmo payment" : payment.note();
            if (!payment.verified()) description = "Unverified email (check Venmo): " + description;
            entries.add(new FlowEntry(entryId, payment.date(), description, payment.payerName(), payment.amount(), "Venmo"));
            processed.put(result.messageId(), new Store.ProcessedEmail(now, receivedAt, "PAYMENT", entryId,
                    payment.verified(), payment.verification()));
            if (payment.verified()) verifiedByEntry.put(entryId, payment);
            if (payment.receivedAt() != null && (newestPayment == null || payment.receivedAt().isAfter(newestPayment))) {
                newestPayment = payment.receivedAt();
                newestSummary = payment.payerName() + " · $" + String.format(Locale.US, "%,.2f", payment.amount());
            }
        }

        List<FlowEntry> added = payments.record(entries, processed, newestPayment, newestSummary);
        int applied = 0;
        List<Member> members = payments.members();
        Map<String, String> confirmedNames = payments.confirmedPayerNames();
        for (FlowEntry entry : added) {
            VenmoEmailParser.Payment payment = verifiedByEntry.get(entry.getId());
            if (payment == null) continue; // unverified emails always wait for the treasurer
            PaymentMatcher.Match match = PaymentMatcher.match(payment.payerName(), payment.note(), members, confirmedNames);
            if (!match.matched()) continue;
            try {
                if (payments.assign(entry.getId(), match.member().getId(), false)) applied++;
            } catch (IllegalStateException e) {
                // The payment is saved and stays in Unmatched Payments; nothing is half-applied.
                log.warn("Could not apply Venmo payment {} automatically", entry.getId(), e);
            }
        }
        return new SyncResult(checked, added.size(), applied, added.size() - applied, duplicates, ignored);
    }

    /**
     * The Cash Flow entry ID. A verified email uses Venmo's payment ID, the same one Venmo statement CSVs use, so the
     * same payment can't be imported once from email and again from a CSV. An unverified email gets an ID of its own,
     * so a forged email can never block the real payment from importing.
     */
    static String entryId(VenmoEmailParser.Payment payment) {
        String messageHash = VenmoEmailParser.sha256(payment.messageId()).substring(0, 24);
        if (!payment.verified()) return "venmo-unverified-" + messageHash;
        if (payment.venmoPaymentId() != null) return "venmo-" + payment.venmoPaymentId();
        if (payment.transactionId() != null) return "venmo-email-" + payment.transactionId();
        return "venmo-email-" + messageHash;
    }

    private LocalDate since(AppSettings settings) {
        ZoneId zone = clock.getZone();
        LocalDate importFrom = parseDate(settings.getVenmoEmailImportFrom(), LocalDate.now(clock));
        LocalDate overlap = payments.newestProcessedEmail()
                .map(at -> LocalDate.ofInstant(at, zone).minusDays(OVERLAP_DAYS)).orElse(null);
        return overlap != null && overlap.isAfter(importFrom) ? overlap : importFrom;
    }

    private void finish(boolean ok, String message) {
        lastSyncAt = Instant.now(clock);
        lastSyncOk = ok;
        lastSyncMessage = message == null ? "" : message;
    }

    public Status status() {
        AppSettings settings = store.getSettings();
        boolean configured = !settings.getVenmoEmailAddress().isBlank();
        boolean hasPassword = credentials.hasPassword();
        ZoneId zone = clock.getZone();
        Instant lastEmail = parseInstant(payments.lastVenmoEmailAt());
        // With no Venmo email yet, count from the day the import was set to start.
        Instant reference = lastEmail != null ? lastEmail
                : parseDate(settings.getVenmoEmailImportFrom(), LocalDate.now(clock)).atStartOfDay(zone).toInstant();
        long quietDays = Duration.between(reference, Instant.now(clock)).toDays();
        boolean quiet = settings.isVenmoEmailEnabled() && configured && quietDays >= QUIET_DAYS;
        String quietMessage = !quiet ? "" : lastEmail == null
                ? "No Venmo payment emails have arrived in the " + quietDays + " days since setup. Check the Gmail forwarding filter."
                : "No Venmo payment emails have arrived in " + quietDays + " days. If members have paid, check the Gmail forwarding filter.";
        return new Status(settings.isVenmoEmailEnabled(), configured, hasPassword,
                lastSyncAt == null ? "" : format(lastSyncAt, zone), lastSyncOk, lastSyncMessage,
                lastEmail == null ? "" : format(lastEmail, zone), payments.lastPaymentSummary(),
                quiet, quietMessage, credentials.storeName());
    }

    private static String format(Instant at, ZoneId zone) {
        return DISPLAY.format(LocalDateTime.ofInstant(at, zone));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
