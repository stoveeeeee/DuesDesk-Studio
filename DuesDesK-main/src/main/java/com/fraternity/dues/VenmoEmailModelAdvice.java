package com.fraternity.dues;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Adds the Venmo email import status to the Settings page, which DashboardController renders. */
@ControllerAdvice
public class VenmoEmailModelAdvice {
    private final VenmoEmailSyncService syncService;
    private final PaymentStore payments;

    public VenmoEmailModelAdvice(VenmoEmailSyncService syncService, PaymentStore payments) {
        this.syncService = syncService;
        this.payments = payments;
    }

    @ModelAttribute
    public void addVenmoEmailStatus(Model model, HttpServletRequest request) {
        if (!"/settings".equals(request.getRequestURI())) return;
        model.addAttribute("venmoEmail", syncService.status());
        model.addAttribute("unmatchedCount", payments.unmatched().size());
    }
}
