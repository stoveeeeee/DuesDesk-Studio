package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** The escalating reminder sequence, the past-due notice, and desktop notification alerts. */
class ReminderSequenceTest {
    @TempDir
    Path tempDir;

    private final LocalDate today = LocalDate.now();

    private Store storeWithChargeDue(LocalDate due, boolean muted) throws Exception {
        Path file = tempDir.resolve("store.json");
        Files.writeString(file, """
                {"members":[
                {"id":"m1","name":"Marcus Vance","phoneNumber":"5550001","totalOwed":200,"pastDueBalance":0,"paidThisMonth":0,"status":"Payment Plan","lastTextedDate":"Never","muteReminders":%s,
                 "charges":[{"id":"c1","date":"2026-01-05","description":"Spring dues","amount":200,"pastDueAmount":0,"dueDate":"%s","paidAmount":0}]}
                ],"settings":{"ihqBillAmount":0}}""".formatted(muted, due));
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }

    private static ReminderSequenceService sequence(Store store) {
        SmsService sms = new SmsService(store);
        ReflectionTestUtils.setField(sms, "simulationMode", true);
        return new ReminderSequenceService(store, sms);
    }

    @Test
    void aFriendlyTextGoesOutBeforeTheDueDateOnceAndIsLoggedOnTheAccount() throws Exception {
        Store store = storeWithChargeDue(today.plusDays(3), false);
        ReminderSequenceService sequence = sequence(store);

        List<ReminderSequenceService.Plan> plans = sequence.preview(today);
        assertEquals(1, plans.size());
        assertEquals(ReminderStep.FRIENDLY, plans.getFirst().stage());

        ReminderSequenceService.RunResult result = sequence.run(today);
        assertEquals(1, result.texts());
        Member member = store.findMemberById("m1").orElseThrow();
        ReminderStep step = member.getReminderLog().getFirst();
        assertEquals(ReminderStep.FRIENDLY, step.getStage());
        assertEquals("Simulated text", step.getOutcome());
        assertTrue(step.getDetail().contains("Marcus Vance"));
        assertTrue(sequence.preview(today).isEmpty(), "each step runs once");
        assertTrue(store.getActivityLog().stream().anyMatch(entry -> "Automatic reminder sequence".equals(entry.getSource())
                && "Reminder step".equals(entry.getEntityType())));
    }

    @Test
    void theStepsEscalateFromFirmToNoticeToOfficerReview() throws Exception {
        Store store = storeWithChargeDue(today.minusDays(8), false);
        ReminderSequenceService sequence = sequence(store);
        assertEquals(ReminderStep.FIRM, sequence.preview(today).getFirst().stage());
        sequence.run(today);

        // A week later the notice is due; the firm text is never sent again.
        assertEquals(ReminderStep.NOTICE, sequence.preview(today.plusDays(7)).getFirst().stage());
        sequence.run(today.plusDays(7));
        Member member = store.findMemberById("m1").orElseThrow();
        ReminderStep notice = PastDueNoticeService.notices(member).getFirst();
        byte[] pdf = new PastDueNoticeService().noticePdf(member, notice, store.getSettings());
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertTrue(text.contains("PAST-DUE NOTICE"));
            assertTrue(text.contains("Marcus Vance"));
            assertTrue(text.contains("Spring dues"));
        }

        assertEquals(ReminderStep.REVIEW, sequence.preview(today.plusDays(14)).getFirst().stage());
        sequence.run(today.plusDays(14));
        assertTrue(member.isOfficerReviewFlagged());
        assertEquals(List.of(member), sequence.flaggedMembers());
        assertTrue(sequence.preview(today.plusDays(15)).isEmpty(), "nothing is repeated once every step has run");

        assertTrue(sequence.resolveReview("m1", "Plan agreed at chapter"));
        assertFalse(member.isOfficerReviewFlagged());
        assertEquals(ReminderStep.RESOLVED, member.getReminderLog().getLast().getStage());
        assertEquals(List.of(ReminderStep.FIRM, ReminderStep.NOTICE, ReminderStep.REVIEW, ReminderStep.RESOLVED),
                member.getReminderLog().stream().map(ReminderStep::getStage).toList());
    }

    @Test
    void mutedMembersGetNoTextsButStillReachOfficerReview() throws Exception {
        Store store = storeWithChargeDue(today.minusDays(25), true);
        ReminderSequenceService sequence = sequence(store);
        assertEquals(0, sequence.run(today).texts());
        assertEquals(1, sequence.run(today.plusDays(7)).notices());
        assertEquals(1, sequence.run(today.plusDays(14)).flagged());
        assertTrue(store.getSmsLogs().isEmpty());
        assertTrue(store.findMemberById("m1").orElseThrow().getReminderLog().getFirst().getOutcome().contains("muted"));
    }

    @Test
    void longOverdueAccountsStartAtTheFirstStepInsteadOfJumpingToReview() throws Exception {
        Store store = storeWithChargeDue(today.minusDays(60), false);
        ReminderSequenceService sequence = sequence(store);
        assertEquals(ReminderStep.FIRM, sequence.preview(today).getFirst().stage());
        sequence.run(today);
        assertTrue(sequence.preview(today.plusDays(3)).isEmpty(), "the notice waits its gap after the firm text");
        assertEquals(ReminderStep.NOTICE, sequence.preview(today.plusDays(7)).getFirst().stage());
    }

    @Test
    void paidItemsLeaveTheSequenceAndCloseAnOpenReview() throws Exception {
        Store store = storeWithChargeDue(today.minusDays(25), false);
        ReminderSequenceService sequence = sequence(store);
        sequence.run(today);
        sequence.run(today.plusDays(7));
        sequence.run(today.plusDays(14));
        Member member = store.findMemberById("m1").orElseThrow();
        assertTrue(member.isOfficerReviewFlagged());

        store.recordPayment("m1", new PaymentRecord(today.toString(), 200, "Venmo", "pay-1"));
        assertNull(ReminderSequenceService.anchor(member, today));
        sequence.run(today.plusDays(15));
        assertFalse(member.isOfficerReviewFlagged());
    }

    @Test
    void installmentsDueTodayAndBillsComingDueBecomeAlertsShownOnce() throws Exception {
        Store store = storeWithChargeDue(today.plusDays(30), false);
        store.addInstallments("m1", List.of(new PaymentInstallment("i1", today.toString(), "First installment", 50)));
        RecurringBill bill = new RecurringBill(null, "House insurance", 120, "MONTHLY", today.plusDays(1).toString(), "",
                "Insurance", "Acme", "MANUAL", List.of(), today.toString());
        store.saveRecurringBill(bill);

        NotificationService notifications = new NotificationService(store, null);
        List<NotificationService.Alert> alerts = notifications.currentAlerts(today);
        assertTrue(alerts.stream().anyMatch(alert -> alert.title().equals("1 installment is due today")));
        assertTrue(alerts.stream().anyMatch(alert -> alert.title().equals("House insurance is due tomorrow")));

        java.util.List<String> shown = new java.util.ArrayList<>();
        notifications.setNotifier((title, body, href) -> shown.add(title));
        notifications.deliver();
        assertEquals(2, shown.size());
        notifications.deliver();
        assertEquals(2, shown.size(), "each alert is shown once");

        store.getSettings().setNotifyInstallments(false);
        assertTrue(notifications.currentAlerts(today).stream().noneMatch(alert -> "installment".equals(alert.kind())));
    }
}
