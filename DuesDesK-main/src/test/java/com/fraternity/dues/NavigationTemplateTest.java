package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

class NavigationTemplateTest {
    private static final Pattern ANCHOR = Pattern.compile("<a\\s+([^>]*)>([^<]*)</a>");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");
    private static final Pattern ACTIVE_CLASS = Pattern.compile("class=\"[^\"]*\\bactive\\b[^\"]*\"");

    private final SpringTemplateEngine templateEngine;

    NavigationTemplateTest() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
    }

    @Test
    void primaryDestinationsStayDirectAndFinanceContainsOnlyFinanceWorkflows() {
        String html = renderNavigation("dues");
        List<NavLink> primaryLinks = links(html, false);
        List<NavLink> financeLinks = links(html, true);

        assertEquals(List.of("Dues", "Charges", "Rent", "Finance", "Calendar & Bills", "Events", "Import & Export", "Settings"),
                primaryLinks.stream().map(NavLink::label).toList());
        assertEquals(List.of("/", "/charges", "/rent", "#", "/calendar", "/presales", "/upload-docs", "/settings"),
                primaryLinks.stream().map(NavLink::href).toList());
        assertEquals(List.of("Financial Overview", "Cash Flow", "Chair Budgets", "Reports & Handoff", "Activity Log & Undo"),
                financeLinks.stream().map(NavLink::label).toList());
        assertEquals(List.of("/numbers", "/numbers/flow", "/chair-budgeting", "/finance-tools", "/activity"),
                financeLinks.stream().map(NavLink::href).toList());
    }

    @Test
    void everyDestinationHighlightsItsTabAndFinanceItemWhenApplicable() {
        Map<String, String> destinations = Map.of(
                "dues", "Dues",
                "rent", "Rent",
                "numbers", "Financial Overview",
                "flow", "Cash Flow",
                "chair", "Chair Budgets",
                "finance", "Reports & Handoff",
                "calendar", "Calendar & Bills",
                "presales", "Events",
                "upload", "Import & Export",
                "settings", "Settings");
        Set<String> financeItems = Set.of("Financial Overview", "Cash Flow", "Chair Budgets", "Reports & Handoff");

        destinations.forEach((active, label) -> {
            String html = renderNavigation(active);
            List<NavLink> primaryLinks = links(html, false);
            List<NavLink> financeLinks = links(html, true);
            boolean financePage = financeItems.contains(label);
            String currentTab = financePage ? "Finance" : label;

            assertTrue(find(primaryLinks, currentTab).current(),
                    () -> currentTab + " should be the current tab for " + active);
            assertEquals(1, primaryLinks.stream().filter(NavLink::current).count(),
                    () -> "Exactly one primary tab should be current for " + active);
            if (financePage) {
                assertTrue(find(financeLinks, label).active(),
                        () -> label + " should be the active Finance menu item");
                assertEquals(1, financeLinks.stream().filter(NavLink::active).count(),
                        () -> "Exactly one Finance menu item should be active for " + active);
            } else {
                assertEquals(0, financeLinks.stream().filter(NavLink::active).count(),
                        () -> "No Finance menu item should be active for " + active);
            }
        });
    }

    private String renderNavigation(String active) {
        Context context = new Context();
        context.setVariable("active", active);
        return templateEngine.process("fragments", Set.of("nav"), context);
    }

    private static List<NavLink> links(String html, boolean inMenu) {
        List<NavLink> links = new ArrayList<>();
        Matcher matcher = ANCHOR.matcher(html);
        while (matcher.find()) {
            String beforeLink = html.substring(0, matcher.start());
            boolean linkIsInMenu = beforeLink.lastIndexOf("<ul") > beforeLink.lastIndexOf("</ul>");
            if (linkIsInMenu != inMenu) continue;

            String attributes = matcher.group(1);
            Matcher hrefMatcher = HREF.matcher(attributes);
            assertTrue(hrefMatcher.find(), () -> "Navigation link has no href: " + matcher.group());
            String label = matcher.group(2).replace("&amp;", "&").trim();
            links.add(new NavLink(label, hrefMatcher.group(1),
                    attributes.contains("aria-current=\"page\""), ACTIVE_CLASS.matcher(attributes).find()));
        }
        return links;
    }

    private static NavLink find(List<NavLink> links, String label) {
        return links.stream().filter(link -> link.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing navigation link: " + label));
    }

    private record NavLink(String label, String href, boolean current, boolean active) {}
}
