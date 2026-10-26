package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** The activity log: every change with before and after values, undo for deletions, and the GreekBill template. */
class ActivityLogTest {
    @TempDir
    Path tempDir;

    private Path file;

    private Store store() throws Exception {
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }

    private Store freshStore() throws Exception {
        file = tempDir.resolve("store.json");
        Files.writeString(file, """
                {"members":[
                {"id":"m1","name":"Marcus Vance","phoneNumber":"5550001","totalOwed":100,"pastDueBalance":0,"paidThisMonth":0,"status":"Payment Plan","lastTextedDate":"Never","muteReminders":false,
                 "charges":[{"id":"c1","date":"2026-01-05","description":"Fall dues","amount":100,"pastDueAmount":0,"dueDate":"2099-01-15","paidAmount":0}]},
                {"id":"m2","name":"Grant Hayes","phoneNumber":"5550002","totalOwed":0,"pastDueBalance":0,"paidThisMonth":0,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false}
                ],
                "residents":[{"id":"r1","name":"Grant Hayes","phoneNumber":"5550002","room":"2A","monthlyRent":500,"balanceDue":0}],
                "settings":{"ihqBillAmount":0}}""");
        return store();
    }

    private static List<ActivityEntry> batch(Store store, String batchId) {
        return store.getActivityLog().stream().filter(entry -> batchId.equals(entry.getBatchId())).toList();
    }

    @Test
    void aBatchChargeIsRecordedPerMemberWithTheBalanceBeforeAndAfter() throws Exception {
        Store store = freshStore();
        int charged = Store.withActivitySource("Batch charge", () -> store.addBatchCharges(List.of("m1", "m2"), "Formal fee", 50,
                LocalDate.now().plusDays(10).toString()));
        assertEquals(2, charged);

        List<ActivityEntry> log = store.getActivityLog();
        List<ActivityEntry> charges = log.stream().filter(entry -> "Charge".equals(entry.getEntityType())).toList();
        assertEquals(2, charges.size());
        assertEquals(1, charges.stream().map(ActivityEntry::getBatchId).distinct().count(), "one action is one batch");
        ActivityEntry marcus = charges.stream().filter(entry -> "m1".equals(entry.getMemberId())).findFirst().orElseThrow();
        assertEquals("Batch charge", marcus.getSource());
        assertEquals(ActivityEntry.ADDED, marcus.getAction());
        assertEquals(50.0, marcus.getAmount());
        assertEquals(100.0, marcus.getBalanceBefore());
        assertEquals(150.0, marcus.getBalanceAfter());
        assertEquals("Charge added to Marcus Vance: Formal fee ($50.00)", marcus.getSummary());
        // The balance move is shown on the charge, not as a separate "member updated" line.
        assertTrue(log.stream().noneMatch(entry -> "Member".equals(entry.getEntityType()) && ActivityEntry.CHANGED.equals(entry.getAction())));
    }

    @Test
    void aRemovedChargeCanBeUndoneAndItsBalanceComesBack() throws Exception {
        Store store = freshStore();
        DashboardController controller = new DashboardController(store, new SmsService(store));
        Store.withActivitySource("Remove charge", () -> controller.removeCharge("m1", "c1", new RedirectAttributesModelMap()));
        assertEquals(0.0, store.findMemberById("m1").orElseThrow().getTotalOwed());

        ActivityEntry removal = store.getActivityLog().stream().filter(ActivityEntry::isRemoval).findFirst().orElseThrow();
        assertEquals("Charge removed from Marcus Vance: Fall dues ($100.00)", removal.getSummary());
        assertTrue(Store.undoable(batch(store, removal.getBatchId()), java.time.LocalDateTime.now()));

        Store.UndoResult result = store.undoActivity(removal.getBatchId());
        assertTrue(result.undone(), result.message());
        Member restored = store.findMemberById("m1").orElseThrow();
        assertEquals(100.0, restored.getTotalOwed());
        assertEquals(List.of("c1"), restored.getCharges().stream().map(ChargeRecord::getId).toList());
        assertTrue(batch(store, removal.getBatchId()).stream().allMatch(ActivityEntry::isUndone));
        assertTrue(store.getActivityLog().stream().anyMatch(entry -> entry.getSource().startsWith("Undo")
                && ActivityEntry.ADDED.equals(entry.getAction())), "the undo is recorded too");
        assertFalse(store.undoActivity(removal.getBatchId()).undone(), "an undo cannot run twice");

        // It survives a restart.
        Store reloaded = store();
        assertEquals(100.0, reloaded.findMemberById("m1").orElseThrow().getTotalOwed());
        assertTrue(reloaded.getActivityLog().stream().anyMatch(ActivityEntry::isUndone));
    }

    @Test
    void aDeletedMemberComesBackWithTheirHistory() throws Exception {
        Store store = freshStore();
        assertTrue(store.removeMember("m1"));
        ActivityEntry removal = store.getActivityLog().stream().filter(ActivityEntry::isRemoval).findFirst().orElseThrow();
        assertEquals("Member removed: Marcus Vance", removal.getSummary());

        assertTrue(store.undoActivity(removal.getBatchId()).undone());
        Member back = store.findMemberById("m1").orElseThrow();
        assertEquals(100.0, back.getTotalOwed());
        assertEquals(1, back.getCharges().size());
    }

