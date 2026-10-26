package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** The Charges page: its ledger, and charges applied from it landing on the member's dues account. */
class ChargesPageTest {
    @TempDir
    Path tempDir;

    private Store store() throws Exception {
        Path file = tempDir.resolve("store.json");
        Files.writeString(file, """
                {"members":[
                {"id":"m1","name":"Marcus Vance","phoneNumber":"5550001","totalOwed":100,"pastDueBalance":0,"paidThisMonth":0,"status":"Payment Plan","lastTextedDate":"Never","muteReminders":false,
                 "charges":[{"id":"c1","date":"2026-01-05","description":"Fall dues","amount":100,"pastDueAmount":0,"dueDate":"2099-01-15","paidAmount":0}]},
                {"id":"m2","name":"Grant Hayes","phoneNumber":"5550002","totalOwed":0,"pastDueBalance":0,"paidThisMonth":0,"status":"Paid in Full","lastTextedDate":"Never","muteReminders":false,"membershipStatus":"Alumni"}
                ],"settings":{"ihqBillAmount":0}}""");
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }

    private static String render(ExtendedModelMap model) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        model.addAttribute("appearanceMode", "light");
        var servletContext = new org.springframework.mock.web.MockServletContext();
        var exchange = org.thymeleaf.web.servlet.JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new org.springframework.mock.web.MockHttpServletRequest(servletContext),
                        new org.springframework.mock.web.MockHttpServletResponse());
        return engine.process("charges", new WebContext(exchange, java.util.Locale.ENGLISH, model));
    }

    @Test
    void thePageListsEveryChargeAndHoldsTheToolsThatApplyThem() throws Exception {
        Store store = store();
        ExtendedModelMap model = new ExtendedModelMap();
        model.addAttribute("greekbill", new GreekBillTemplateService(store).build(
                GreekBillTemplateService.resolvePeriod("last-30", "", "", LocalDate.now(), ""), LocalDate.now()));
        model.addAttribute("greekbillPeriods", GreekBillTemplateService.PERIODS);
        model.addAttribute("greekbillTitle", GreekBillTemplateService.TITLE);
        model.addAttribute("greekbillAction", "/charges");
        assertEquals("charges", new ChargesController(store).charges("", model));

        List<ChargesController.ChargeRow> rows = (List<ChargesController.ChargeRow>) model.getAttribute("chargeRows");
        assertEquals(1, rows.size());
        assertEquals("Upcoming", rows.getFirst().status());
        assertEquals(100.0, model.getAttribute("outstandingOnCharges"));

        String html = render(model);
        assertTrue(html.contains("id=\"singleChargeForm\""), "one-member form");
        assertTrue(html.contains("id=\"batchChargeForm\""), "several-members form moved from the Dues page");
        assertTrue(html.contains("id=\"memberAdjustmentForm\""), "fine or discount form moved from the Dues page");
        assertTrue(html.contains("Template for Greekbill charges"));
        assertTrue(html.contains("id=\"greekbill\""), "the template card is on this page");
        assertTrue(html.contains("Fall dues"));
        assertTrue(html.contains("data-roster=\"Alumni\""), "the member picker knows each roster");
    }

    @Test
    void aChargeAppliedOnThisPageIsAddedToThatMembersDuesAccount() throws Exception {
        Store store = store();
        DashboardController controller = new DashboardController(store, new SmsService(store));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String result = controller.applyCharge("m2", "Formal fee", 75, LocalDate.now().plusDays(7).toString(), false,
                "/charges?member=m2", redirect);
        assertEquals("redirect:/charges?member=m2", result);
        Member grant = store.findMemberById("m2").orElseThrow();
        assertEquals(75.0, grant.getTotalOwed());
        assertEquals(List.of("Formal fee"), grant.getCharges().stream().map(ChargeRecord::getDescription).toList());
        assertEquals("Added charge to Grant Hayes's account.", redirect.getFlashAttributes().get("toast"));

        // Several members at once, and a removal, both come back to the Charges page.
        assertEquals("redirect:/charges", controller.addBatchCharges(List.of("m1", "m2"), "Shirt", 20,
                LocalDate.now().plusDays(3).toString(), "", new RedirectAttributesModelMap()));
        assertEquals(120.0, store.findMemberById("m1").orElseThrow().getTotalOwed());
        assertEquals("redirect:/charges", controller.removeCharge("m1", "c1", "/charges", new RedirectAttributesModelMap()));
        assertEquals(20.0, store.findMemberById("m1").orElseThrow().getTotalOwed());

        // No member chosen: nothing is charged.
        RedirectAttributesModelMap missing = new RedirectAttributesModelMap();
        controller.applyCharge("", "Formal fee", 75, "", false, "/charges", missing);
        assertEquals("Choose the member to charge.", missing.getFlashAttributes().get("toast"));
        assertFalse(store.getActivityLog().isEmpty(), "every charge is in the activity log");
        // Never redirects off the site.
        assertEquals("redirect:/charges", controller.applyCharge("m1", "Fee", 5, "", false, "https://example.com", new RedirectAttributesModelMap()));
    }
}
