package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** Regression tests for the issues found in the full-project audit. */
class AuditFixesTest {
    @TempDir
    Path tempDir;

    @Test
    void moneyRoundsToCentsAndTreatsLeftoversAsZero() {
        assertEquals(0.0, Money.cents(1.1 - 0.9 - 0.2));
        assertEquals(1.01, Money.cents(1.005));
        assertEquals(0.0, Money.cents(-0.0000001));
        assertFalse(Money.positive(5.551115123125783E-17));
        assertTrue(Money.positive(0.01));
        assertTrue(Double.isNaN(Money.cents(Double.NaN)));
    }

    @Test
    void paidUpMemberIsPaidInFullAfterSplitPayments() throws Exception {
        Store store = store(tempDir.resolve("split.json"), """
                {"members":[{"id":"m1","name":"Split Payer","phoneNumber":"555","totalOwed":1.10,"pastDueBalance":1.10,
                "paidThisMonth":0,"status":"Delinquent","lastTextedDate":"Never","muteReminders":false}]}""");

        assertTrue(store.recordPayment("m1", new PaymentRecord("2026-10-20", 0.90, "Part 1", "k1")));
        assertTrue(store.recordPayment("m1", new PaymentRecord("2026-10-21", 0.20, "Part 2", "k2")));

        Member member = store.findMemberById("m1").orElseThrow();
        assertEquals(0.0, member.getTotalOwed());
        assertEquals(0.0, member.getPastDueBalance());
        assertEquals("Paid in Full", member.getStatus());
        assertTrue(new SmsService(store).reminderPreview().isEmpty());
    }

    @Test
    void paidUpResidentIsPaidUpAndNotQueuedForRentTexts() throws Exception {
        Store store = store(tempDir.resolve("rent.json"), "{\"members\":[]}");
        store.addResident(new Resident("r1", "Ana", "555", "1A", 500, 1.10, 0));
        RentController controller = new RentController(store, new SmsService(store));

        controller.recordRentPayment("r1", 0.90, new RedirectAttributesModelMap());
        controller.recordRentPayment("r1", 0.20, new RedirectAttributesModelMap());

        Resident resident = store.findResidentById("r1").orElseThrow();
        assertEquals(0.0, resident.getBalanceDue());
        assertEquals("Paid Up", resident.getStatus());
        ExtendedModelMap model = new ExtendedModelMap();
        controller.rent(null, model);
        assertEquals(0, model.getAttribute("reminderRecipientCount"));
        assertEquals(1L, model.getAttribute("paidUpCount"));
    }

    @Test
    void savesWrittenBeforeRoundingAreCleanedOnLoad() throws Exception {
        Store store = store(tempDir.resolve("legacy.json"), """
                {"members":[{"id":"m1","name":"Legacy","phoneNumber":"555","totalOwed":5.551115123125783E-17,
                "pastDueBalance":2.7755575615628914E-17,"paidThisMonth":1.1,"status":"Delinquent",
                "lastTextedDate":"Never","muteReminders":false}],
                "residents":[{"id":"r1","name":"Ana","phoneNumber":"555","room":"1A","monthlyRent":500,
                "balanceDue":5.551115123125783E-17,"paidThisMonth":0,"muteReminders":false}]}""");

        Member member = store.findMemberById("m1").orElseThrow();
        assertEquals(0.0, member.getTotalOwed());
        assertEquals("Paid in Full", member.getStatus());
        assertEquals("Paid Up", store.findResidentById("r1").orElseThrow().getStatus());
    }

    @Test
    void overlappingBankStatementsAreNotImportedTwice() throws Exception {
        Store store = store(tempDir.resolve("bank.json"), "{\"members\":[]}");
        String september = "Date,Description,Amount,Name\n2026-10-01,Dues deposit,100.00,Jordan\n"
                + "2026-10-02,Venue refund,$-25.00,Grand Hall\n2026-10-03,Snacks,-12.50,Costco\n";
        String october = "Date,Description,Amount,Name\n2026-10-05,Dues deposit,50.00,Alex\n"
                + "2026-10-01,Dues deposit,100.00,Jordan\n2026-10-02,Venue refund,$-25.00,Grand Hall\n"
                + "2026-10-03,Snacks,-12.50,Costco\n";

        assertEquals(3, store.importBankCsv(csv(september))[0]);
        int[] second = store.importBankCsv(csv(october));
        assertEquals(1, second[0]);
        assertEquals(3, second[1]);
        assertEquals(4, store.getFlowEntries().size());
        assertEquals(-25.0, store.getFlowEntries().stream()
                .filter(entry -> "Venue refund".equals(entry.getDescription())).findFirst().orElseThrow().getAmount());
    }

    @Test
    void identicalRowsInOneStatementStaySeparateAndOldImportsAreRecognized() throws Exception {
        Store store = store(tempDir.resolve("bank-legacy.json"), """
                {"members":[],"flowEntries":[{"id":"bank-old-row-number-scheme","date":"2026-10-01",
                "description":"Dues deposit","counterparty":"Jordan","amount":100.0,"source":"Bank",
                "reconciliationStatus":"PENDING"}]}""");
        String statement = "Date,Description,Amount,Name\n2026-10-01,Dues deposit,100.00,Jordan\n"
                + "2026-10-04,Coffee,-4.00,Cafe\n2026-10-04,Coffee,-4.00,Cafe\n";

        int[] result = store.importBankCsv(csv(statement));
        // The Jordan deposit matches the row saved under the old ID scheme; both coffees are real purchases.
        assertEquals(2, result[0]);
        assertEquals(1, result[1]);
        assertEquals(3, store.getFlowEntries().size());
    }

