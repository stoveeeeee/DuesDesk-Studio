package com.fraternity.dues;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the header notification bell to every page, the reminder sequence to the Dues page, and the
 * "Template for Greekbill charges" card to the pages that show it.
 */
@ControllerAdvice
public class TreasuryModelAdvice {
    private final Store store;
    private final NotificationService notifications;
    private final ReminderSequenceService sequence;

    public TreasuryModelAdvice(Store store, NotificationService notifications, ReminderSequenceService sequence) {
        this.store = store;
        this.notifications = notifications;
        this.sequence = sequence;
    }

    @ModelAttribute
    public void addTreasuryModel(Model model, HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return;
        String path = request.getRequestURI();
        LocalDate today = LocalDate.now();
        try {
            model.addAttribute("headerAlerts", store.getSettings().isNotificationsEnabled()
                    ? notifications.currentAlerts(today) : List.of());
        } catch (RuntimeException e) {
            model.addAttribute("headerAlerts", List.of());
        }
        if ("/".equals(path) || "/index".equals(path)) {
            List<ReminderSequenceService.Plan> plans = sequence.preview(today);
            model.addAttribute("sequencePlans", plans);
            model.addAttribute("flaggedMembers", sequence.flaggedMembers());
            Map<String, List<ReminderStep>> notices = new LinkedHashMap<>();
            store.getMembers().forEach(member -> notices.put(member.getId(), PastDueNoticeService.notices(member)));
            model.addAttribute("noticesByMember", notices);
        }
        if ("/charges".equals(path)) {
            String period = request.getParameter("gbPeriod");
            GreekBillTemplateService.Period range = GreekBillTemplateService.resolvePeriod(period,
                    request.getParameter("gbFrom"), request.getParameter("gbTo"), today, store.getSettings().getLastRolloverDate());
            model.addAttribute("greekbill", new GreekBillTemplateService(store).build(range, today));
            model.addAttribute("greekbillPeriods", GreekBillTemplateService.PERIODS);
            model.addAttribute("greekbillTitle", GreekBillTemplateService.TITLE);
            model.addAttribute("greekbillAction", path);
        }
    }
}
