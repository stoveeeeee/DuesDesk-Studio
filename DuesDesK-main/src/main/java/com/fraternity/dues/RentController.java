package com.fraternity.dues;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

@Controller
public class RentController {

    private final Store store;
    private final SmsService smsService;

    public RentController(Store store, SmsService smsService) {
        this.store = store;
        this.smsService = smsService;
    }

    @GetMapping("/rent")
    public String rent(@org.springframework.web.bind.annotation.RequestParam(name = "sort", required = false) String sort, Model model) {
        List<Resident> residents = store.getResidents();
        java.util.Comparator<Resident> order = RosterSort.comparator(sort, Resident::getName,
                java.util.Map.of("rent", Resident::getMonthlyRent, "balance", Resident::getBalanceDue, "paid", Resident::getPaidThisMonth));
        if (order != null) {
            residents.sort(order);
            model.addAttribute("sort", sort);
        }
        double collected = residents.stream().mapToDouble(Resident::getPaidThisMonth).sum();
        double outstanding = residents.stream().mapToDouble(Resident::getBalanceDue).sum();
        double expected = residents.stream().mapToDouble(Resident::getMonthlyRent).sum();
        double collectionRate = collected + outstanding > 0 ? collected / (collected + outstanding) * 100 : 100;
        long paidUp = residents.stream().filter(r -> !Money.positive(r.getBalanceDue())).count();
        List<Resident> eligibleForReminder = residents.stream()
                .filter(resident -> Money.positive(resident.getBalanceDue()) && !resident.isMuteReminders())
                .toList();

        model.addAttribute("residents", residents);
        model.addAttribute("smsRemainingToday", store.getSmsRemainingToday());
        model.addAttribute("rentCollected", collected);
        model.addAttribute("rentOutstanding", outstanding);
        model.addAttribute("rentExpected", expected);
        model.addAttribute("rentCollectionRate", collectionRate);
        model.addAttribute("paidUpCount", paidUp);
        model.addAttribute("balanceDueCount", residents.size() - paidUp);
        model.addAttribute("reminderRecipientCount", eligibleForReminder.size());
        model.addAttribute("reminderBalanceTotal", eligibleForReminder.stream().mapToDouble(Resident::getBalanceDue).sum());
        model.addAttribute("liveSmsEnabled", smsService.isLiveConfigured());
        model.addAttribute("settings", store.getSettings());
        model.addAttribute("unmutedResidentCount", residents.stream().filter(resident -> !resident.isMuteReminders()).count());
        // Roster members for the "Linked member" picker, and names for showing who each resident is linked to.
        List<Member> roster = new java.util.ArrayList<>(store.getMembers());
        roster.sort(java.util.Comparator.comparing(Member::getName, String.CASE_INSENSITIVE_ORDER));
        java.util.Map<String, String> memberNames = new java.util.LinkedHashMap<>();
        roster.forEach(member -> memberNames.put(member.getId(), member.getName()));
        model.addAttribute("rosterMembers", roster);
        // Rendered rent reminder for each resident with a balance, for the Email / Messages / Copy quick actions.
        java.util.Map<String, String> reminderTexts = new java.util.LinkedHashMap<>();
        residents.stream().filter(resident -> Money.positive(resident.getBalanceDue()))
                .forEach(resident -> reminderTexts.put(resident.getId(), smsService.formatRentMessage(resident)));
        model.addAttribute("reminderTexts", reminderTexts);
        model.addAttribute("memberNames", memberNames);
        return "rent";
    }

    private boolean invalid(String name, String phone, String room, double rent, double balance, double paid) {
        return name.isBlank() || name.length() > 120 || phone.isBlank() || phone.length() > 40 || room.length() > 60
                || !Double.isFinite(rent) || !Double.isFinite(balance) || !Double.isFinite(paid)
                || rent < 0 || balance < 0 || paid < 0;
    }

    /** The "Linked member" choice: "" = match by name, "none" = not a member, otherwise a roster member id. */
    static final String LINK_BY_NAME = "";
    static final String LINK_NONE = "none";
    static final String LINK_KEEP = "__keep__";

    /** Turns the form choice into the stored link (null = match by name, "" = not a member), or throws when the member is unknown. */
    private String linkValue(String choice) {
        if (choice == null || LINK_BY_NAME.equals(choice)) return null;
        if (LINK_NONE.equals(choice)) return "";
        if (store.findMemberById(choice).isEmpty()) throw new IllegalArgumentException("That roster member no longer exists. Pick another.");
        return choice;
    }

