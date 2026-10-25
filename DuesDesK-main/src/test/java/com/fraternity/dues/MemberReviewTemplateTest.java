package com.fraternity.dues;

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

class MemberReviewTemplateTest {
    private final SpringTemplateEngine templateEngine;

    MemberReviewTemplateTest() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
    }

    @Test
    @SuppressWarnings("unchecked")
    void memberReviewOpensTheRosterFullscreenAndKeepsItsHeaderVisible() {
        Store store = mock(Store.class);
        Member member = new Member("member-1", "Alex Member", "555-000-0000",
                100, 25, 0, "Delinquent", "Never", false);
        when(store.getMembers()).thenReturn(List.of(member));
        when(store.getSettings()).thenReturn(new AppSettings());
        when(store.getSmsLogs()).thenReturn(List.of());
        when(store.getFlowEntries()).thenReturn(List.of());
        ExtendedModelMap model = new ExtendedModelMap();
        new DashboardController(store, new SmsService(store)).dashboard(1, null, model);

        List<DashboardController.AttentionItem> attention =
                (List<DashboardController.AttentionItem>) model.getAttribute("attentionItems");
        assertTrue(attention.stream().anyMatch(item -> item.href().equals("/?page=1#member-member-1")));

        model.addAttribute("appearanceMode", "light");
        var servletContext = new org.springframework.mock.web.MockServletContext();
        var request = new org.springframework.mock.web.MockHttpServletRequest(servletContext);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(request, response);
        WebContext context = new WebContext(exchange, java.util.Locale.ENGLISH, model);
        String html = templateEngine.process("index", context);

        assertTrue(html.contains("href=\"/?page=1#member-member-1\""));
        assertTrue(html.contains("data-fullscreen-target=\"duesLedgerView\""));
        assertTrue(html.contains("class=\"table-responsive roster-table-container\""));
        assertTrue(html.contains("fullscreenToggle.click()"));
        assertTrue(html.contains("tableScroller.scrollTo"));
        assertTrue(html.contains(".ledger-fullscreen.is-fullscreen .roster-table-container.headers-sticky .table-fintech thead th"), "full-screen headers stay pinned only while Headers: Sticky is on");
        assertTrue(html.contains(".roster-table-container {\n        height: auto; min-height: 0; overflow-x: auto; overflow-y: hidden; padding-bottom: 0;"));
        assertTrue(html.contains("#rosterTable, #rentTable { height: auto; margin-bottom: 0; }"));
        assertTrue(html.contains("position: sticky"));
        assertTrue(html.contains("id=\"member-member-1\""));
    }
}
