package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** Resident linking, receipt compression, payer aliases, payment links, plan agreements and the semester rollover. */
class TreasuryUpgradesTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @TempDir
    Path tempDir;

    private Store store(String name, String json) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, json);
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }

    private static final String MEMBERS = """
            {"id":"m1","name":"Marcus Vance","phoneNumber":"5550001","totalOwed":100,"pastDueBalance":0,"paidThisMonth":0,"status":"Payment Plan","lastTextedDate":"Never","muteReminders":false},
            {"id":"m2","name":"Grant Hayes","phoneNumber":"5550002","totalOwed":0,"pastDueBalance":0,"paidThisMonth":0,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false}""";

    // ---- 3. Resident linking ----

    @Test
    void residentsAutoLinkByNameAndClearanceUsesTheLinkFirst() throws Exception {
        Store store = store("link.json", "{\"members\":[" + MEMBERS + "],\"residents\":["
                + "{\"id\":\"r1\",\"name\":\"  marcus VANCE \",\"phoneNumber\":\"1\",\"room\":\"1\",\"monthlyRent\":500,\"balanceDue\":300,\"paidThisMonth\":0},"
                + "{\"id\":\"r2\",\"name\":\"G. Hayes\",\"phoneNumber\":\"2\",\"room\":\"2\",\"monthlyRent\":500,\"balanceDue\":200,\"paidThisMonth\":0},"
                + "{\"id\":\"r3\",\"name\":\"Grant Hayes\",\"phoneNumber\":\"3\",\"room\":\"3\",\"monthlyRent\":500,\"balanceDue\":50,\"paidThisMonth\":0,\"linkedMemberId\":\"\"}"
                + "],\"settings\":{\"ihqBillAmount\":0,\"socialClearanceDuesOnly\":false,\"socialClearanceTolerance\":0}}");

        assertEquals("m1", store.findResidentById("r1").orElseThrow().getLinkedMemberId(), "a save from before linking is matched on load (trimmed, any case)");
        assertNull(store.findResidentById("r2").orElseThrow().getLinkedMemberId(), "a different spelling is left for the treasurer");
        assertEquals("", store.findResidentById("r3").orElseThrow().getLinkedMemberId(), "an explicit non-member is never auto-linked");

        Member grant = store.findMemberById("m2").orElseThrow();
        assertTrue(store.isSociallyCleared(grant), "r3 shares his name but was marked as not a member");
        store.findResidentById("r2").orElseThrow().setLinkedMemberId("m2");
        assertFalse(store.isSociallyCleared(grant), "linked despite the typo, so his $200 rent now counts");
        assertEquals("r2", store.residentForMember(grant).orElseThrow().getId());
    }

    // ---- 4. Receipt compression ----

    @Test
    void wideReceiptPhotosAreResizedTo1600PxJpegs() throws Exception {
        BufferedImage photo = new BufferedImage(3200, 2400, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < photo.getHeight(); y += 4) for (int x = 0; x < photo.getWidth(); x += 4) photo.setRGB(x, y, random.nextInt());
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(photo, "png", png);

        ReceiptCompressor.Result result = ReceiptCompressor.compress(png.toByteArray(), photo, ".png");
        assertEquals(".jpg", result.extension());
        assertEquals(1600, result.width());
        assertEquals(1200, result.height(), "proportions kept");
        assertTrue(result.bytes().length < png.size() / 2, "much smaller than the original");

        Store store = store("receipt.json", "{\"members\":[],\"settings\":{\"ihqBillAmount\":0}}");
        String saved = store.saveReceipt(new MockMultipartFile("receipt", "r.png", "image/png", png.toByteArray()));
        assertTrue(saved.endsWith(".jpg"));
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(store.readReceipt(saved)));
        assertEquals(1600, stored.getWidth());
    }

    @Test
    void smallReceiptsKeepWhicheverFileIsSmaller() throws Exception {
        BufferedImage tiny = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(tiny, "png", png);
        ReceiptCompressor.Result result = ReceiptCompressor.compress(png.toByteArray(), tiny, ".png");
        assertEquals(40, result.width());
        assertTrue(result.bytes().length <= png.size());
    }

    // ---- 5. Payer aliases ----

    @Test
    void payerAliasesAreRememberedOnlyWhenAskedAndCanBeForgotten() throws Exception {
        Store store = store("alias.json", "{\"members\":[" + MEMBERS + "],\"flowEntries\":["
                + "{\"id\":\"b1\",\"date\":\"2026-10-01\",\"description\":\"Dues\",\"counterparty\":\"Linda Vance-Ortiz\",\"amount\":25,\"source\":\"BANK\",\"reconciliationStatus\":\"PENDING\"},"
                + "{\"id\":\"b2\",\"date\":\"2026-10-02\",\"description\":\"Dues\",\"counterparty\":\"Dad Hayes\",\"amount\":10,\"source\":\"BANK\",\"reconciliationStatus\":\"PENDING\"},"
                + "{\"id\":\"b3\",\"date\":\"2026-10-03\",\"description\":\"Dues\",\"counterparty\":\"Marcus Vance\",\"amount\":5,\"source\":\"BANK\",\"reconciliationStatus\":\"PENDING\"}"
                + "],\"settings\":{\"ihqBillAmount\":0}}");

        assertTrue(store.confirmReconciliation("b2", "m2", false));
        assertTrue(store.findMemberByCounterpartyAlias("Dad Hayes").isEmpty(), "not remembered when the box was unticked");

        assertTrue(store.confirmReconciliation("b1", "m1", true));
        assertTrue(store.confirmReconciliation("b3", "m1", true));
        Member marcus = store.findMemberById("m1").orElseThrow();
        assertEquals(List.of("Linda Vance-Ortiz"), marcus.getRecognizedPayerAliases(), "his own name is not stored as an alias");
        assertEquals("m1", store.findMemberByCounterpartyAlias(" linda vance ortiz ").orElseThrow().getId());

        assertTrue(store.removePayerAlias("m1", "Linda Vance-Ortiz"));
        assertTrue(store.findMemberByCounterpartyAlias("Linda Vance-Ortiz").isEmpty());
        assertFalse(store.removePayerAlias("m1", "Linda Vance-Ortiz"));
    }

    // ---- 6. Payment links ----

    @Test
    void paymentLinksPrefillTheAmountAndAReconciliationNote() {
        assertEquals("https://venmo.com/?txn=pay&recipients=ChapterTreasury&amount=125.50&note=Marcus%20Vance%20-%20Dues",
                PaymentLinks.link(PaymentLinks.VENMO, "@ChapterTreasury", 125.5, "Marcus Vance - Dues"));
        assertEquals("https://cash.app/$chapter/40.00", PaymentLinks.link(PaymentLinks.CASH_APP, "$chapter", 40, "x"));
        assertEquals("https://paypal.me/chapterpay/40.00USD", PaymentLinks.link(PaymentLinks.PAYPAL, "chapterpay", 40, "x"));
        assertEquals("", PaymentLinks.link(PaymentLinks.ZELLE, "pay@chapter.org", 40, "x"), "Zelle has no deep link");

        List<PaymentLinks.Handle> handles = List.of(new PaymentLinks.Handle(PaymentLinks.VENMO, "@Chapter"),
                new PaymentLinks.Handle(PaymentLinks.ZELLE, "pay@chapter.org"));
        String filled = PaymentLinks.fill("Pay here {{venmo_link}} | {paypal_link}|\n{{pay_links}}", handles, 20, "Sam - Rent");
        assertEquals("Pay here https://venmo.com/?txn=pay&recipients=Chapter&amount=20.00&note=Sam%20-%20Rent | |\n"
                + "Venmo: https://venmo.com/?txn=pay&recipients=Chapter&amount=20.00&note=Sam%20-%20Rent\nZelle: pay@chapter.org", filled);
    }

    @Test
    void reminderTemplatesResolveTheVenmoPlaceholder() throws Exception {
        Store store = store("sms.json", "{\"members\":[" + MEMBERS + "],\"residents\":["
                + "{\"id\":\"r1\",\"name\":\"Sam Ortiz\",\"phoneNumber\":\"1\",\"room\":\"102\",\"monthlyRent\":500,\"balanceDue\":350,\"paidThisMonth\":0}"
                + "],\"settings\":{\"ihqBillAmount\":0,\"smsTemplate\":\"Hi {name}, pay {{venmo_link}}\",\"rentSmsTemplate\":\"Rent: {venmo_link}\","
                + "\"chapterCashServiceHandles\":[{\"service\":\"VENMO\",\"handle\":\"@House\"}]}}");
        SmsService sms = new SmsService(store);
        assertEquals("Hi Marcus Vance, pay https://venmo.com/?txn=pay&recipients=House&amount=100.00&note=Marcus%20Vance%20-%20Dues",
                sms.formatMessage(store.findMemberById("m1").orElseThrow()), "nothing past due yet, so the link asks for the whole balance");
        assertEquals("Rent: https://venmo.com/?txn=pay&recipients=House&amount=350.00&note=Sam%20Ortiz%20-%20Rent%20%28102%29",
                sms.formatRentMessage(store.findResidentById("r1").orElseThrow()));
    }

    @Test
    void paymentHandlesAndEmailsAreValidatedWhenSaved() throws Exception {
        Store store = store("handles.json", "{\"members\":[" + MEMBERS + "],\"settings\":{\"ihqBillAmount\":0}}");
        DashboardController controller = new DashboardController(store, new SmsService(store));
        RedirectAttributesModelMap ok = new RedirectAttributesModelMap();
        controller.updatePaymentLinks(List.of("VENMO", "ZELLE", "PAYPAL"), List.of("@Chapter", "pay@chapter.org", ""), ok);
        assertEquals(2, store.getSettings().getChapterCashServiceHandles().size(), "blank rows are dropped");
        RedirectAttributesModelMap bad = new RedirectAttributesModelMap();
        controller.updatePaymentLinks(List.of("VENMO"), List.of("has spaces"), bad);
        assertTrue(bad.getFlashAttributes().get("toast").toString().startsWith("Check payment handle 1"));

        assertTrue(DashboardController.validEmail(""));
        assertTrue(DashboardController.validEmail("sam@example.edu"));
        assertFalse(DashboardController.validEmail("sam@example"));
        assertFalse(DashboardController.validEmail("a@b.c, d@e.f"));
    }

    @Test
    void savedPaymentHandlesReloadAndSnapshotsStillWork() throws Exception {
        Store store = store("handles-reload.json", "{\"members\":[" + MEMBERS + "],\"settings\":{\"ihqBillAmount\":0}}");
        store.getSettings().setChapterCashServiceHandles(List.of(new PaymentLinks.Handle(PaymentLinks.VENMO, "@Chapter")));
        store.save();
        assertFalse(Files.readString(tempDir.resolve("handles-reload.json")).contains("serviceName"), "display-only names are not saved");

        Store reloaded = new Store();
        ReflectionTestUtils.setField(reloaded, "DATA_FILE", tempDir.resolve("handles-reload.json").toString());
        ReflectionTestUtils.setField(reloaded, "smsDailyCap", 100);
        reloaded.init();
        assertEquals("@Chapter", reloaded.getSettings().getChapterCashServiceHandles().getFirst().getHandle());
        // A save written while serviceName was still stored must load too.
        Files.writeString(tempDir.resolve("legacy-handles.json"), "{\"members\":[],\"settings\":{\"ihqBillAmount\":0,"
                + "\"chapterCashServiceHandles\":[{\"service\":\"VENMO\",\"handle\":\"@Chapter\",\"serviceName\":\"Venmo\"}]}}");
        Store legacy = store("legacy-handles.json", Files.readString(tempDir.resolve("legacy-handles.json")));
        assertEquals("Venmo", legacy.getSettings().getChapterCashServiceHandles().getFirst().getServiceName());
        // The rollover deep-copies the data, which is exactly where the bug surfaced.
        assertEquals(0, legacy.startNewSemester(new Store.RolloverRequest("Spring", List.of(), true, true, false), TODAY).archived());
    }

    // ---- 7. Payment plan agreement ----

    @Test
    void agreementListsPartiesScheduleTermsAndSignatureLines() throws Exception {
        Member member = new Member("m1", "Marcus Vance", "5550001", 300, 0, 0, "Payment Plan", "Never", false);
        member.setEmail("marcus@example.edu");
        PaymentInstallment first = new PaymentInstallment("i1", "2026-11-01", "Installment 1", 150);
        first.setPaidAmount(150);
        member.setInstallments(new java.util.ArrayList<>(List.of(first, new PaymentInstallment("i2", "2026-12-01", "Installment 2", 150))));
        AppSettings settings = new AppSettings();
        settings.setChapterName("Alpha Beta Chapter");
        settings.setLateFeesEnabled(true);
        settings.setLateFeeAmount(25);
        settings.setLateFeeGraceDays(7);

        byte[] pdf = new PaymentPlanAgreementService().agreementPdf(member, settings, TODAY);
        String text;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(document);
        }
        assertTrue(text.contains("Payment Plan Agreement"));
        assertTrue(text.contains("Alpha Beta Chapter"));
        assertTrue(text.contains("Marcus Vance") && text.contains("marcus@example.edu") && text.contains("5550001"));
        assertTrue(text.contains("$300.00") && text.contains("$150.00"));
        assertTrue(text.contains("Nov 1, 2026") && text.contains("Dec 1, 2026"));
        assertTrue(text.contains("late fee of $25.00") && text.contains("7 days"));
        assertTrue(text.contains("Member signature") && text.contains("Treasurer signature"));

        Member noPlan = new Member("m2", "Grant", "1", 0, 0, 0, "Paid in Full", "Never", false);
        assertThrows(IllegalArgumentException.class, () -> new PaymentPlanAgreementService().agreementPdf(noPlan, settings, TODAY));
    }

    // ---- 8. Semester rollover ----

    @Test
    void rolloverArchivesPaidUpMembersConsolidatesChargesAndKeepsHistory() throws Exception {
        Store store = store("rollover.json", """
                {"members":[
                 {"id":"m1","name":"Marcus Vance","phoneNumber":"1","totalOwed":120,"pastDueBalance":50,"paidThisMonth":0,"status":"Delinquent","lastTextedDate":"Never","muteReminders":false,
                  "charges":[{"id":"c1","date":"2026-09-01","description":"Fall dues","amount":100,"pastDueAmount":50,"paidAmount":30},
                             {"id":"c2","date":"2026-09-15","description":"Formal","amount":50,"pastDueAmount":0,"paidAmount":0},
                             {"id":"c3","date":"2026-08-01","description":"Paid off","amount":20,"pastDueAmount":0,"paidAmount":20}],
                  "clearedUntilDate":"2026-10-01"},
                 {"id":"m2","name":"Grant Hayes","phoneNumber":"2","totalOwed":0,"pastDueBalance":0,"paidThisMonth":0,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false,
                  "paymentHistory":[{"date":"2026-09-10","amount":900,"description":"Fall dues","idempotencyKey":"k1"}]},
                 {"id":"m3","name":"Owes Money","phoneNumber":"3","totalOwed":40,"pastDueBalance":0,"paidThisMonth":0,"status":"Payment Plan","lastTextedDate":"Never","muteReminders":false,
                  "clearedUntilDate":"2027-01-01"}],
                 "chairBudgets":[{"id":"b1","chairName":"Rush","budgetCap":500,"reimbursements":[{"id":"x","date":"2026-09-02","description":"Shirts","amount":120}]}],
                 "settings":{"ihqBillAmount":0,"currentTermName":"Fall 2026"}}""");

        Store.RolloverResult result = store.startNewSemester(new Store.RolloverRequest("Spring 2027", List.of("m2", "m3"), true, true, true), TODAY);

        assertEquals(1, result.archived());
        assertEquals(1, result.skippedWithBalance(), "m3 still owes $40, so stays on the roster");
        assertEquals(List.of("Marcus Vance", "Owes Money"), store.getMembers().stream().map(Member::getName).toList());
        assertEquals(List.of("Grant Hayes"), store.getArchivedMembers().stream().map(Member::getName).toList());

        Member marcus = store.findMemberById("m1").orElseThrow();
        assertEquals(120, marcus.getTotalOwed(), "the balance is unchanged");
        assertEquals(1, result.consolidatedMembers());
        assertEquals(120, result.consolidatedTotal());
        ChargeRecord carryover = marcus.getCharges().stream().filter(c -> c.getDescription().equals(Store.CARRYOVER_DESCRIPTION)).findFirst().orElseThrow();
        assertEquals(120, carryover.getAmount(), "70 left on Fall dues + 50 Formal");
        assertEquals(50, carryover.getPastDueAmount());
        assertEquals(List.of("Paid off", Store.CARRYOVER_DESCRIPTION), marcus.getCharges().stream().map(ChargeRecord::getDescription).toList(),
                "paid charges stay as history");

        assertEquals(1, result.clearedVouches());
        assertNull(marcus.getClearedUntilDate());
        assertEquals("2027-01-01", store.findMemberById("m3").orElseThrow().getClearedUntilDate(), "a vouch still in force stays");

        assertEquals(1, result.budgetsReset());
        assertTrue(store.getChairBudgets().getFirst().getReimbursements().isEmpty());
        assertEquals(500, store.getChairBudgets().getFirst().getBudgetCap(), "the cap stays");
        assertEquals("Fall 2026", store.getChairBudgetHistory().getFirst().getTermName());
        assertEquals(120, store.getChairBudgetHistory().getFirst().getSpent());

        assertEquals("Spring 2027", store.getSettings().getCurrentTermName());
        assertEquals(TODAY.toString(), store.getSettings().getLastRolloverDate());
        assertTrue(Files.isRegularFile(result.backupFile()), "a copy of the data was saved first");
        assertTrue(Files.readString(result.backupFile()).contains("Grant Hayes"));
        assertTrue(new FlowReportService(store).allRows().stream().anyMatch(row -> row.party().equals("Grant Hayes")),
                "an archived member's payments still show in Cash Flow");
    }

    @Test
    void rolloverSurvivesReloadAndRejectsAnOverlongTermName() throws Exception {
        Store store = store("rollover2.json", "{\"members\":[" + MEMBERS + "],\"settings\":{\"ihqBillAmount\":0}}");
        assertThrows(IllegalArgumentException.class,
                () -> store.startNewSemester(new Store.RolloverRequest("x".repeat(61), List.of(), false, false, false), TODAY));
        store.startNewSemester(new Store.RolloverRequest("Spring", List.of("m2"), false, false, false), TODAY);

        Store reloaded = new Store();
        ReflectionTestUtils.setField(reloaded, "DATA_FILE", tempDir.resolve("rollover2.json").toString());
        ReflectionTestUtils.setField(reloaded, "smsDailyCap", 100);
        reloaded.init();
        assertEquals(1, reloaded.getArchivedMembers().size());
        assertEquals(1, reloaded.getMembers().size());
    }
}