    @Test
    void bankAmountSignIsReadWhereverTheMinusAppears() {
        List<FlowEntry> rows = BankCsvParser.parse("Date,Description,Amount\n"
                + "2026-10-01,A,$-25.00\n2026-10-01,B,25.00-\n2026-10-01,C,(12.50)\n2026-10-01,D,USD -3.00\n"
                + "2026-10-01,E,\"$1,200.00\"\n");
        assertEquals(List.of(-25.0, -25.0, -12.5, -3.0, 1200.0), rows.stream().map(FlowEntry::getAmount).toList());
    }

    @Test
    void singleInstallmentDescriptionWithCommaIsKept() {
        Store store = mock(Store.class);
        when(store.addInstallments(eq("m2"), org.mockito.ArgumentMatchers.anyList())).thenReturn(true);
        DashboardController controller = new DashboardController(store, new SmsService(store));
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("dueDates", "2026-11-01");
        form.add("amounts", "100");
        form.add("descriptions", "Fall, part 1");
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.addInstallments("m2", form, redirect);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<PaymentInstallment>>) (Class<?>) List.class);
        verify(store).addInstallments(eq("m2"), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("Fall, part 1", captor.getValue().getFirst().getDescription());
        assertEquals("Custom payment plan installments saved.", redirect.getFlashAttributes().get("toast"));

        form.set("amounts", "abc");
        RedirectAttributesModelMap bad = new RedirectAttributesModelMap();
        controller.addInstallments("m2", form, bad);
        assertEquals("Enter a valid amount for every installment row.", bad.getFlashAttributes().get("toast"));
    }

    @Test
    void unknownPagesAreNotFoundRatherThanServerErrors() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        var notFound = handler.notFound(new org.springframework.web.servlet.resource.NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/upload"));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatus());
        assertEquals("Page not found", notFound.getModel().get("errorTitle"));
        var wrongMethod = handler.methodNotAllowed(
                new org.springframework.web.HttpRequestMethodNotSupportedException("GET"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, wrongMethod.getStatus());
    }

    @Test
    void appStartsDespiteAnUnreadableReceiptButImportsStillRejectIt() throws Exception {
        String badReceipt = "{\"members\":[],\"receiptFiles\":{\"0f8fad5b-d9cb-469f-a165-70867728950e.png\":\"bm90IGFuIGltYWdl\"}}";
        Store store = store(tempDir.resolve("receipts.json"), badReceipt);
        assertTrue(store.getMembers().isEmpty());

        store.addMember(new Member("keep", "Keep Me", "555", 10, 0, 0, "Payment Plan", "Never", false));
        assertThrows(IllegalStateException.class, () -> store.importData(badReceipt.getBytes(StandardCharsets.UTF_8)));
        assertTrue(store.findMemberById("keep").isPresent());
    }

    @Test
    void statementCsvDefusesFormulasButKeepsNegativeAmountsNumeric() {
        Member member = new Member("m1", "=Evil Name", "555", 10, 0, 0, "Payment Plan", "Never", false);
        member.setPaymentHistory(List.of(new PaymentRecord("2026-10-01", 25, "+cmd|' /C calc'!A0", "k")));
        member.getCredits().add(new MemberCredit("c1", "2026-10-02", "@credit", 5, "r1"));

        String csv = new String(new MemberDocumentService().statementCsv(member), StandardCharsets.UTF_8);

        assertTrue(csv.contains("\"'+cmd|' /C calc'!A0\""));
        assertTrue(csv.contains("\"'@credit\""));
        assertTrue(csv.contains("\"'=Evil Name\""));
        assertTrue(csv.contains("\"-5.00\""));
    }

    @Test
    void smsAmountsUseUsFormattingWhateverTheComputerRegion() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            Store store = mock(Store.class);
            AppSettings settings = new AppSettings();
            settings.setSmsTemplate("{name}: {pastDue} of {totalOwed}");
            settings.setRentSmsTemplate("{name} owes {balanceDue}");
            when(store.getSettings()).thenReturn(settings);
            SmsService sms = new SmsService(store);

            assertEquals("Jo: 250.50 of 1234.00",
                    sms.formatMessage(new Member("m", "Jo", "555", 1234, 250.5, 0, "Delinquent", "Never", false)));
            assertEquals("Ana owes 12.30", sms.formatRentMessage(new Resident("r", "Ana", "555", "1A", 500, 12.3, 0)));
            assertTrue(SpendingAdvisor.evaluate(100, 1234.5, 95, 0, 0).reasons().getFirst().contains("$1,234.50"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void requestFieldLimitCoversLargeBatchForms() throws Exception {
        java.util.Properties properties = new java.util.Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) {
            properties.load(input);
        }
        assertTrue(Integer.parseInt(properties.getProperty("server.tomcat.max-parameter-count")) >= 2000);
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "statement.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private static Store store(Path file, String json) throws Exception {
        Files.writeString(file, json);
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }
}
