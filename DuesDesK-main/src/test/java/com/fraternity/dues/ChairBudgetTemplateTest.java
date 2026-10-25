package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class ChairBudgetTemplateTest {
    private final SpringTemplateEngine templateEngine;

    ChairBudgetTemplateTest() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
    }

    @Test
    void chairPageRendersUniqueManagementCardsAndNavigation() {
        ChairBudget rush = new ChairBudget("b2", "Rush", 500);
        rush.setAccentColor("#2563EB");
        ChairBudget service = new ChairBudget("b1", "Community Service", 300);
        service.setAccentColor("#0F766E");
        Store store = mock(Store.class);
        when(store.getChairBudgets()).thenReturn(List.of(rush, service));
        when(store.getMembers()).thenReturn(List.of());
        ExtendedModelMap model = new ExtendedModelMap();

        new DashboardController(store, new SmsService(store)).chairBudgeting(model);
        assertEquals(List.of(service, rush), model.getAttribute("budgets"),
                "chairs and jump-menu entries should be alphabetical");

        model.addAttribute("appearanceMode", "light");
        var servletContext = new org.springframework.mock.web.MockServletContext();
        var request = new org.springframework.mock.web.MockHttpServletRequest(servletContext);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(request, response);
        WebContext context = new WebContext(exchange, java.util.Locale.ENGLISH, model);
        String html = templateEngine.process("chair-budgeting", context);

        assertTrue(html.contains("data-chair-search"));
        assertTrue(html.contains("data-chair-jump"));
        assertTrue(html.contains("--chair-accent"));
        assertTrue(html.contains("--chair-accent-text"));
        assertTrue(html.contains("#0F766E"));
        assertTrue(html.contains(">CS</span>"));
        assertTrue(html.contains("action=\"/finance/chair-budgets/b1/edit\""));
        assertTrue(html.contains("action=\"/finance/chair-budgets/b1/delete\""));
        assertTrue(html.contains("This permanently removes its budget, charges, money raised, and receipts."));
        assertTrue(html.indexOf("budget-b1") < html.indexOf("budget-b2"));
    }
}
