package com.fraternity.dues;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Charges page: every itemized charge on every member's dues account in one ledger, with the tools that apply
 * charges (one member, several members at once, fines and discounts with saved presets) and the
 * "Template for Greekbill charges" export. The forms post to the same routes the Dues page used, so every charge still
 * lands on the member's dues account, balance and past-due amount exactly as before.
 */
@Controller
public class ChargesController {
    private final Store store;

    public ChargesController(Store store) {
        this.store = store;
    }

    /** One charge as the ledger shows it. */
    public record ChargeRow(String memberId, String memberName, String roster, String chargeId, String description,
                            String date, String dueDate, double amount, double remaining, double pastDue,
                            String status, String tone, boolean lateFee) {
        public boolean isPaid() { return remaining < Money.HALF_CENT; }
        /** "Sep 28, 2026", or a dash when there is no due date. */
        public String getDueText() { return readable(dueDate, "—"); }
        public String getAppliedText() { return readable(date, "unknown date"); }
        /** Everything the search box matches: member, charge, roster and status. */
        public String getSearchText() {
            return (memberName + " " + description + " " + roster + " " + status).toLowerCase(java.util.Locale.ROOT);
        }
    }

    @GetMapping("/charges")
    public String charges(@RequestParam(defaultValue = "") String member, Model model) {
        LocalDate today = LocalDate.now();
        List<Member> members = new ArrayList<>(store.getMembers());
        members.sort(Comparator.comparing(m -> m.getName() == null ? "" : m.getName(), String.CASE_INSENSITIVE_ORDER));
        Member focus = member.isBlank() ? null : members.stream().filter(m -> member.equals(m.getId())).findFirst().orElse(null);

        List<ChargeRow> rows = new ArrayList<>();
        for (Member m : members) {
            for (ChargeRecord charge : m.getCharges() == null ? List.<ChargeRecord>of() : m.getCharges()) {
                rows.add(row(m, charge, today));
            }
        }
        // Newest first, then by member, so the charge just applied is at the top.
        rows.sort(Comparator.comparing((ChargeRow row) -> row.date() == null ? "" : row.date()).reversed()
                .thenComparing(ChargeRow::memberName, String.CASE_INSENSITIVE_ORDER));

        YearMonth month = YearMonth.from(today);
        double chargedThisMonth = rows.stream().filter(row -> row.date() != null && row.date().startsWith(month.toString()))
                .mapToDouble(ChargeRow::amount).sum();
        long chargesThisMonth = rows.stream().filter(row -> row.date() != null && row.date().startsWith(month.toString())).count();
        double outstanding = rows.stream().mapToDouble(ChargeRow::remaining).sum();
        long openCount = rows.stream().filter(row -> !row.isPaid()).count();
        double pastDue = rows.stream().mapToDouble(ChargeRow::pastDue).sum();
        long pastDueCount = rows.stream().filter(row -> row.pastDue() >= Money.HALF_CENT).count();
        long membersWithOpen = rows.stream().filter(row -> !row.isPaid()).map(ChargeRow::memberId).distinct().count();

        model.addAttribute("chargeRows", rows);
        model.addAttribute("rosterMembers", members);
        model.addAttribute("focusMember", focus);
        model.addAttribute("chargePresets", store.getChargePresets());
        model.addAttribute("chargedThisMonth", Money.cents(chargedThisMonth));
        model.addAttribute("chargesThisMonth", chargesThisMonth);
        model.addAttribute("monthLabel", month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US));
        model.addAttribute("outstandingOnCharges", Money.cents(outstanding));
        model.addAttribute("openChargeCount", openCount);
        model.addAttribute("pastDueOnCharges", Money.cents(pastDue));
        model.addAttribute("pastDueChargeCount", pastDueCount);
        model.addAttribute("membersWithOpenCharges", membersWithOpen);
        model.addAttribute("today", today.toString());
        model.addAttribute("back", focus == null ? "/charges" : "/charges?member=" + focus.getId());
        model.addAttribute("settings", store.getSettings());
        return "charges";
    }

    private static String readable(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value)
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US));
        } catch (java.time.format.DateTimeParseException e) {
            return value;
        }
    }

    static ChargeRow row(Member member, ChargeRecord charge, LocalDate today) {
        double remaining = charge.getRemainingAmount();
        String status;
        String tone;
        if (remaining < Money.HALF_CENT) {
            status = "Paid";
            tone = "paid";
        } else if (charge.getPastDueAmount() >= Money.HALF_CENT) {
            status = "Past due";
            tone = "delinquent";
        } else if (charge.getDueDate() == null || charge.getDueDate().isBlank()) {
            status = "Open";
            tone = "neutral";
        } else if (charge.getDueDate().equals(today.toString())) {
            status = "Due today";
            tone = "warning";
        } else {
            status = "Upcoming";
            tone = "plan";
        }
        return new ChargeRow(member.getId(), member.getName(), member.getMembershipStatus(), charge.getId(),
                charge.getDescription() == null || charge.getDescription().isBlank() ? "Charge" : charge.getDescription(),
                charge.getDate(), charge.getDueDate(), charge.getAmount(), remaining, charge.getPastDueAmount(),
                status, tone, charge.getLateFeeForChargeId() != null);
    }
}
