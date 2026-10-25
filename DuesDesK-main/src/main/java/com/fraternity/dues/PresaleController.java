package com.fraternity.dues;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event Pre-Sales: campaigns, their ticket/merch sales and expenses, profitability and the seller leaderboard. */
@Controller
public class PresaleController {

    private final Store store;

    public PresaleController(Store store) {
        this.store = store;
    }

    /** One event in the picker: {@code when} is "In 5 days", "Today", "Ended 3 days ago" or "No date"; {@code tone} styles it. */
    public record EventTab(PresaleCampaign campaign, String when, String tone) {}

    /**
     * Open events first, ordered so the next one coming up is on the left (undated events next, then ones whose date has
     * passed with the most recent first); completed events follow in their own list, most recently finished first.
     */
    static List<EventTab> activeTabs(List<PresaleCampaign> campaigns, LocalDate today) {
        List<PresaleCampaign> newestFirst = new ArrayList<>(campaigns);
        Collections.reverse(newestFirst);
        List<PresaleCampaign> open = newestFirst.stream().filter(campaign -> !campaign.isCompleted()).toList();
        List<PresaleCampaign> upcoming = open.stream().filter(campaign -> eventDay(campaign) != null && !eventDay(campaign).isBefore(today))
                .sorted(Comparator.comparing(PresaleController::eventDay)).toList();
        List<PresaleCampaign> undated = open.stream().filter(campaign -> eventDay(campaign) == null).toList();
        List<PresaleCampaign> past = open.stream().filter(campaign -> eventDay(campaign) != null && eventDay(campaign).isBefore(today))
                .sorted(Comparator.comparing(PresaleController::eventDay).reversed()).toList();
        List<EventTab> tabs = new ArrayList<>();
        for (List<PresaleCampaign> group : List.of(upcoming, undated, past)) {
            group.forEach(campaign -> tabs.add(tab(campaign, today)));
        }
        return tabs;
    }

    static List<EventTab> completedTabs(List<PresaleCampaign> campaigns, LocalDate today) {
        List<PresaleCampaign> newestFirst = new ArrayList<>(campaigns);
        Collections.reverse(newestFirst);
        return newestFirst.stream().filter(PresaleCampaign::isCompleted)
                .sorted(Comparator.comparing(PresaleCampaign::getCompletedDate).reversed())
                .map(campaign -> new EventTab(campaign, "Completed", "done"))
                .toList();
    }

