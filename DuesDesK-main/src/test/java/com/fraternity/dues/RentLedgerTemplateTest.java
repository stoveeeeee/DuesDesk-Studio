package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

class RentLedgerTemplateTest {
    private final SpringTemplateEngine templateEngine;

    RentLedgerTemplateTest() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
    }

    @Test
    void rentLedgerTableEndsAtItsRowsInsteadOfStretchingToViewportHeight() {
        Store store = mock(Store.class);
        when(store.getResidents()).thenReturn(List.of(
                new Resident("r1", "Alex Resident", "555-0100", "1A", 500, 0, 0)));
        when(store.getMembers()).thenReturn(List.of());
        when(store.getSettings()).thenReturn(new AppSettings());
        when(store.getSmsRemainingToday()).thenReturn(10);
        ExtendedModelMap model = new ExtendedModelMap();
        new RentController(store, new SmsService(store)).rent(null, model);
        model.addAttribute("appearanceMode", "light");

        var servletContext = new org.springframework.mock.web.MockServletContext();
        var request = new org.springframework.mock.web.MockHttpServletRequest(servletContext);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(request, response);
        WebContext context = new WebContext(exchange, java.util.Locale.ENGLISH, model);

        String html = templateEngine.process("rent", context);

        assertTrue(html.contains("id=\"rentTable\""));
        assertTrue(html.contains("class=\"table-responsive roster-table-container\""));
        assertTrue(html.contains(".roster-table-container {\n        height: auto; min-height: 0; overflow-x: auto; overflow-y: hidden; padding-bottom: 0;"));
        assertTrue(html.contains(".ledger-fullscreen.is-fullscreen > .roster-table-container"));
        assertTrue(html.contains(".table-responsive > .table { margin-bottom: 0; }"));
        assertTrue(html.contains("#rosterTable, #rentTable { height: auto; margin-bottom: 0; }"));
        assertTrue(html.contains("#rosterTable tbody tr:only-child, #rentTable tbody tr:only-child { height: auto; }"));
        assertFalse(html.contains(".rent-ledger-card .table-responsive {\n            flex: 1 1 auto;\n            min-height: 42vh;"));
        assertFalse(html.contains(".rent-ledger-card tbody tr:only-child"));
        assertFalse(html.contains(".rent-ledger-card table {\n            height: 100%;"));
    }
}
