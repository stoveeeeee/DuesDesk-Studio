package com.fraternity.dues;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An event pre-sale: tickets and merchandise sold ahead of an event, the event's expenses, and which member sold
 * what (for the seller leaderboard and its optional prize).
 */
public class PresaleCampaign {

    /** One line of the seller leaderboard. Members tied on revenue share a rank; {@code admissions} counts tickets and wristbands. */
    public record LeaderboardRow(int rank, String memberId, String name, int admissions, int sales, double revenue) {}

    /** Who bought a kind of admission (tickets or wristbands): units and revenue by male, female and not recorded. */
    public record Audience(String item, int male, int female, int unspecified, double maleRevenue, double femaleRevenue) {
        public int total() { return male + female + unspecified; }
        /** Share of the recorded (male + female) units that were male, in percent; 0 when neither is recorded. */
        public double malePercent() { return male + female == 0 ? 0 : male * 100.0 / (male + female); }
        public double femalePercent() { return male + female == 0 ? 0 : female * 100.0 / (male + female); }
        /** "Male" or "Female" for whichever is buying more; blank on a tie or when neither is recorded. */
        public String leader() { return male == female ? "" : male > female ? PresaleSale.MALE : PresaleSale.FEMALE; }
    }

    private String id;
    private String name;
    /** yyyy-MM-dd, or blank when the date isn't set yet. */
    private String eventDate = "";
    private double ticketPrice;
    /** Optional gross revenue goal; 0 means no goal. */
    private double revenueGoal;
    /** Optional reward for the top seller, e.g. "Free formal ticket". */
    private String prize = "";
    private List<PresaleSale> sales = new ArrayList<>();
    private List<PresaleExpense> expenses = new ArrayList<>();
    /** A finished event is listed under "Completed events" but keeps all of its sales and expenses. */
    private boolean completed;
    /** yyyy-MM-dd the event was marked complete; blank while it is open. */
    private String completedDate = "";
    /** The chair position running this event; its sales and expenses count on that chair's budget. Null for none. */
    private String chairBudgetId;

    public PresaleCampaign() {}