    private static LocalDate eventDay(PresaleCampaign campaign) {
        try {
            return campaign.getEventDate().isBlank() ? null : LocalDate.parse(campaign.getEventDate());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static EventTab tab(PresaleCampaign campaign, LocalDate today) {
        LocalDate day = eventDay(campaign);
        if (day == null) return new EventTab(campaign, "No date yet", "none");
        long days = java.time.temporal.ChronoUnit.DAYS.between(today, day);
        if (days == 0) return new EventTab(campaign, "Today", "today");
        if (days > 0) return new EventTab(campaign, days == 1 ? "Tomorrow" : "In " + days + " days", days <= 7 ? "soon" : "later");
        return new EventTab(campaign, days == -1 ? "Ended yesterday" : "Ended " + (-days) + " days ago", "past");
    }

    @GetMapping("/presales")
    public String presales(@RequestParam(name = "campaign", required = false) String campaignId, Model model) {
        LocalDate today = LocalDate.now();
        List<PresaleCampaign> campaigns = new ArrayList<>(store.getPresaleCampaigns());
        List<EventTab> activeTabs = activeTabs(campaigns, today);
        List<EventTab> completedTabs = completedTabs(campaigns, today);
        // With no event picked, open the first open event (else the most recently completed one).
        PresaleCampaign selected = campaigns.stream().filter(campaign -> campaign.getId().equals(campaignId)).findFirst()
                .orElse(!activeTabs.isEmpty() ? activeTabs.getFirst().campaign()
                        : (!completedTabs.isEmpty() ? completedTabs.getFirst().campaign() : null));
        model.addAttribute("activeEvents", activeTabs);
        model.addAttribute("completedEvents", completedTabs);
        List<Member> members = new ArrayList<>(store.getMembers());
        members.sort(Comparator.comparing(Member::getName, String.CASE_INSENSITIVE_ORDER));
        Map<String, String> memberNames = new LinkedHashMap<>();
        members.forEach(member -> memberNames.put(member.getId(), member.getName()));

        model.addAttribute("campaigns", campaigns);
        model.addAttribute("campaign", selected);
        model.addAttribute("members", members);
        model.addAttribute("memberNames", memberNames);
        // Chair positions an event can be assigned to, and their names for the event's "Chair" label.
        List<ChairBudget> chairs = new ArrayList<>(store.getChairBudgets());
        chairs.sort(Comparator.comparing(ChairBudget::getChairName, String.CASE_INSENSITIVE_ORDER));
        Map<String, String> chairNames = new LinkedHashMap<>();
        chairs.forEach(chair -> chairNames.put(chair.getId(), chair.getChairName()));
        model.addAttribute("chairs", chairs);
        model.addAttribute("chairNames", chairNames);
        model.addAttribute("today", LocalDate.now().toString());
        if (selected != null) {
            List<PresaleSale> sales = new ArrayList<>(selected.getSales());
            sales.sort(Comparator.comparing(PresaleSale::getDate, Comparator.nullsLast(Comparator.reverseOrder())));
            List<PresaleExpense> expenses = new ArrayList<>(selected.getExpenses());
            expenses.sort(Comparator.comparing(PresaleExpense::getDate, Comparator.nullsLast(Comparator.reverseOrder())));
            model.addAttribute("sales", sales);
            model.addAttribute("expenses", expenses);
            model.addAttribute("leaderboard", selected.leaderboard(memberNames));
            model.addAttribute("ticketAudience", selected.audience(PresaleSale.TICKET));
            model.addAttribute("wristbandAudience", selected.audience(PresaleSale.WRISTBAND));
            model.addAttribute("customItems", selected.customItemNames());
            model.addAttribute("goalPercent", selected.getRevenueGoal() > 0
                    ? selected.getGrossRevenue() / selected.getRevenueGoal() * 100 : 0);
        }
        return "presales";
    }

    /** Creates a campaign, or (with {@code id}) updates an existing campaign's details. */
    @PostMapping("/presales")
    public synchronized String saveCampaign(@RequestParam(defaultValue = "") String id,
                                            @RequestParam String name,
                                            @RequestParam(defaultValue = "") String eventDate,
                                            @RequestParam(defaultValue = "0") double ticketPrice,
                                            @RequestParam(defaultValue = "0") double revenueGoal,
                                            @RequestParam(defaultValue = "") String prize,
                                            @RequestParam(defaultValue = "") String chairBudgetId,
                                            RedirectAttributes redirect) {
        try {
            PresaleCampaign details = new PresaleCampaign(id.isBlank() ? null : id, name, eventDate.trim(), ticketPrice, revenueGoal, prize);
            details.setChairBudgetId(chairBudgetId.isBlank() ? null : chairBudgetId.trim());
            PresaleCampaign saved = store.savePresaleCampaign(details);
            String chair = saved.getChairBudgetId() == null ? "" : store.getChairBudgets().stream()
                    .filter(budget -> budget.getId().equals(saved.getChairBudgetId())).findFirst()
                    .map(budget -> " Its sales and expenses now count on the " + budget.getChairName() + " chair budget.").orElse("");
            redirect.addFlashAttribute("toast", (id.isBlank() ? "Created " : "Updated ") + saved.getName() + "." + chair);
            return back(saved.getId());
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("toast", e.getMessage());
            return id.isBlank() ? "redirect:/presales" : back(id);
        }
    }

    @PostMapping("/presales/{id}/sales")
    public synchronized String addSale(@PathVariable String id,
                                       @RequestParam String date,
                                       @RequestParam(defaultValue = "") String buyerName,
                                       @RequestParam(defaultValue = PresaleSale.TICKET) String item,
                                       @RequestParam(defaultValue = "") String description,
                                       @RequestParam(defaultValue = "") String guestType,
                                       @RequestParam(defaultValue = "1") int quantity,
                                       @RequestParam double unitPrice,
                                       @RequestParam(defaultValue = "") String sellerMemberId,
                                       RedirectAttributes redirect) {
        String seller = sellerMemberId.isBlank() ? null : sellerMemberId;
        PresaleSale sale = new PresaleSale(UUID.randomUUID().toString(), date.trim(), buyerName.trim(), item,
                description.trim(), guestType.trim(), quantity, unitPrice, seller);
        if (sale.isAdmission() && !PresaleSale.MALE.equals(sale.getGuestType()) && !PresaleSale.FEMALE.equals(sale.getGuestType())) {
            redirect.addFlashAttribute("toast", "Choose Male or Female for tickets and wristbands.");
            return back(id);
        }
        if (PresaleSale.OTHER.equals(item) && sale.getDescription().isBlank()) {
            redirect.addFlashAttribute("toast", "Name the custom item you sold.");
            return back(id);
        }
        if (!store.addPresaleSale(id, sale)) {
            redirect.addFlashAttribute("toast", "Sale was not saved. Check the date, quantity (at least 1) and price.");
            return back(id);
        }
        String credit = seller == null ? "" : " credited to " + store.findMemberById(seller).map(Member::getName).orElse("the seller");
        redirect.addFlashAttribute("toast", "Logged " + saleSummary(sale) + " (" + String.format("$%,.2f", sale.getAmount()) + ")" + credit + ".");
        return back(id);
    }

    /**
     * Logs several items sold together: the sale form's rows arrive as parallel lists (items, guestTypes, descriptions,
     * quantities, unitPrices) and are saved all together or not at all.
     */
    @PostMapping("/presales/{id}/sales/multi")
    public synchronized String addSales(@PathVariable String id,
                                        @RequestParam String date,
                                        @RequestParam(defaultValue = "") String buyerName,
                                        @RequestParam(defaultValue = "") String sellerMemberId,
                                        @RequestParam org.springframework.util.MultiValueMap<String, String> form,
                                        RedirectAttributes redirect) {
        // Read the repeated row fields from the raw map so a name like "Hoodie, grey" is not split on its comma.
        List<String> items = form.getOrDefault("items", List.of());
        List<String> guestTypes = form.getOrDefault("guestTypes", List.of());
        List<String> descriptions = form.getOrDefault("descriptions", List.of());
        List<String> quantities = form.getOrDefault("quantities", List.of());
        List<String> prices = form.getOrDefault("unitPrices", List.of());
        int rows = items.size();
        if (rows == 0 || rows > 50 || guestTypes.size() != rows || descriptions.size() != rows
                || quantities.size() != rows || prices.size() != rows) {
            redirect.addFlashAttribute("toast", "Add at least one item to the sale.");
            return back(id);
        }
        String seller = sellerMemberId.isBlank() ? null : sellerMemberId;
        List<PresaleSale> sales = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            int quantity;
            double price;
            try {
                quantity = Integer.parseInt(quantities.get(i).trim());
                price = prices.get(i).isBlank() ? 0 : Double.parseDouble(prices.get(i).trim());
            } catch (NumberFormatException e) {
                redirect.addFlashAttribute("toast", "Enter a whole-number quantity and a price for every item.");
                return back(id);
            }
            PresaleSale sale = new PresaleSale(UUID.randomUUID().toString(), date.trim(), buyerName.trim(), items.get(i),
                    descriptions.get(i).trim(), guestTypes.get(i).trim(), quantity, price, seller);
            if (sale.isAdmission() && !PresaleSale.MALE.equals(sale.getGuestType()) && !PresaleSale.FEMALE.equals(sale.getGuestType())) {
                redirect.addFlashAttribute("toast", "Choose Male or Female for each ticket and wristband.");
                return back(id);
            }
            if (PresaleSale.OTHER.equals(sale.getItem()) && sale.getDescription().isBlank()) {
                redirect.addFlashAttribute("toast", "Name each custom item you sold.");
                return back(id);
            }
            sales.add(sale);
        }
        if (!store.addPresaleSales(id, sales)) {
            redirect.addFlashAttribute("toast", "Nothing was saved. Check the date, each quantity (at least 1) and each price.");
            return back(id);
        }
        double total = sales.stream().mapToDouble(PresaleSale::getAmount).sum();
        String credit = seller == null ? "" : " credited to " + store.findMemberById(seller).map(Member::getName).orElse("the seller");
        redirect.addFlashAttribute("toast", "Logged " + sales.stream().map(PresaleController::saleSummary).collect(java.util.stream.Collectors.joining(", "))
                + " (" + String.format("$%,.2f", total) + ")" + credit + ".");
        return back(id);
    }

    /** Marks an event complete (completed=true) or reopens it (completed=false). */
    @PostMapping("/presales/{id}/complete")
    public synchronized String complete(@PathVariable String id,
                                        @RequestParam(defaultValue = "true") boolean completed,
                                        RedirectAttributes redirect) {
        String name = store.findPresaleCampaign(id).map(PresaleCampaign::getName).orElse(null);
        if (name == null || !store.setPresaleCompleted(id, completed, LocalDate.now())) {
            redirect.addFlashAttribute("toast", "That event was not found.");
            return "redirect:/presales";
        }
        redirect.addFlashAttribute("toast", completed
                ? name + " is marked complete. Find it under Completed events whenever you need it."
                : name + " is open again.");
        return back(id);
    }

    @PostMapping("/presales/{id}/expenses")
    public synchronized String addExpense(@PathVariable String id,
                                          @RequestParam String date,
                                          @RequestParam String description,
                                          @RequestParam double amount,
                                          RedirectAttributes redirect) {
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            redirect.addFlashAttribute("toast", "Choose a valid expense date.");
            return back(id);
        }
        boolean saved = store.addPresaleExpense(id, new PresaleExpense(UUID.randomUUID().toString(), date, description.trim(), amount));
        redirect.addFlashAttribute("toast", saved ? "Logged " + String.format("$%,.2f", amount) + " expense: " + description.trim() + "."
                : "Expense was not saved. Enter a description and an amount greater than zero.");
        return back(id);
    }

