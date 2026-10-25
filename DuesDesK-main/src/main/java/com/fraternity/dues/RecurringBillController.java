package com.fraternity.dues;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** The recurring-bill forms on the Calendar page: create or edit a bill, mark a due date paid or skipped, delete. */
@Controller
public class RecurringBillController {

    private final Store store;

    public RecurringBillController(Store store) {
        this.store = store;
    }

    /**
     * Creates a bill, or (with {@code id}) updates one. Reminder lead times come from the preset checkboxes
     * ({@code reminderDays}) plus any custom days typed in ({@code customReminderDays}, separated by commas or spaces).
     */
    @PostMapping("/calendar/bills")
    public synchronized String saveBill(@RequestParam(defaultValue = "") String id,
                                        @RequestParam String name,
                                        @RequestParam double amount,
                                        @RequestParam String frequency,
                                        @RequestParam String startDate,
                                        @RequestParam(defaultValue = "") String endDate,
                                        @RequestParam String category,
                                        @RequestParam(defaultValue = "") String payee,
                                        @RequestParam(defaultValue = RecurringBill.MANUAL) String paymentMode,
                                        @RequestParam(required = false) List<Integer> reminderDays,
                                        @RequestParam(defaultValue = "") String customReminderDays,
                                        RedirectAttributes redirect) {
        try {
            List<Integer> reminders = new ArrayList<>(reminderDays == null ? List.of() : reminderDays);
            for (String part : customReminderDays.trim().split("[,\\s]+")) {
                if (part.isBlank()) continue;
                try {
                    reminders.add(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Custom reminders are whole numbers of days, like 10 or 21.");
                }
            }
            RecurringBill saved = store.saveRecurringBill(new RecurringBill(id.isBlank() ? null : id, name, amount, frequency,
                    startDate.trim(), endDate.trim(), category, payee, paymentMode, reminders, null));
            redirect.addFlashAttribute("toast", (id.isBlank() ? "Added " : "Updated ") + saved.getName() + ": "
                    + String.format("$%,.2f", saved.getAmount()) + " " + saved.getFrequencyLabel().toLowerCase()
                    + (saved.isAutomatic() ? ", paid automatically." : ", paid manually."));
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", e.getMessage());
        }
        return "redirect:/calendar#bills";
    }

    /** Marks one due date paid: records the outflow in Inflow/Outflow and stops the reminder. */
    @PostMapping("/calendar/bills/{id}/pay")
    public synchronized String pay(@PathVariable String id,
                                   @RequestParam String dueDate,
                                   @RequestParam(defaultValue = "") String paidDate,
                                   @RequestParam(required = false) Double amount,
                                   RedirectAttributes redirect) {
        RecurringBill bill = store.getRecurringBills().stream().filter(item -> item.getId().equals(id)).findFirst().orElse(null);
        LocalDate due;
        LocalDate paidOn;
        try {
            due = LocalDate.parse(dueDate);
            paidOn = paidDate.isBlank() ? LocalDate.now() : LocalDate.parse(paidDate);
        } catch (DateTimeParseException e) {
            redirect.addFlashAttribute("toast", "Choose a valid payment date.");
            return "redirect:/calendar#bills";
        }
        double paid = amount != null ? amount : bill == null ? 0 : bill.getAmount();
        boolean ok = store.payRecurringBill(id, due, paidOn, paid);
        redirect.addFlashAttribute("toast", ok
                ? "Recorded " + String.format("$%,.2f", paid) + " paid for " + bill.getName() + " (due " + due + ")."
                : "That payment wasn't recorded. The bill may have been removed, or this due date is already handled.");
        return "redirect:/calendar?month=" + YearMonth.from(paidOn) + "#bills";
    }

    @PostMapping("/calendar/bills/{id}/skip")
    public synchronized String skip(@PathVariable String id, @RequestParam String dueDate, RedirectAttributes redirect) {
        try {
            boolean ok = store.skipRecurringBill(id, LocalDate.parse(dueDate));
            redirect.addFlashAttribute("toast", ok ? "Skipped the " + dueDate + " due date." : "That due date was already handled.");
        } catch (DateTimeParseException e) {
            redirect.addFlashAttribute("toast", "Choose a valid due date.");
        }
        return "redirect:/calendar#bills";
    }

    @PostMapping("/calendar/bills/{id}/delete")
    public synchronized String delete(@PathVariable String id, RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", store.deleteRecurringBill(id)
                ? "Recurring bill removed. Payments already recorded stay in Cash Flow." : "That bill was already removed.");
        return "redirect:/calendar#bills";
    }
}