    @Test
    void settingsChangesKeepTheOldAndNewValues() throws Exception {
        Store store = freshStore();
        store.getSettings().setLateFeeAmount(40);
        store.save();
        ActivityEntry change = store.getActivityLog().getLast();
        assertEquals("Settings", change.getEntityType());
        ActivityEntry.FieldChange fee = change.getChanges().getFirst();
        assertEquals("Late fee amount", fee.getLabel());
        assertEquals("$25.00", fee.getBefore());
        assertEquals("$40.00", fee.getAfter());
        assertFalse(Store.undoable(batch(store, change.getBatchId()), java.time.LocalDateTime.now()), "only deletions offer undo");
    }

    @Test
    void theGreekBillTemplateListsBalancesAndEachChargeWithItsMembersButNoRent() throws Exception {
        Store store = freshStore();
        LocalDate today = LocalDate.now();
        store.addBatchCharges(List.of("m1", "m2"), "Formal fee", 50, today.plusDays(10).toString());
        store.addMemberAdjustment("m2", ChargePreset.FINE, "Missed chapter", 15, "", today);
        store.addResidentCharges(List.of("r1"), "Cleaning fee", 30, "");

        GreekBillTemplateService service = new GreekBillTemplateService(store);
        GreekBillTemplateService.Period period = GreekBillTemplateService.resolvePeriod("last-7", "", "", today, "");
        GreekBillTemplateService.Report report = service.build(period, today);

        GreekBillTemplateService.MemberRow marcus = report.members().stream().filter(row -> "m1".equals(row.memberId())).findFirst().orElseThrow();
        assertEquals(100.0, marcus.before());
        assertEquals(150.0, marcus.after());
        GreekBillTemplateService.MemberRow grant = report.members().stream().filter(row -> "m2".equals(row.memberId())).findFirst().orElseThrow();
        assertEquals(0.0, grant.before());
        assertEquals(65.0, grant.after());

        assertEquals(List.of("Formal fee", "Missed chapter"), report.applied().stream().map(GreekBillTemplateService.ChargeGroup::description).toList());
        assertEquals(List.of("Grant Hayes", "Marcus Vance"), report.applied().getFirst().lines().stream()
                .map(GreekBillTemplateService.ChargeLine::memberName).toList());
        assertTrue(report.applied().stream().noneMatch(group -> group.description().equals("Cleaning fee")), "rent charges stay out");
        assertEquals(3, report.getChargeCount());
        assertEquals(115.0, report.getTotalCharged());

        byte[] pdf = service.pdf(report);
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertTrue(text.contains("Template for Greekbill charges"));
            assertTrue(text.contains("Formal fee"));
            assertTrue(text.contains("Marcus Vance"));
            assertFalse(text.contains("Cleaning fee"));
        }
    }

    @Test
    void balancesBeforeTheLogBeganAreWorkedOutFromTheLedger() {
        Member member = new Member("m", "Pat", "1", 150, 0, 0, "Payment Plan", "Never", false);
        ChargeRecord older = new ChargeRecord("c-old", "2026-01-01", "Fall dues", 100, 0);
        ChargeRecord newer = new ChargeRecord("c-new", "2026-03-01", "Formal fee", 75, 0);
        member.getCharges().addAll(List.of(older, newer));
        member.getPaymentHistory().add(new PaymentRecord("2026-03-05", 25, "Venmo", "p1"));
        // Logging began on Apr 1: before that, the dated charge and payment are walked back.
        double onFeb1 = GreekBillTemplateService.balanceAt(member, LocalDate.of(2026, 2, 1).atStartOfDay(), List.of(),
                LocalDate.of(2026, 4, 1).atStartOfDay());
        assertEquals(100.0, onFeb1);
    }

    @Test
    void periodChoicesResolveToDates() {
        LocalDate today = LocalDate.of(2026, 10, 25);
        assertEquals(LocalDate.of(2026, 10, 19), GreekBillTemplateService.resolvePeriod("last-7", "", "", today, "").from());
        assertEquals(LocalDate.of(2026, 9, 1), GreekBillTemplateService.resolvePeriod("last-month", "", "", today, "").from());
        assertEquals(LocalDate.of(2026, 8, 20), GreekBillTemplateService.resolvePeriod("this-term", "", "", today, "2026-08-20").from());
        GreekBillTemplateService.Period custom = GreekBillTemplateService.resolvePeriod("custom", "2026-10-10", "2026-10-01", today, "");
        assertEquals(LocalDate.of(2026, 10, 1), custom.from());
        assertEquals(LocalDate.of(2026, 10, 10), custom.to());
        assertEquals("last-30", GreekBillTemplateService.resolvePeriod("nonsense", "", "", today, "").key());
    }

    @Test
    void requestsAreLabelledWithTheActionBehindThem() {
        assertEquals("Add charge", ActivityWebConfig.sourceFor("POST", "/members/42/charges"));
        assertEquals("Remove charge", ActivityWebConfig.sourceFor("POST", "/members/42/charges/c1/remove"));
        assertEquals("Venmo CSV import", ActivityWebConfig.sourceFor("POST", "/numbers/flow/upload-venmo"));
        assertEquals("Automatic update", ActivityWebConfig.sourceFor("GET", "/"));
        assertNotNull(ActivityWebConfig.sourceFor("POST", "/something-new"));
    }
}
