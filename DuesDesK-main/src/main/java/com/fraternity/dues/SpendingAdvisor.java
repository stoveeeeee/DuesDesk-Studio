package com.fraternity.dues;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure calculations behind the IHQ bill stat and the Numbers-page spending calculator. */
public final class SpendingAdvisor {

    private SpendingAdvisor() {}

    public record IhqProgress(double bill, double paidOff, double remaining, double percent) {}

    public record Advice(boolean approve, String verdict, double expense, double averageIncome,
                         double percentOfIncome, double limitPercent, List<String> reasons,
                         boolean balanceAware, double currentCash, double projectedCash,
                         double recoveryAmount, String purpose, String urgency,
                         String purposeCategory, String recommendation,
                         List<String> recoveryActions, List<String> proceedActions) {}

    private record PurposeAssessment(String category, String group) {}

    /** Dues payments dated on/after {@code startDate} (blank = all recorded) come off the IHQ bill first. */
    public static IhqProgress ihqProgress(List<Member> members, double bill, String startDate) {
        LocalDate start = null;
        try {
            if (startDate != null && !startDate.isBlank()) start = LocalDate.parse(startDate);
        } catch (DateTimeParseException ignored) {
            // Treat an unreadable date as "count everything".
        }
        double collected = 0;
        for (Member member : members) {
            if (member.getPaymentHistory() == null) continue;
            for (PaymentRecord payment : member.getPaymentHistory()) {
                if (start != null) {
                    LocalDate paymentDate = parseDate(payment.getDate());
                    if (paymentDate == null || paymentDate.isBefore(start)) continue;
                }
                collected += payment.getAmount();
            }
        }
        if (!(bill > 0)) return new IhqProgress(0, 0, 0, 0);
        double paidOff = Math.min(bill, collected);
        return new IhqProgress(bill, paidOff, bill - paidOff, paidOff / bill * 100);
    }

