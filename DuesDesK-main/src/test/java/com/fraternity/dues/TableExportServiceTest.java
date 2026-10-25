package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ExtendedModelMap;

/** The full-screen table PDF export and the Chair Budgeting page that moved off Finance Tools. */
class TableExportServiceTest {
    private final TableExportService service = new TableExportService();

    private static TableExportService.TableData table(List<List<String>> rows) {
        return new TableExportService.TableData("Member Dues Ledger", "Filter: Past Due",
                List.of("Member", "Total Owed", "Status"), rows, List.of(false, true, false));
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void pdfShowsTitleNotesHeadersAndEveryRow() throws Exception {
        String text = text(service.pdf(table(List.of(
                List.of("Marcus Vance · 5551110001", "$850.00", "Past due"),
                List.of("Cole Henderson", "$600.00", "Past due")))));

        assertTrue(text.contains("Member Dues Ledger"));
        assertTrue(text.contains("2 rows"));
        assertTrue(text.contains("Filter: Past Due"));
        assertTrue(text.contains("MEMBER") && text.contains("TOTAL OWED") && text.contains("STATUS"));
        assertTrue(text.contains("Marcus Vance - 5551110001"));
        assertTrue(text.contains("$600.00"));
        assertTrue(text.contains("Page 1 of 1"));
    }

    @Test
    void pdfOfAnEmptyTableSaysSoAndLongTablesSpanPages() throws Exception {
        assertTrue(text(service.pdf(table(List.of()))).contains("No rows to show."));

        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) rows.add(List.of("Member " + i, "$" + i + ".00", "Paid"));
        String text = text(service.pdf(table(rows)));
        assertTrue(text.contains("60 rows"));
        assertTrue(text.contains("Member 59"));
        assertTrue(text.contains("Page 3 of 3"), "60 rows at 18pt need three landscape pages (24 + 27 + 9)");
    }

    @Test
    void shortRowsArePaddedNonAsciiIsSafeAndOversizedTablesAreRefused() throws Exception {
        TableExportService.TableData padded = TableExportService.validate(table(List.of(List.of("Only a name"))));
        assertEquals(List.of("Only a name", "", ""), padded.rows().get(0));
        assertTrue(text(service.pdf(table(List.of(List.of("José Núñez – “VP”", "$1.00", "Paid"))))).contains("Jose Nunez"));

        assertThrows(IllegalArgumentException.class, () -> service.pdf(new TableExportService.TableData("T", "", List.of(), List.of(), null)));
        List<String> wide = new ArrayList<>();
        for (int i = 0; i <= TableExportService.MAX_COLUMNS; i++) wide.add("C" + i);
        assertThrows(IllegalArgumentException.class, () -> service.pdf(new TableExportService.TableData("T", "", wide, List.of(), null)));
        List<List<String>> tooMany = new ArrayList<>();
        for (int i = 0; i <= TableExportService.MAX_ROWS; i++) tooMany.add(List.of("x", "y", "z"));
        assertThrows(IllegalArgumentException.class, () -> service.pdf(table(tooMany)));
    }

    @Test
    void fileNamesAreSlugsWithTheDate() {
        assertEquals("DuesDesk-member-dues-ledger-2026-10-24.pdf", TableExportService.fileName("Member Dues Ledger", LocalDate.of(2026, 10, 24)));
        assertEquals("DuesDesk-table-2026-10-24.pdf", TableExportService.fileName(" !! ", LocalDate.of(2026, 10, 24)));
    }

    @Test
    void exportEndpointReturnsAPdfDownloadOrAClearError() throws Exception {
        Store store = mock(Store.class);
        DashboardController controller = new DashboardController(store, new SmsService(store));

        ResponseEntity<?> ok = controller.exportTablePdf(table(List.of(List.of("Grant Hayes", "$0.00", "Paid"))));
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertTrue(ok.getHeaders().getFirst("Content-Disposition").startsWith("attachment; filename=\"DuesDesk-member-dues-ledger-"));
        assertTrue(text((byte[]) ok.getBody()).contains("Grant Hayes"));

        ResponseEntity<?> bad = controller.exportTablePdf(new TableExportService.TableData("T", "", List.of(), List.of(), null));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        assertEquals("The table has no columns to export.", bad.getBody());
    }

    @Test
    void chairBudgetingPageCarriesTheBudgetsFinanceToolsNoLongerDoes() {
        Store store = mock(Store.class);
        ChairBudget budget = new ChairBudget("b1", "Rush", 500);
        when(store.getChairBudgets()).thenReturn(List.of(budget));
        when(store.getMembers()).thenReturn(List.of());
        when(store.getSettings()).thenReturn(new AppSettings());
        DashboardController controller = new DashboardController(store, new SmsService(store));
        ExtendedModelMap model = new ExtendedModelMap();

        assertEquals("chair-budgeting", controller.chairBudgeting(model));
        assertEquals(List.of(budget), model.getAttribute("budgets"));
        assertTrue(model.containsAttribute("budgetDetails"));
        assertEquals("finance-tools", controller.financeTools(new ExtendedModelMap()));
    }
}
