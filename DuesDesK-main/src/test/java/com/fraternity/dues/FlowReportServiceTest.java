package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

class FlowReportServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @Test
    void presetsResolveToCalendarRanges() {
        assertRange("this-month", "2026-10-01", "2026-10-31");
        assertRange("last-month", "2026-09-01", "2026-09-30");
        assertRange("last-30", "2026-09-25", "2026-10-24");
        assertRange("last-90", "2026-07-27", "2026-10-24");
        assertRange("ytd", "2026-01-01", "2026-10-24");
        assertRange("last-year", "2025-01-01", "2025-12-31");

        FlowReportService.Period all = FlowReportService.resolvePeriod(null, null, null, TODAY);
        assertEquals("all", all.key());
        assertFalse(all.isBounded());
        assertEquals("all", FlowReportService.resolvePeriod("bogus", "2026-01-01", null, TODAY).key());
    }

    @Test
    void customRangeSwapsReversedDatesIgnoresBadOnesAndFallsBackToAllTime() {
        FlowReportService.Period reversed = FlowReportService.resolvePeriod("custom", "2026-10-31", "2026-10-01", TODAY);
        assertEquals(LocalDate.of(2026, 10, 1), reversed.from());
        assertEquals(LocalDate.of(2026, 10, 31), reversed.to());
        assertEquals("Custom range (Oct 1, 2026 to Oct 31, 2026)", reversed.getDescription());

        FlowReportService.Period openEnded = FlowReportService.resolvePeriod("custom", "2026-10-05", "not-a-date", TODAY);
        assertEquals(LocalDate.of(2026, 10, 5), openEnded.from());
        assertEquals(null, openEnded.to());
        assertEquals("from Oct 5, 2026", openEnded.getRange());

        assertEquals("all", FlowReportService.resolvePeriod("custom", "", "", TODAY).key());
    }

    @Test
    void rowsCoverPaymentsAndUnreconciledEntriesFilteredToThePeriod() {
        FlowReportService service = new FlowReportService(store());

        List<FlowReportService.FlowRow> all = service.allRows();
        // Reconciled bank row is left out (it already shows as the member payment it became).
        assertEquals(5, all.size());
        assertEquals("2026-10-20", all.getFirst().date());

        FlowReportService.Period october = FlowReportService.resolvePeriod("this-month", null, null, TODAY);
        List<FlowReportService.FlowRow> rows = service.filter(all, october);
        // The September payment and the undated row drop out; the month-only "2026-10" row counts for October.
        assertEquals(3, rows.size());
        assertTrue(rows.stream().anyMatch(row -> "2026-10".equals(row.date())));
        assertFalse(rows.stream().anyMatch(row -> row.date().isEmpty()));

        FlowReportService.Totals totals = FlowReportService.totals(rows);
        assertEquals(170, totals.inflow());
        assertEquals(86.4, totals.outflow(), 0.0001);
        assertEquals(83.6, totals.net(), 0.0001);
        assertEquals(3, totals.count());
    }

    @Test
    void csvIsSpreadsheetReadyAndGuardsAgainstFormulas() {
        FlowReportService service = new FlowReportService(store());
        String csv = new String(service.csv(FlowReportService.resolvePeriod("this-month", null, null, TODAY)), StandardCharsets.UTF_8);

        assertTrue(csv.startsWith("﻿Date,Type,Description,Name,Source,Amount\r\n"));
        assertTrue(csv.contains("\"2026-10-20\",\"Food & beverages\",\"Pizza, drinks\",\"Tony's\",\"Manual entry\",-86.40\r\n"));
        // A description starting with "=" is defused so a spreadsheet won't evaluate it.
        assertTrue(csv.contains("\"'=HYPERLINK(1)\""));
        assertEquals(4, csv.split("\r\n").length);
    }

    @Test
    void pdfHasTitleTotalsAndPagesForLongTables() throws Exception {
        Store store = mock(Store.class);
        List<PaymentRecord> payments = new ArrayList<>();
        for (int i = 1; i <= 60; i++) payments.add(new PaymentRecord("2026-10-" + String.format("%02d", (i % 28) + 1), 10, "Dues " + i));
        Member member = new Member("m1", "Jordan Hayes", "555", 0, 0, 0, "Paid in Full", "Never", false);
        member.setPaymentHistory(payments);
        when(store.getMembers()).thenReturn(List.of(member));
        when(store.getFlowEntries()).thenReturn(List.of());

        byte[] pdf = new FlowReportService(store).pdf(FlowReportService.resolvePeriod("this-month", null, null, TODAY));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertEquals(3, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Cash Flow Report"));
            assertTrue(text.contains("This month (Oct 1, 2026 to Oct 31, 2026)"));
            assertTrue(text.contains("$600.00"));
            assertTrue(text.contains("Page 3 of 3"));
        }

        byte[] empty = new FlowReportService(store).pdf(FlowReportService.resolvePeriod("last-year", null, null, TODAY));
        try (PDDocument document = Loader.loadPDF(empty)) {
            assertTrue(new PDFTextStripper().getText(document).contains("No transactions in this period."));
        }
    }

    @Test
    void fileNamesDescribeThePeriod() {
        assertEquals("DuesDesk-inflow-outflow-2026-10-01-to-2026-10-31.csv",
                FlowReportService.fileName(FlowReportService.resolvePeriod("this-month", null, null, TODAY), "csv", TODAY));
        assertEquals("DuesDesk-inflow-outflow-all-time-2026-10-24.pdf",
                FlowReportService.fileName(FlowReportService.resolvePeriod("all", null, null, TODAY), "pdf", TODAY));
        assertEquals("DuesDesk-inflow-outflow-2026-10-05-to-present.csv",
                FlowReportService.fileName(FlowReportService.resolvePeriod("custom", "2026-10-05", null, TODAY), "csv", TODAY));
    }

    @Test
    void pageAndExportsFollowTheSelectedPeriod() throws Exception {
        Store store = store();
        DashboardController controller = new DashboardController(store, new SmsService(store));
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("flow", controller.flow("custom", "2026-10-01", "2026-10-15", model));
        FlowReportService.Period period = (FlowReportService.Period) model.getAttribute("period");
        assertEquals("custom", period.key());
        // The Oct 3 payment, plus the month-only "2026-10" import, which overlaps Oct 1-15.
        assertEquals(2, ((List<?>) model.getAttribute("rows")).size());
        assertEquals(true, model.getAttribute("hasAnyTransactions"));
        assertEquals(170.0, model.getAttribute("totalInflow"));

        var csv = controller.exportFlowCsv("custom", "2026-10-01", "2026-10-15", null, null);
        assertEquals("attachment; filename=\"DuesDesk-inflow-outflow-2026-10-01-to-2026-10-15.csv\"",
                csv.getHeaders().getFirst("Content-Disposition"));
        assertTrue(csv.getHeaders().getContentType().toString().startsWith("text/csv"));
        var pdf = controller.exportFlowPdf("all", null, null, null, null);
        assertEquals("application/pdf", pdf.getHeaders().getContentType().toString());
        assertTrue(new String(pdf.getBody(), 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF"));
    }

    private static void assertRange(String key, String from, String to) {
        FlowReportService.Period period = FlowReportService.resolvePeriod(key, null, null, TODAY);
        assertEquals(key, period.key());
        assertEquals(LocalDate.parse(from), period.from());
        assertEquals(LocalDate.parse(to), period.to());
    }

    /** Two dues payments (Oct + Sep), a manual outflow, a month-only import, an undated import and a reconciled row. */
    private static Store store() {
        Store store = mock(Store.class);
        Member member = new Member("m1", "Jordan Hayes", "555", 0, 0, 0, "Paid in Full", "Never", false);
        member.setPaymentHistory(List.of(
                new PaymentRecord("2026-10-03", 120, "October dues"),
                new PaymentRecord("2026-09-15", 75, "September dues")));
        FlowEntry pizza = new FlowEntry("f1", "2026-10-20", "Pizza, drinks", "Tony's", -86.4, "Manual entry");
        pizza.setCategory("Food & beverages");
        pizza.setReconciliationStatus("MANUAL");
        FlowEntry monthOnly = new FlowEntry("f2", "2026-10", "=HYPERLINK(1)", "Old import", 50, "BANK");
        FlowEntry undated = new FlowEntry("f3", "", "Mystery", "Old import", 10, "BANK");
        FlowEntry reconciled = new FlowEntry("f4", "2026-10-03", "Matched deposit", "Jordan", 120, "BANK");
        reconciled.setReconciliationStatus("RECONCILED");
        when(store.getMembers()).thenReturn(List.of(member));
        when(store.getFlowEntries()).thenReturn(List.of(pizza, monthOnly, undated, reconciled));
        return store;
    }
}
