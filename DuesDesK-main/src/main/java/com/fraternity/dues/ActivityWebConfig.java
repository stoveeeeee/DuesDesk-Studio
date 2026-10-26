package com.fraternity.dues;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Labels every save made while handling a request with the action behind it, for the activity log. */
@Configuration
public class ActivityWebConfig implements WebMvcConfigurer {

    private static final Map<Pattern, String> POST_SOURCES = new LinkedHashMap<>();

    static {
        source("/add-member", "Add member");
        source("/edit-member/[^/]+", "Edit member");
        source("/delete-member/[^/]+", "Remove member");
        source("/members/[^/]+/charges", "Add charge");
        source("/members/[^/]+/charges/[^/]+/remove", "Remove charge");
        source("/charges/batch", "Batch charge");
        source("/charges/apply", "Add charge");
        source("/members/adjustments", "Fine or discount");
        source("/charge-presets/[^/]+/remove", "Remove charge preset");
        source("/members/[^/]+/installments", "Payment plan");
        source("/members/[^/]+/installments/[^/]+/remove", "Remove installment");
        source("/members/[^/]+/social-clearance", "Social clearance vouch");
        source("/members/[^/]+/payer-aliases/remove", "Forget payer name");
        source("/members/[^/]+/officer-review/resolve", "Close officer review");
        source("/payments", "Record payment");
        source("/calendar/transactions", "Calendar transaction");
        source("/calendar/bills", "Save bill");
        source("/calendar/bills/[^/]+/pay", "Pay bill");
        source("/calendar/bills/[^/]+/skip", "Skip bill");
        source("/calendar/bills/[^/]+/delete", "Delete bill");
        source("/upload-csv", "GreekBill CSV import");
        source("/numbers/flow/upload-venmo", "Venmo CSV import");
        source("/numbers/flow/upload-bank", "Bank CSV import");
        source("/import-data", "Restore backup");
        source("/reconciliation/[^/]+/confirm", "Match deposit to member");
        source("/reconciliation/[^/]+/ignore", "Dismiss deposit");
        source("/venmo/unmatched/[^/]+/assign", "Match Venmo email payment");
        source("/venmo/unmatched/[^/]+/dismiss", "Dismiss Venmo email payment");
        source("/settings/venmo-email/sync", "Venmo email import");
        source("/finance/semester-rollover", "Semester rollover");
        source("/finance/chair-budgets", "Save chair budget");
        source("/finance/chair-budgets/[^/]+/edit", "Edit chair budget");
        source("/finance/chair-budgets/[^/]+/delete", "Delete chair budget");
        source("/finance/chair-budgets/[^/]+/raised", "Record money raised");
        source("/finance/chair-budgets/[^/]+/charges", "Chair charges");
        source("/finance/reimbursements", "Chair expense");
        source("/presales", "Save event");
        source("/presales/[^/]+/sales(/multi)?", "Record event sale");
        source("/presales/[^/]+/complete", "Complete event");
        source("/presales/[^/]+/expenses", "Event expense");
        source("/presales/[^/]+/sales/[^/]+/delete", "Delete event sale");
        source("/presales/[^/]+/expenses/[^/]+/delete", "Delete event expense");
        source("/rent/residents", "Add resident");
        source("/rent/residents/[^/]+/edit", "Edit resident");
        source("/rent/residents/[^/]+/payment", "Rent payment");
        source("/rent/charges", "Rent charge");
        source("/rent/residents/[^/]+/charges/[^/]+/remove", "Remove rent charge");
        source("/rent/residents/[^/]+/delete", "Remove resident");
        source("/rent/residents/[^/]+/toggle-mute", "Mute rent reminders");
        source("/rent/settings", "Rent settings");
        source("/toggle-mute/[^/]+", "Mute reminders");
        source("/attention/[^/]+/snooze", "Snooze attention");
        source("/ihq-bill", "IHQ bill");
        source("/dashboard-stats", "Dashboard stats");
        source("/reminders/run", "Run reminder sequence");
        source("/settings/reminders/send", "Send reminders");
        source("/send-text/[^/]+", "Text member");
        source("/send-bulk", "Text members");
        source("/send-status-text", "Text members by status");
        source("/settings/appearance", "Appearance");
        source("/settings/notifications", "Notification settings");
        source("/settings/payment-links", "Payment links");
        source("/settings/twilio(/disconnect)?", "Twilio settings");
        source("/settings/treasury", "Treasury settings");
        source("/settings/cloud-backup/.*", "Cloud backup");
        source("/settings", "Reminder settings");
    }

    private static void source(String path, String label) {
        POST_SOURCES.put(Pattern.compile(path), label);
    }

    /** The activity log label for a request, e.g. "Add charge" for POST /members/42/charges. */
    static String sourceFor(String method, String path) {
        if (path == null) return "Automatic update";
        if (!"POST".equalsIgnoreCase(method)) return "Automatic update";
        for (Map.Entry<Pattern, String> item : POST_SOURCES.entrySet()) {
            if (item.getKey().matcher(path).matches()) return item.getValue();
        }
        return "Change";
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Store.setActivitySource(sourceFor(request.getMethod(), request.getRequestURI()));
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                Store.setActivitySource(null);
            }
        });
    }
}
