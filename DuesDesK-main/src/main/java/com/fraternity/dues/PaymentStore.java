package com.fraternity.dues;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the Venmo email import needs from the app's local data file, in one place. Everything goes through
 * {@link Store}, which writes the file atomically and keeps a backup copy, so a failed save never corrupts data.
 */
@Component
public class PaymentStore {
    private final Store store;

    public PaymentStore(Store store) {
        this.store = store;
    }

    public boolean isProcessed(String messageId) {
        return store.isEmailProcessed(messageId);
    }

    public boolean hasPayment(String flowEntryId) {
        return store.hasFlowEntry(flowEntryId);
    }

    public List<Member> members() {
        return store.getMembers();
    }

    public Map<String, String> confirmedPayerNames() {
        return store.getCounterpartyAliases();
    }

    public Optional<Instant> newestProcessedEmail() {
        return store.latestProcessedEmailReceivedAt();
    }

    /** Saves new payments and processed Message-IDs together; returns the payments actually added. */
    public List<FlowEntry> record(List<FlowEntry> payments, Map<String, Store.ProcessedEmail> processed,
                                  Instant newestPaymentAt, String newestPaymentSummary) {
        return store.recordProcessedEmails(payments, processed, newestPaymentAt, newestPaymentSummary);
    }

    /**
     * Applies a waiting payment to a member's balance. With {@code rememberPayerName}, the payer's Venmo name is
     * saved on the member so the next payment from that name is applied automatically.
     */
    public boolean assign(String flowEntryId, String memberId, boolean rememberPayerName) {
        return store.confirmReconciliation(flowEntryId, memberId, rememberPayerName);
    }

    public boolean dismiss(String flowEntryId) {
        return store.ignoreReconciliation(flowEntryId);
    }

    /** Incoming payments still waiting for a member (the same queue Cash Flow shows), newest first. */
    public List<FlowEntry> unmatched() {
        return store.getFlowEntries().stream()
                .filter(entry -> "PENDING".equals(entry.getReconciliationStatus()) && entry.getAmount() > 0)
                .sorted(Comparator.comparing((FlowEntry entry) -> entry.getDate() == null ? "" : entry.getDate()).reversed())
                .toList();
    }

    public Optional<Store.ProcessedEmail> emailFor(String flowEntryId) {
        return store.processedEmailForEntry(flowEntryId);
    }

    public String lastVenmoEmailAt() {
        return store.getLastVenmoEmailAt();
    }

    public String lastPaymentSummary() {
        return store.getLastVenmoPaymentSummary();
    }
}