    @PostMapping("/rent/residents")
    public synchronized String addResident(@RequestParam String name, @RequestParam String phoneNumber,
                                           @RequestParam(defaultValue = "") String room,
                                           @RequestParam double monthlyRent, @RequestParam double balanceDue,
                                           @RequestParam double paidThisMonth,
                                           @RequestParam(defaultValue = LINK_BY_NAME) String linkedMemberId,
                                           @RequestParam(defaultValue = "") String email,
                                           RedirectAttributes redirect) {
        if (invalid(name, phoneNumber, room, monthlyRent, balanceDue, paidThisMonth) || !DashboardController.validEmail(email)) {
            redirect.addFlashAttribute("toast", "Enter valid resident details and non-negative amounts.");
            return "redirect:/rent";
        }
        Resident resident = new Resident(UUID.randomUUID().toString(), name.trim(), phoneNumber.trim(),
                room.trim(), monthlyRent, balanceDue, paidThisMonth);
        resident.setEmail(email.trim());
        try {
            resident.setLinkedMemberId(linkValue(linkedMemberId));
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", e.getMessage());
            return "redirect:/rent";
        }
        store.addResident(resident);
        String linkedTo = resident.getLinkedMemberId() == null || resident.getLinkedMemberId().isEmpty() ? ""
                : store.findMemberById(resident.getLinkedMemberId()).map(member -> ", linked to " + member.getName() + " on the roster").orElse("");
        redirect.addFlashAttribute("toast", "Added " + name.trim() + " to the house" + linkedTo + ".");
        return "redirect:/rent";
    }

    @PostMapping("/rent/residents/{id}/edit")
    public synchronized String editResident(@PathVariable String id, @RequestParam String name,
                                            @RequestParam String phoneNumber,
                                            @RequestParam(defaultValue = "") String room,
                                            @RequestParam double monthlyRent, @RequestParam double balanceDue,
                                            @RequestParam double paidThisMonth,
                                            @RequestParam(defaultValue = LINK_KEEP) String linkedMemberId,
                                            @RequestParam(required = false) String email,
                                            RedirectAttributes redirect) {
        if (invalid(name, phoneNumber, room, monthlyRent, balanceDue, paidThisMonth) || (email != null && !DashboardController.validEmail(email))) {
            redirect.addFlashAttribute("toast", "Enter valid resident details and non-negative amounts.");
            return "redirect:/rent";
        }
        String link;
        try {
            link = LINK_KEEP.equals(linkedMemberId) ? LINK_KEEP : linkValue(linkedMemberId);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", e.getMessage());
            return "redirect:/rent";
        }
        synchronized (store) {
            store.findResidentById(id).ifPresentOrElse(resident -> {
                if (!LINK_KEEP.equals(link)) resident.setLinkedMemberId(link);
                if (email != null) resident.setEmail(email.trim());
                resident.setName(name.trim());
                resident.setPhoneNumber(phoneNumber.trim());
                resident.setRoom(room.trim());
                resident.setMonthlyRent(monthlyRent);
                resident.setBalanceDue(balanceDue);
                resident.setPaidThisMonth(paidThisMonth);
                if (!resident.isLinkDecided()) store.autoLinkResidents();
                store.save();
                redirect.addFlashAttribute("toast", "Updated " + resident.getName() + ".");
            }, () -> redirect.addFlashAttribute("toast", "Resident not found."));
        }
        return "redirect:/rent";
    }

    @PostMapping("/rent/residents/{id}/payment")
    public synchronized String recordRentPayment(@PathVariable String id, @RequestParam double amount,
                                                 RedirectAttributes redirect) {
        if (!Double.isFinite(amount) || amount <= 0) {
            redirect.addFlashAttribute("toast", "Enter a payment amount greater than zero.");
            return "redirect:/rent";
        }
        synchronized (store) {
            store.findResidentById(id).ifPresentOrElse(resident -> {
                resident.setBalanceDue(Math.max(0, resident.getBalanceDue() - amount));
                resident.setPaidThisMonth(resident.getPaidThisMonth() + amount);
                store.save();
                redirect.addFlashAttribute("toast", String.format("Recorded $%,.2f rent from %s.", amount, resident.getName()));
            }, () -> redirect.addFlashAttribute("toast", "Resident not found."));
        }
        return "redirect:/rent";
    }

