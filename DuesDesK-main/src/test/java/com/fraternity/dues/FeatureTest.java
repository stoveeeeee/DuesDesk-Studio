package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;

/** Server-side parts of the audit features: copyable summaries and export refinement by quick filter/search. */
class FeatureTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @TempDir
    Path tempDir;

    @Test
    void duesSummaryListsTotalsIhqProgressAndPastDueMembersLargestFirst() throws Exception {
        Path file = tempDir.resolve("summary.json");
        Files.writeString(file, """
                {"members":[
                 {"id":"m1","name":"Marcus Vance","phoneNumber":"555","totalOwed":850,"pastDueBalance":250,"paidThisMonth":150,"status":"Delinquent","lastTextedDate":"Never","muteReminders":false},
                 {"id":"m2","name":"Grant Hayes","phoneNumber":"555","totalOwed":0,"pastDueBalance":0,"paidThisMonth":900,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false,
                  "paymentHistory":[{"date":"2026-10-01","amount":900,"description":"Dues","idempotencyKey":"k1"}]},
                 {"id":"m3","name":"Cole Henderson","phoneNumber":"555","totalOwed":600,"pastDueBalance":200,"paidThisMonth":250,"status":"Delinquent","lastTextedDate":"Never","muteReminders":false}],
                 "settings":{"ihqBillAmount":1000}}""");
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        ExtendedModelMap model = new ExtendedModelMap();

        new DashboardController(store, new SmsService(store)).dashboard(1, null, model);
        String summary = (String) model.getAttribute("duesSummary");

        assertTrue(summary.startsWith("DuesDesk dues summary ("));
        assertTrue(summary.contains("Collected this month: $1,300.00"));
        assertTrue(summary.contains("Outstanding balance: $1,450.00 across 2 members"));
        assertTrue(summary.contains("Past due: $450.00 from 2 members"));
        assertTrue(summary.contains("Collection rate: 47.3%"));
        assertTrue(summary.contains("IHQ bill: $900.00 of $1,000.00 paid (90.0%)"));
        assertTrue(summary.indexOf("- Marcus Vance: $250.00") < summary.indexOf("- Cole Henderson: $200.00"));
    }

    @Test
    void quickFiltersAndSearchNarrowRowsTheWayThePageDoes() {
        List<FlowReportService.FlowRow> rows = new FlowReportService(store()).allRows();

        assertEquals(List.of("Pizza, drinks"), descriptions(FlowReportService.refine(rows, "out", null)));
        assertEquals(2, FlowReportService.refine(rows, "dues", null).size());
        assertEquals(List.of("Pizza, drinks"), descriptions(FlowReportService.refine(rows, null, "TONY")));
        assertEquals(List.of("October dues"), descriptions(FlowReportService.refine(rows, "in", "october")));
        assertEquals(rows, FlowReportService.refine(rows, "all", " "));
        assertEquals("Money out, matching \"pizza\"", FlowReportService.refinementLabel("out", " pizza "));
        assertEquals("", FlowReportService.refinementLabel(null, null));
    }

    @Test
    void flowSummaryReportsTotalsAndBreaksMoneyDownByType() {
        FlowReportService.Period october = FlowReportService.resolvePeriod("this-month", null, null, TODAY);
        List<FlowReportService.FlowRow> rows = new FlowReportService(store()).rows(october);

        String summary = FlowReportService.summary(october, rows);

        assertTrue(summary.startsWith("DuesDesk cash flow: This month (Oct 1, 2026 to Oct 31, 2026)"));
        assertTrue(summary.contains("Money in: $170.00 (2 transactions)"));
        assertTrue(summary.contains("Money out: $86.40 (1 transaction)"));
        assertTrue(summary.contains("Net: +$83.60"));
        assertTrue(summary.contains("Money in by type:\n- Dues payment: $120.00\n- Inflow: $50.00"));
        assertTrue(summary.contains("Money out by type:\n- Food & beverages: $86.40"));
    }

    @Test
    void exportsCanBeNarrowedToWhatTheTableShows() throws Exception {
        FlowReportService service = new FlowReportService(store());
        FlowReportService.Period all = FlowReportService.resolvePeriod("all", null, null, TODAY);

        String csv = new String(service.csv(all, "out", null), StandardCharsets.UTF_8);
        assertEquals(2, csv.split("\r\n").length);
        assertTrue(csv.contains("Pizza, drinks"));

        try (PDDocument document = Loader.loadPDF(service.pdf(all, "dues", "october"))) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("All time - Dues payments, matching \"october\""));
            assertTrue(text.contains("October dues"));
            assertTrue(!text.contains("September dues"));
        }
    }

    private static List<String> descriptions(List<FlowReportService.FlowRow> rows) {
        return rows.stream().map(FlowReportService.FlowRow::description).toList();
    }

    /** Two dues payments (Oct + Sep), a manual outflow, and a month-only bank import. */
    private static Store store() {
        Store store = mock(Store.class);
        Member member = new Member("m1", "Jordan Hayes", "555", 0, 0, 0, "Paid in Full", "Never", false);
        member.setPaymentHistory(List.of(
                new PaymentRecord("2026-10-03", 120, "October dues"),
                new PaymentRecord("2026-09-15", 75, "September dues")));
        FlowEntry pizza = new FlowEntry("f1", "2026-10-20", "Pizza, drinks", "Tony's", -86.4, "Manual entry");
        pizza.setCategory("Food & beverages");
        pizza.setReconciliationStatus("MANUAL");
        FlowEntry monthOnly = new FlowEntry("f2", "2026-10", "Old deposit", "Old import", 50, "BANK");
        when(store.getMembers()).thenReturn(List.of(member));
        when(store.getFlowEntries()).thenReturn(List.of(pizza, monthOnly));
        return store;
    }
}