    @PostMapping("/presales/{id}/sales/{saleId}/delete")
    public synchronized String removeSale(@PathVariable String id, @PathVariable String saleId, RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", store.removePresaleSale(id, saleId) ? "Sale removed." : "That sale was already removed.");
        return back(id);
    }

    @PostMapping("/presales/{id}/expenses/{expenseId}/delete")
    public synchronized String removeExpense(@PathVariable String id, @PathVariable String expenseId, RedirectAttributes redirect) {
        redirect.addFlashAttribute("toast", store.removePresaleExpense(id, expenseId) ? "Expense removed." : "That expense was already removed.");
        return back(id);
    }

    /** "3 tickets (Female)", "2 wristbands (Male)", "1 ticket (Male)", "2 × Hoodie" or "4 × Koozies". */
    private static String saleSummary(PresaleSale sale) {
        int quantity = sale.getQuantity();
        if (sale.isAdmission()) {
            String noun = sale.isWristband() ? "wristband" : "ticket";
            return quantity + " " + noun + (quantity == 1 ? "" : "s") + " (" + sale.getGuestType() + ")";
        }
        String name = PresaleSale.OTHER.equals(sale.getItem()) || !sale.getDescription().isBlank() ? sale.getDescription() : "merch";
        return quantity + " × " + name;
    }

    private static String back(String campaignId) {
        return "redirect:/presales?campaign=" + java.net.URLEncoder.encode(campaignId, java.nio.charset.StandardCharsets.UTF_8);
    }
}