    /** Average of monthly dues receipts across months with payments; falls back to this month's inflow. */
    public static double averageMonthlyIncome(List<Member> members) {
        Map<YearMonth, Double> byMonth = new HashMap<>();
        for (Member member : members) {
            if (member.getPaymentHistory() == null) continue;
            for (PaymentRecord payment : member.getPaymentHistory()) {
                LocalDate date = parseDate(payment.getDate());
                if (date != null) byMonth.merge(YearMonth.from(date), payment.getAmount(), Double::sum);
            }
        }
        if (byMonth.isEmpty()) return members.stream().mapToDouble(Member::getPaidThisMonth).sum();
        return byMonth.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public static Advice evaluate(double expense, double averageIncome, double collectionRate,
                                  double pastDue, double ihqRemaining) {
        return evaluate(expense, averageIncome, collectionRate, pastDue, ihqRemaining,
                0, "", "routine", false);
    }

    public static Advice evaluate(double expense, double averageIncome, double collectionRate,
                                  double pastDue, double ihqRemaining, double currentCash,
                                  String purpose, String urgency) {
        return evaluate(expense, averageIncome, collectionRate, pastDue, ihqRemaining,
                currentCash, purpose, urgency, true);
    }

    private static Advice evaluate(double expense, double averageIncome, double collectionRate,
                                   double pastDue, double ihqRemaining, double currentCash,
                                   String purpose, String urgency, boolean balanceAware) {
        List<String> reasons = new ArrayList<>();
        List<String> recoveryActions = new ArrayList<>();
        List<String> proceedActions = new ArrayList<>();
        double projectedCash = balanceAware ? Money.cents(currentCash - expense) : 0;
        double recoveryAmount = balanceAware ? Money.cents(Math.max(0, -projectedCash)) : 0;
        PurposeAssessment purposeAssessment = assessPurpose(purpose);
        if (purpose != null && !purpose.isBlank()) {
            reasons.add("Purpose: " + purpose.trim() + ".");
        }
        if (balanceAware) {
            reasons.add("Urgency selected: " + urgencyDescription(urgency) + ".");
            reasons.add("Purpose category: " + purposeAssessment.category() + ".");
            reasons.add(String.format(Locale.US, "Current spendable cash is $%,.2f; after this expense it would be $%,.2f.",
                    currentCash, projectedCash));
            if (currentCash < 0) {
                reasons.add(String.format(Locale.US, "The chapter is already operating $%,.2f below its committed obligations.",
                        -currentCash));
            }
        }

        if (!(averageIncome > 0)) {
            reasons.add("No recorded monthly income yet, so there is nothing to measure this expense against.");
        } else {
            double percent = expense / averageIncome * 100;
            double limit = 25;
            reasons.add(String.format(Locale.US, "This is %.1f%% of average monthly income ($%,.2f).", percent, averageIncome));
            if (collectionRate < 80) {
                limit -= 7.5;
                reasons.add(String.format(Locale.US, "Collection rate is only %.1f%% (below 80%%), so income may fall short.", collectionRate));
            }
            if (pastDue >= averageIncome * 0.5) {
                limit -= 7.5;
                reasons.add(String.format(Locale.US, "$%,.2f is past due — at least half of a typical month's income.", pastDue));
            }
            if (ihqRemaining > averageIncome) {
                limit -= 7.5;
                reasons.add(String.format(Locale.US, "$%,.2f of the IHQ bill is still unpaid — more than a month of income.", ihqRemaining));
            }
            limit = Math.max(limit, 5);
            reasons.add(String.format(Locale.US, "This expense is %.1f%% of average monthly income; the calculator's cautious benchmark is %.1f%%.",
                    percent, limit));
            boolean balanceAllows = !balanceAware || projectedCash >= 0;
            boolean withinIncomeBenchmark = percent <= limit;
            boolean purposeIsClear = !"unclear".equals(purposeAssessment.group());
            boolean urgencyMatchesPurpose = !("discretionary".equals(purposeAssessment.group())
                    && ("urgent".equals(urgency) || "emergency".equals(urgency)));
            boolean approve = withinIncomeBenchmark && balanceAllows
                    && (!balanceAware || (purposeIsClear && urgencyMatchesPurpose));
            if (balanceAware && !balanceAllows) {
                reasons.add(String.format(Locale.US, "This expense would leave a $%,.2f gap after scheduled fees and the remaining IHQ bill.", recoveryAmount));
            }
            if (balanceAware && !purposeIsClear) {
                reasons.add("The description does not clearly identify the type of purchase, so confirm its purpose before treating this result as approval.");
            }
            if (balanceAware && !urgencyMatchesPurpose) {
                reasons.add("The selected urgency does not appear to match the described discretionary purchase; verify the deadline and necessity.");
            }
            if (balanceAware && recoveryAmount > 0) {
                addRecoveryActions(recoveryActions, recoveryAmount, urgency, purposeAssessment);
            }
            String recommendation = balanceAware
                    ? recommendation(approve, withinIncomeBenchmark, balanceAllows, purposeIsClear,
                            urgencyMatchesPurpose, purposeAssessment, urgency)
                    : verdict(approve, false, 0, urgency);
            if (balanceAware) {
                addProceedActions(proceedActions, purposeAssessment, urgency, recoveryAmount);
            }
            return new Advice(approve, recommendation, expense, averageIncome, percent, limit, reasons,
                    balanceAware, currentCash, projectedCash, recoveryAmount, purpose, urgency,
                    purposeAssessment.category(), recommendation, recoveryActions, proceedActions);
        }

        if (balanceAware && recoveryAmount > 0) {
            addRecoveryActions(recoveryActions, recoveryAmount, urgency, purposeAssessment);
        }
        if (balanceAware) {
            addProceedActions(proceedActions, purposeAssessment, urgency, recoveryAmount);
        }
        String recommendation = balanceAware
                ? "Do not commit yet — verify the chapter's income and confirm an affordable funding plan."
                : verdict(false, balanceAware, recoveryAmount, urgency);
        return new Advice(false, recommendation,
                expense, 0, 0, 0, reasons, balanceAware, currentCash, projectedCash,
                recoveryAmount, purpose, urgency, purposeAssessment.category(), recommendation,
                recoveryActions, proceedActions);
    }

    private static String verdict(boolean approve, boolean balanceAware, double recoveryAmount, String urgency) {
        if (balanceAware && recoveryAmount > 0) {
            if ("urgent".equals(urgency) || "emergency".equals(urgency)) {
                return "Urgent need — contain the cost and close the funding gap";
            }
            if ("soon".equals(urgency)) {
                return "Needed soon — negotiate the cost and close the funding gap";
            }
            return "Defer if possible — restore positive operating cash first";
        }
        return approve ? "Go ahead and spend it" : "Don't spend it";
    }

    private static String recommendation(boolean approve, boolean withinIncomeBenchmark,
                                        boolean balanceAllows, boolean purposeIsClear,
                                        boolean urgencyMatchesPurpose, PurposeAssessment purpose,
                                        String urgency) {
        if (!balanceAllows) {
            if ("emergency".equals(urgency) && "essential".equals(purpose.group())) {
                return "Do not approve the full purchase as quoted. If the emergency is verified, consider only the minimum safe fix with an approved funding plan.";
            }
            return switch (urgency) {
                case "urgent" -> "Do not commit at this price — negotiate, reduce the scope, or secure funding first.";
                case "emergency" -> "Do not commit the full expense from operating funds — contain the cost and agree a funding plan first.";
                case "soon" -> "Defer or renegotiate — the purchase would leave required obligations unfunded.";
                default -> "Do not purchase now — restore positive operating cash first.";
            };
        }
        if (!purposeIsClear) {
            return "Pause for review — clarify the purchase purpose and confirm it is necessary before committing.";
        }
        if (!urgencyMatchesPurpose) {
            return "Pause for review — verify why this discretionary purchase cannot wait.";
        }
        if (!withinIncomeBenchmark) {
            return "Do not approve at this amount yet — seek a lower-cost option or a separately approved funding plan.";
        }
        if ("revenue".equals(purpose.group())) {
            return "Conditionally proceed — confirm the expected net return, cap the budget, and obtain chapter approval before committing.";
        }
        if ("urgent".equals(urgency) || "emergency".equals(urgency)) {
            return "Financially appears affordable — proceed only after verifying the need and obtaining required chapter approval.";
        }
        if ("soon".equals(urgency)) {
            return "Financially appears affordable — confirm the deadline and obtain required chapter approval before committing.";
        }
        return "Financially appears affordable — proceed only after normal chapter approval and a final balance check.";
    }

    private static PurposeAssessment assessPurpose(String purpose) {
        String text = purpose == null ? "" : purpose.toLowerCase(Locale.ROOT);
        if (containsAny(text, "fire", "flood", "gas leak", "water leak", "electrical hazard",
                "safety", "injury", "emergency", "urgent repair", "broken pipe")) {
            return new PurposeAssessment("Safety-critical or emergency repair", "essential");
        }
        if (containsAny(text, "insurance", "permit", "license", "licence", "compliance",
                "tax", "legal", "required fee", "ihq", "headquarters")) {
            return new PurposeAssessment("Compliance or required obligation", "essential");
        }
        if (containsAny(text, "utility", "utilities", "rent", "venue", "maintenance", "repair",
                "equipment", "supplies", "plumbing", "roof", "heating", "cooling", "software")) {
            return new PurposeAssessment("Core chapter operations", "essential");
        }
        if (containsAny(text, "fundraiser", "fundraising", "ticket sales", "merchandise",
                "advertising", "sponsorship", "revenue", "sales campaign")) {
            return new PurposeAssessment("Revenue-generating activity", "revenue");
        }
        if (containsAny(text, "party", "social", "banquet", "decoration", "decor", "gift",
                "swag", "entertainment", "recreational", "optional trip", "retreat")) {
            return new PurposeAssessment("Discretionary or member-experience expense", "discretionary");
        }
        return new PurposeAssessment("Needs officer review", "unclear");
    }

    private static boolean containsAny(String value, String... phrases) {
        for (String phrase : phrases) {
            if (value.contains(phrase)) return true;
        }
        return false;
    }

    private static String urgencyDescription(String urgency) {
        return switch (urgency) {
            case "soon" -> "Needed soon";
            case "urgent" -> "Urgent deadline or essential operations";
            case "emergency" -> "Emergency affecting safety or critical operations";
            default -> "Routine; can wait";
        };
    }

    private static void addRecoveryActions(List<String> actions, double recoveryAmount, String urgency,
                                           PurposeAssessment purpose) {
        actions.add(String.format(Locale.US,
                "Secure at least $%,.2f in confirmed net cash or cost reductions to return projected operating cash to $0; aim for a surplus buffer.",
                recoveryAmount));
        actions.add("Ask the vendor for a lower-cost or phased option, payment terms, or an extension; identify which nonessential costs can be paused.");
        if ("revenue".equals(purpose.group())) {
            actions.add("Recheck the expected income, direct costs, break-even point, and worst-case loss; do not count projected sales as cash.");
        }
        if ("soon".equals(urgency)) {
            actions.add("Confirm the real deadline and ask the vendor or payee for a short extension while the chapter arranges funding.");
        }
        actions.add("Confirm scheduled obligations and approach the treasurer about approved funding; count dues only after payment is received.");
        if ("emergency".equals(urgency)) {
            actions.add("If a safety or critical-operations emergency is verified, limit any immediate payment to the minimum safe remedy and obtain prompt documented officer/chapter approval.");
        } else if ("urgent".equals(urgency)) {
            actions.add("Escalate the deadline to the treasurer/chapter leadership and get approval for a documented funding plan before committing.");
        }
    }

    private static void addProceedActions(List<String> actions, PurposeAssessment purpose,
                                          String urgency, double recoveryAmount) {
        actions.add("Verify the invoice, deadline, and the minimum scope that meets the stated need; compare alternatives where time allows.");
        if (recoveryAmount > 0) {
            actions.add(String.format(Locale.US,
                    "Before committing, name an approved funding source or cost reduction covering at least $%,.2f; do not rely on unpaid dues or hoped-for revenue.",
                    recoveryAmount));
        } else {
            actions.add("Confirm the latest reconciled cash still covers scheduled fees, the remaining IHQ bill, and the chapter's chosen reserve.");
        }
        if ("revenue".equals(purpose.group())) {
            actions.add("Set a written spending cap and track actual proceeds against direct costs before authorizing additional spending.");
        } else if ("essential".equals(purpose.group())) {
            actions.add("For urgent work, document why it cannot wait and get the required treasurer/chapter approval; for an emergency, authorize only the minimum safe remedy.");
        } else {
            actions.add("Get the required chapter approval and confirm the purchase is a priority over other planned spending.");
        }
        if ("urgent".equals(urgency) || "emergency".equals(urgency)) {
            actions.add("Record who approved the exception, the deadline, and the confirmed funding source.");
        }
        actions.add("After payment, record and reconcile the expense, then rerun this check against the updated balance.");
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) return null;
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
