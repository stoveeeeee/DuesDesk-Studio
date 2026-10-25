package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class FinancialOverviewTemplateTest {
    private final SpringTemplateEngine templateEngine;

    FinancialOverviewTemplateTest() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
    }

    @Test
    void cashCardsRenderAboveTheCalculatorWithValuesAndAccuracyGuidance() {
        Member member = new Member("m1", "Alex Member", "555", 0, 0, 0, "Payment Plan", "Never", false);
        member.setPaymentHistory(List.of(new PaymentRecord("2026-10-01", 100, "Dues")));
        member.setInstallments(List.of(
                new PaymentInstallment("week", LocalDate.now().plusDays(1).toString(), "Weekly dues", 50),
                new PaymentInstallment("later", LocalDate.now().plusDays(30).toString(), "Later dues", 60)));
        FlowEntry pending = new FlowEntry("pending", "2026-10-20", "Deposit", "Alex", 10, "Bank");
        pending.setReconciliationStatus("PENDING");
        AppSettings settings = new AppSettings();
        settings.setOpeningCashBalance(1000);
        Store store = mock(Store.class);
        when(store.getMembers()).thenReturn(List.of(member));
        when(store.getArchivedMembers()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of(pending));
        when(store.getRecurringBills()).thenReturn(List.of());
        when(store.getSettings()).thenReturn(settings);
        ExtendedModelMap model = new ExtendedModelMap();

        new DashboardController(store, new SmsService(store)).numbers(null, model);
        verify(store).processRecurringBills(any(LocalDate.class));
        FinancialOverviewService.FinancialSnapshot snapshot =
                (FinancialOverviewService.FinancialSnapshot) model.getAttribute("financialSnapshot");
        assertEquals(1110, snapshot.overallCash(), 0.001);
        assertEquals(1110, snapshot.spendableCash(), 0.001);
        assertEquals(50, snapshot.scheduledDuesNextWeek(), 0.001);

        new DashboardController(store, new SmsService(store)).numbers(
                1500.0, "Emergency plumbing repair", "emergency", model);
        model.addAttribute("appearanceMode", "light");
        var servletContext = new org.springframework.mock.web.MockServletContext();
        var request = new org.springframework.mock.web.MockHttpServletRequest(servletContext);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(request, response);
        WebContext context = new WebContext(exchange, java.util.Locale.ENGLISH, model);
        String html = templateEngine.process("numbers", context);

        assertTrue(html.contains("Overall cash available"));
        assertTrue(html.contains("Cash available to spend"));
        assertTrue(html.contains("Expected dues within one week"));
        assertTrue(html.contains("$1,110.00"));
        assertTrue(html.contains("$50.00"));
        assertTrue(html.contains("All transactions must be up to date for these balances to be accurate."));
        assertTrue(html.contains("input.type = 'text'"));
        assertTrue(html.contains("document.addEventListener('DOMContentLoaded', enableCommaNumberInputs)"));
        assertTrue(html.contains("Enter a number; use commas only as thousands separators"));
        assertTrue(html.contains("What is this purchase for?"));
        assertTrue(html.contains("Emergency plumbing repair"));
        assertTrue(html.contains("How urgent is it?"));
        assertTrue(html.contains("Emergency — safety or critical operations"));
        assertTrue(html.contains("Recommendation"));
        assertTrue(html.contains("data-fullscreen-target=\"calculatorResult\""));
        assertTrue(html.contains("data-fullscreen-portal=\"true\""));
        assertTrue(html.contains("View calculator result full screen"));
        assertTrue(html.contains("Full screen"));
        assertTrue(html.contains("#calculatorResult .small { font-size:.98rem;line-height:1.65; }"));
        assertTrue(html.contains(".calculator-result.is-fullscreen"));
        assertTrue(html.contains("left:.75rem;z-index:1100;overflow-y:auto;"));
        assertTrue(html.contains(".ledger-fullscreen.is-fullscreen .table-responsive { flex: 0 1 auto; min-height: 0; max-height: 100%; }"));
        assertTrue(html.contains(".table-responsive > .table { margin-bottom: 0; }"));
        assertTrue(html.contains("target.parentElement.insertBefore(portalPlaceholder, target)"));
        assertTrue(html.contains("document.body.appendChild(target)"));
        assertTrue(html.contains("portalPlaceholder.parentNode.insertBefore(target, portalPlaceholder)"));
        assertTrue(html.contains("Exit calculator result full screen"));
        assertTrue(html.contains("Do not approve the full purchase as quoted"));
        assertTrue(html.contains("Safety-critical or emergency repair"));
        assertTrue(html.contains("Minimum confirmed funding gap"));
        assertTrue(html.contains("Recommended plan to restore positive operating cash"));
        assertTrue(html.contains("Secure at least $390.00"));
        assertTrue(html.contains("If you decide to proceed anyway"));
        assertTrue(html.contains("This is a rules-based budgeting aid"));
        assertTrue(html.contains("1 transaction needs reconciliation"));
        assertTrue(html.contains("Review Cash Flow"));
        assertTrue(html.indexOf("Overall cash available") < html.indexOf("id=\"spendCalculator\""));
    }

    @Test
    void calculatorParsesCommaFormattedAmountFromRequest() {
        Member member = new Member("m1", "Alex Member", "555", 0, 0, 0, "Payment Plan", "Never", false);
        member.setPaymentHistory(List.of(new PaymentRecord("2026-10-01", 100, "Dues")));
        AppSettings settings = new AppSettings();
        settings.setOpeningCashBalance(2000);
        Store store = mock(Store.class);
        when(store.getMembers()).thenReturn(List.of(member));
        when(store.getArchivedMembers()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of());
        when(store.getRecurringBills()).thenReturn(List.of());
        when(store.getSettings()).thenReturn(settings);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = new DashboardController(store, new SmsService(store))
                .numbers("1,000", "plumbing repair", "soon", model);

        SpendingAdvisor.Advice advice = (SpendingAdvisor.Advice) model.getAttribute("advice");
        assertEquals("numbers", view);
        assertEquals(1000, advice.expense(), 0.001);
        assertEquals("plumbing repair", advice.purpose());
    }

    @Test
    void calculatorShowsValidationForMalformedCommaFormattedAmount() {
        Store store = mock(Store.class);
        when(store.getMembers()).thenReturn(List.of());
        when(store.getArchivedMembers()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of());
        when(store.getRecurringBills()).thenReturn(List.of());
        when(store.getSettings()).thenReturn(new AppSettings());
        ExtendedModelMap model = new ExtendedModelMap();

        new DashboardController(store, new SmsService(store))
                .numbers("1,00", "plumbing repair", "soon", model);

        assertEquals("1,00", model.getAttribute("expenseInput"));
        assertEquals("Enter a valid amount; use commas only as thousands separators (for example, 1,000.00).",
                model.getAttribute("expenseError"));
        assertEquals(null, model.getAttribute("advice"));
    }
}