    public PresaleCampaign(String id, String name, String eventDate, double ticketPrice, double revenueGoal, String prize) {
        this.id = id;
        this.name = name;
        this.eventDate = eventDate == null ? "" : eventDate;
        this.ticketPrice = Money.cents(ticketPrice);
        this.revenueGoal = Money.cents(revenueGoal);
        this.prize = prize == null ? "" : prize;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEventDate() { return eventDate == null ? "" : eventDate; }
    public void setEventDate(String eventDate) { this.eventDate = eventDate; }
    public double getTicketPrice() { return ticketPrice; }
    public void setTicketPrice(double ticketPrice) { this.ticketPrice = Money.cents(ticketPrice); }
    public double getRevenueGoal() { return revenueGoal; }
    public void setRevenueGoal(double revenueGoal) { this.revenueGoal = Money.cents(revenueGoal); }
    public String getPrize() { return prize == null ? "" : prize; }
    public void setPrize(String prize) { this.prize = prize; }
    public List<PresaleSale> getSales() {
        if (sales == null) sales = new ArrayList<>();
        return sales;
    }
    public void setSales(List<PresaleSale> sales) { this.sales = sales; }
    public List<PresaleExpense> getExpenses() {
        if (expenses == null) expenses = new ArrayList<>();
        return expenses;
    }
    public void setExpenses(List<PresaleExpense> expenses) { this.expenses = expenses; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public String getCompletedDate() { return completedDate == null ? "" : completedDate; }
    public void setCompletedDate(String completedDate) { this.completedDate = completedDate; }
    public String getChairBudgetId() { return chairBudgetId == null || chairBudgetId.isBlank() ? null : chairBudgetId; }
    public void setChairBudgetId(String chairBudgetId) { this.chairBudgetId = chairBudgetId; }

    @JsonIgnore
    public double getGrossRevenue() {
        return Money.cents(getSales().stream().mapToDouble(PresaleSale::getAmount).sum());
    }
    @JsonIgnore
    public double getExpenseTotal() {
        return Money.cents(getExpenses().stream().mapToDouble(PresaleExpense::getAmount).sum());
    }
    @JsonIgnore
    public double getNetProfit() { return Money.cents(getGrossRevenue() - getExpenseTotal()); }
    /** Net profit as a share of gross revenue, in percent; 0 before anything has sold. */
    @JsonIgnore
    public double getMarginPercent() {
        double gross = getGrossRevenue();
        return gross > 0 ? getNetProfit() / gross * 100 : 0;
    }
    @JsonIgnore
    public int getTicketsSold() {
        return getSales().stream().filter(PresaleSale::isTicket).mapToInt(PresaleSale::getQuantity).sum();
    }
    @JsonIgnore
    public int getWristbandsSold() {
        return getSales().stream().filter(PresaleSale::isWristband).mapToInt(PresaleSale::getQuantity).sum();
    }
    /** Merchandise and custom items together: everything that is not a ticket or a wristband. */
    @JsonIgnore
    public int getMerchSold() {
        return getSales().stream().filter(sale -> !sale.isAdmission()).mapToInt(PresaleSale::getQuantity).sum();
    }

    /** Male/female split of the tickets ({@link PresaleSale#TICKET}) or wristbands ({@link PresaleSale#WRISTBAND}) sold. */
    public Audience audience(String item) {
        int male = 0, female = 0, unspecified = 0;
        double maleRevenue = 0, femaleRevenue = 0;
        for (PresaleSale sale : getSales()) {
            if (!sale.isAdmission() || !item.equals(sale.isWristband() ? PresaleSale.WRISTBAND : PresaleSale.TICKET)) continue;
            if (PresaleSale.MALE.equals(sale.getGuestType())) { male += sale.getQuantity(); maleRevenue += sale.getAmount(); }
            else if (PresaleSale.FEMALE.equals(sale.getGuestType())) { female += sale.getQuantity(); femaleRevenue += sale.getAmount(); }
            else unspecified += sale.getQuantity();
        }
        return new Audience(item, male, female, unspecified, Money.cents(maleRevenue), Money.cents(femaleRevenue));
    }
    /** Revenue from sales no member was credited for (walk-ups, sales at the door). */
    @JsonIgnore
    public double getUncreditedRevenue() {
        return Money.cents(getSales().stream().filter(sale -> sale.getSellerMemberId() == null).mapToDouble(PresaleSale::getAmount).sum());
    }

    /** Names of the custom items sold so far (first-use order), offered again as suggestions when logging a sale. */
    public List<String> customItemNames() {
        return getSales().stream().filter(sale -> PresaleSale.OTHER.equals(sale.getItem())).map(PresaleSale::getDescription)
                .filter(name -> !name.isBlank()).distinct().toList();
    }

    /**
     * Members ranked by the revenue they brought in (then by tickets and wristbands, then by name). {@code memberNames} maps member
     * ids to names; a seller who has since left the roster shows as "Removed member".
     */
    public List<LeaderboardRow> leaderboard(Map<String, String> memberNames) {
        Map<String, double[]> totals = new LinkedHashMap<>(); // revenue, tickets, sales
        for (PresaleSale sale : getSales()) {
            if (sale.getSellerMemberId() == null) continue;
            double[] total = totals.computeIfAbsent(sale.getSellerMemberId(), key -> new double[3]);
            total[0] += sale.getAmount();
            if (sale.isAdmission()) total[1] += sale.getQuantity();
            total[2]++;
        }
        List<LeaderboardRow> unranked = totals.entrySet().stream()
                .map(entry -> new LeaderboardRow(0, entry.getKey(), memberNames.getOrDefault(entry.getKey(), "Removed member"),
                        (int) entry.getValue()[1], (int) entry.getValue()[2], Money.cents(entry.getValue()[0])))
                .sorted(Comparator.comparingDouble(LeaderboardRow::revenue).reversed()
                        .thenComparing(Comparator.comparingInt(LeaderboardRow::admissions).reversed())
                        .thenComparing(LeaderboardRow::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<LeaderboardRow> ranked = new ArrayList<>();
        for (int i = 0; i < unranked.size(); i++) {
            LeaderboardRow row = unranked.get(i);
            int rank = i > 0 && Math.abs(row.revenue() - unranked.get(i - 1).revenue()) < Money.HALF_CENT ? ranked.get(i - 1).rank() : i + 1;
            ranked.add(new LeaderboardRow(rank, row.memberId(), row.name(), row.admissions(), row.sales(), row.revenue()));
        }
        return ranked;
    }
}
