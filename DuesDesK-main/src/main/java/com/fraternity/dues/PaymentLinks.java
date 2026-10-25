package com.fraternity.dues;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds pre-filled payment links for the chapter's cash services and fills the payment placeholders in reminder
 * messages, so a member can tap once and pay the exact amount with the reconciliation note already typed:
 * <ul>
 *   <li>{@code {{venmo_link}}}, {@code {{cashapp_link}}}, {@code {{paypal_link}}} - one service's link;</li>
 *   <li>{@code {{pay_links}}} - every configured service, one per line ("Venmo: https://...").</li>
 * </ul>
 * Single-brace forms ({venmo_link}) work too, to match the other {name}-style placeholders.
 */
public final class PaymentLinks {
    private PaymentLinks() {}

    public static final String VENMO = "VENMO";
    public static final String CASH_APP = "CASH_APP";
    public static final String PAYPAL = "PAYPAL";
    public static final String ZELLE = "ZELLE";

    /** Services offered in Settings, with their display names. */
    public static final Map<String, String> SERVICES;
    static {
        Map<String, String> services = new LinkedHashMap<>();
        services.put(VENMO, "Venmo");
        services.put(CASH_APP, "Cash App");
        services.put(PAYPAL, "PayPal");
        services.put(ZELLE, "Zelle");
        SERVICES = java.util.Collections.unmodifiableMap(services);
    }

    /** One of the chapter's payment accounts, e.g. Venmo @ChapterTreasury. */
    public static class Handle {
        private String service = VENMO;
        private String handle = "";

        public Handle() {}

        public Handle(String service, String handle) {
            this.service = service;
            this.handle = handle;
        }

        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public String getHandle() { return handle == null ? "" : handle; }
        public void setHandle(String handle) { this.handle = handle; }
        /** Display name only; not saved (and ignored if an older save contains it). */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String getServiceName() { return SERVICES.getOrDefault(service, service); }
    }

    /** Strips the @ / $ prefix and any spaces people paste in with a handle. */
    public static String cleanHandle(String handle) {
        if (handle == null) return "";
        return handle.trim().replaceFirst("^[@$]+", "").replaceAll("\\s+", "");
    }

    /**
     * The pay link for one service, or "" when it has no deep link (Zelle) or the handle is blank.
     * Venmo: https://venmo.com/?txn=pay&recipients={handle}&amount={amount}&note={note}
     */
    public static String link(String service, String handle, double amount, String note) {
        String clean = cleanHandle(handle);
        if (clean.isEmpty()) return "";
        String amountText = String.format(Locale.US, "%.2f", Math.max(0, Money.cents(amount)));
        return switch (service == null ? "" : service) {
            case VENMO -> "https://venmo.com/?txn=pay&recipients=" + encode(clean) + "&amount=" + amountText
                    + "&note=" + encode(note == null ? "" : note);
            case CASH_APP -> "https://cash.app/$" + encode(clean) + "/" + amountText;
            case PAYPAL -> "https://paypal.me/" + encode(clean) + "/" + amountText + "USD";
            default -> "";
        };
    }

    /** "Venmo: https://...\nCash App: https://...\nZelle: chapter@example.com" for every configured service. */
    public static String allLinks(List<Handle> handles, double amount, String note) {
        List<String> lines = new ArrayList<>();
        for (Handle handle : handles == null ? List.<Handle>of() : handles) {
            if (cleanHandle(handle.getHandle()).isEmpty()) continue;
            String url = link(handle.getService(), handle.getHandle(), amount, note);
            lines.add(handle.getServiceName() + ": " + (url.isEmpty() ? handle.getHandle().trim() : url));
        }
        return String.join("\n", lines);
    }

    /** Replaces the payment placeholders in a message. Placeholders for services that aren't set up become "". */
    public static String fill(String message, List<Handle> handles, double amount, String note) {
        if (message == null || !message.contains("_link")) return message;
        String result = message;
        for (String[] placeholder : new String[][] {{"venmo_link", VENMO}, {"cashapp_link", CASH_APP}, {"paypal_link", PAYPAL}}) {
            String url = first(handles, placeholder[1]).map(h -> link(h.getService(), h.getHandle(), amount, note)).orElse("");
            result = result.replace("{{" + placeholder[0] + "}}", url).replace("{" + placeholder[0] + "}", url);
        }
        String all = allLinks(handles, amount, note);
        return result.replace("{{pay_links}}", all).replace("{pay_links}", all);
    }

    private static java.util.Optional<Handle> first(List<Handle> handles, String service) {
        if (handles == null) return java.util.Optional.empty();
        return handles.stream().filter(h -> service.equals(h.getService()) && !cleanHandle(h.getHandle()).isEmpty()).findFirst();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