    @PostMapping("/rent/charges")
    public synchronized String addCharges(@RequestParam(required = false) List<String> residentIds,
                                          @RequestParam String description,
                                          @RequestParam double amount,
                                          @RequestParam(defaultValue = "") String dueDate,
                                          RedirectAttributes redirect) {
        if (description.isBlank() || description.length() > 200 || !Double.isFinite(amount) || amount <= 0) {
            redirect.addFlashAttribute("toast", "Enter a charge description and an amount greater than zero.");
            return "redirect:/rent#rentCharges";
        }
        if (!dueDate.isBlank()) {
            try {
                java.time.LocalDate.parse(dueDate);
            } catch (java.time.format.DateTimeParseException e) {
                redirect.addFlashAttribute("toast", "Choose a valid due date, or leave it blank.");
                return "redirect:/rent#rentCharges";
            }
        }
        int added = store.addResidentCharges(residentIds, description.trim(), amount, dueDate);
        redirect.addFlashAttribute("toast", added > 0
                ? "Added " + description.trim() + " (" + String.format("$%,.2f", amount) + ") to "
                    + added + (added == 1 ? " resident's rent bill." : " residents' rent bills.")
                : "No charges were added. Select valid residents and try again.");
        return "redirect:/rent";
    }

    @PostMapping("/rent/residents/{id}/charges/{chargeId}/remove")
    public synchronized String removeCharge(@PathVariable String id, @PathVariable String chargeId,
                                            RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", store.removeResidentCharge(id, chargeId)
                ? "Charge removed and balance updated."
                : "Charge not found.");
        return "redirect:/rent";
    }

    @PostMapping("/rent/residents/{id}/delete")
    public synchronized String deleteResident(@PathVariable String id, RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", store.removeResident(id) ? "Resident removed." : "Resident not found.");
        return "redirect:/rent";
    }

    @PostMapping("/rent/residents/{id}/toggle-mute")
    public synchronized String toggleMute(@PathVariable String id, RedirectAttributes redirect) {
        synchronized (store) {
            store.findResidentById(id).ifPresent(resident -> {
                resident.setMuteReminders(!resident.isMuteReminders());
                store.save();
                redirect.addFlashAttribute("toast", (resident.isMuteReminders() ? "Muted " : "Unmuted ")
                        + "texts for " + resident.getName());
            });
        }
        return "redirect:/rent";
    }

    @PostMapping("/rent/residents/{id}/text")
    public synchronized String textResident(@PathVariable String id, RedirectAttributes redirect) {
        store.findResidentById(id).ifPresent(resident -> redirect.addFlashAttribute("toast",
                smsService.sendRentReminder(resident)
                        ? "Sent rent reminder to " + resident.getName() + "!"
                        : "Reminder was not sent. Check the daily SMS cap and dispatch log."));
        return "redirect:/rent";
    }

    @PostMapping("/rent/send-bulk")
    public synchronized String textAllWithBalance(RedirectAttributes redirect) {
        int count = smsService.sendRentRemindersToAllWithBalance();
        redirect.addFlashAttribute("toast", "Sent " + count + " rent reminders. Remaining daily SMS allowance: "
                + store.getSmsRemainingToday() + ".");
        return "redirect:/rent";
    }

    @PostMapping("/rent/announce")
    public synchronized String announce(@RequestParam String message, RedirectAttributes redirect) {
        if (message.isBlank() || message.length() > 1600) {
            redirect.addFlashAttribute("toast", "Enter a house announcement (up to 1,600 characters).");
            return "redirect:/rent#rentCommunications";
        }
        int count = smsService.broadcastToResidents(message.trim());
        redirect.addFlashAttribute("toast", "Announcement sent to " + count + " residents. Remaining daily SMS allowance: "
                + store.getSmsRemainingToday() + ".");
        return "redirect:/rent#rentCommunications";
    }

    @PostMapping("/rent/settings")
    public synchronized String updateRentTemplate(@RequestParam String rentSmsTemplate, RedirectAttributes redirect) {
        if (rentSmsTemplate.isBlank() || rentSmsTemplate.length() > 1600) {
            redirect.addFlashAttribute("toast", "Rent template must be 1–1,600 characters.");
            return "redirect:/rent#rentCommunications";
        }
        synchronized (store) {
            store.getSettings().setRentSmsTemplate(rentSmsTemplate);
            store.save();
        }
        redirect.addFlashAttribute("toast", "Rent reminder template saved!");
        return "redirect:/rent#rentCommunications";
    }
}
