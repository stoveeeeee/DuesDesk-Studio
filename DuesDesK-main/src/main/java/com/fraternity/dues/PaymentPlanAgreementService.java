package com.fraternity.dues;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.fraternity.dues.ExecutivePdf.Cell;
import com.fraternity.dues.ExecutivePdf.Column;
import com.fraternity.dues.ExecutivePdf.InfoBlock;
import com.fraternity.dues.ExecutivePdf.SummaryLine;
import com.fraternity.dues.ExecutivePdf.Tone;

/**
 * A printable payment plan agreement (promissory note) for a member on installments: chapter and member details, the
 * balance the plan covers, the itemized installment schedule, the late-fee and social-clearance policy in force, and
 * signature lines for the member and the treasurer. Pure Java (PDFBox standard fonts), identical on every OS.
 */
public class PaymentPlanAgreementService {
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    /** True when the member has an installment schedule to put in an agreement. */
    public static boolean hasPlan(Member member) {
        return member != null && member.getInstallments() != null && !member.getInstallments().isEmpty();
    }

    public byte[] agreementPdf(Member member, AppSettings settings, LocalDate today) throws IOException {
        if (!hasPlan(member)) throw new IllegalArgumentException("This member has no installment plan to put in an agreement.");
        List<PaymentInstallment> schedule = new ArrayList<>(member.getInstallments());
        schedule.sort(Comparator.comparing(installment -> installment.getDueDate() == null ? "" : installment.getDueDate()));
        double covered = Money.cents(schedule.stream().mapToDouble(PaymentInstallment::getAmount).sum());
        double paid = Money.cents(schedule.stream().mapToDouble(PaymentInstallment::getPaidAmount).sum());
        double remaining = Money.cents(schedule.stream().mapToDouble(PaymentInstallment::getRemainingAmount).sum());
        String chapter = settings.getChapterName().isBlank() ? "the Chapter" : settings.getChapterName().trim();
        boolean overdue = schedule.stream().anyMatch(PaymentInstallment::isOverdue);
        boolean complete = remaining < Money.HALF_CENT;

        ExecutivePdf pdf = new ExecutivePdf(settings.getChapterName(), "Payment plan agreement - " + member.getName(), false);
        pdf.letterhead("Payment Plan Agreement  |  " + (settings.getChapterName().isBlank() ? "Chapter dues" : chapter),
                "Payment Plan Agreement", List.of(
                        new String[] {"Prepared", today.format(LONG_DATE)},
                        new String[] {"Member", member.getName()},
                        new String[] {"Installments", String.valueOf(schedule.size())}
                ), complete ? "Paid" : overdue ? "Overdue" : "Active", complete ? Tone.SUCCESS : overdue ? Tone.DANGER : Tone.INDIGO);

        List<String> memberLines = new ArrayList<>();
        memberLines.add(member.getName());
        memberLines.add(member.getPhoneNumber());
        if (!member.getEmail().isBlank()) memberLines.add(member.getEmail());
        pdf.infoBand(List.of(
                new InfoBlock("Parties", List.of(chapter, "and member " + member.getName()), null, null),
                new InfoBlock("Member", memberLines, null, null),
                InfoBlock.callout("Still to be paid", money(remaining), complete ? ExecutivePdf.GREEN : ExecutivePdf.INK)
        ));

        pdf.section("Balance covered by this plan");
        pdf.pair("Total of all installments", money(covered));
        pdf.pair("Still to be paid under the plan", money(remaining));
        pdf.pair("Member's current account balance", money(member.getTotalOwed()));
        pdf.space(4);

        pdf.section("Installment schedule");
        float w = pdf.width();
        List<List<Cell>> rows = new ArrayList<>();
        for (int i = 0; i < schedule.size(); i++) {
            PaymentInstallment installment = schedule.get(i);
            boolean isPaid = installment.getRemainingAmount() < Money.HALF_CENT;
            boolean partly = !isPaid && installment.getPaidAmount() >= Money.HALF_CENT;
            String status = isPaid ? "Paid" : partly ? "Partly paid (" + money(installment.getRemainingAmount()) + " left)" : "Due";
            rows.add(List.of(Cell.muted(String.valueOf(i + 1)), Cell.bold(dateText(installment.getDueDate())), Cell.of(installment.getDescription()),
                    Cell.bold(money(installment.getAmount())), Cell.pill(isPaid ? "Paid" : partly ? "Partial" : installment.isOverdue() ? "Overdue" : "Due",
                            isPaid ? Tone.SUCCESS : partly ? Tone.WARNING : installment.isOverdue() ? Tone.DANGER : Tone.NEUTRAL),
                    Cell.muted(status)));
        }
        pdf.table(List.of(new Column("#", w * 0.06f, false), new Column("Due date", w * 0.17f, false), new Column("Description", w * 0.31f, false),
                new Column("Amount", w * 0.14f, true), new Column("Status", w * 0.12f, true), new Column("Detail", w * 0.20f, true)), rows, null);

        pdf.section("Terms");
        pdf.paragraph("The member agrees to pay " + chapter + " each installment above in full on or before its due date. "
                + "Payments are applied to the earliest unpaid installment first. The member may pay ahead at any time "
                + "without penalty. This agreement covers the balance shown above; charges added later are billed separately "
                + "unless the plan is updated in writing.");
        pdf.space(4);
        pdf.paragraph(lateFeeTerms(settings));
        pdf.space(4);
        pdf.paragraph("Social standing: while every installment is paid on time, the member is treated as in good standing "
                + "for chapter social events. If an installment becomes overdue, the member is not socially cleared until "
                + "the overdue amount is paid" + (settings.getSocialClearanceTolerance() > 0
                ? " (unpaid balances of " + money(settings.getSocialClearanceTolerance()) + " or less are tolerated)." : ".")
                + " The treasurer may grant a temporary clearance in writing.");
        pdf.space(12);

        pdf.totals("Acknowledgement", List.of(
                "By signing, the member accepts the installment schedule and the terms above, and the treasurer confirms the plan on behalf of " + chapter + ".",
                ""
        ), List.of(new String[] {"Member signature", member.getName()}, new String[] {"Treasurer signature", "Treasurer, " + chapter}), List.of(
                new SummaryLine("Total of all installments", money(covered)),
                new SummaryLine("Paid so far", "-" + money(paid)),
                new SummaryLine("Current account balance", money(member.getTotalOwed())),
                new SummaryLine("Still to be paid", money(remaining), true)
        ));
        return pdf.finish();
    }

    private String lateFeeTerms(AppSettings settings) {
        if (!settings.isLateFeesEnabled() || settings.getLateFeeAmount() <= 0) {
            return "Late fees: no automatic late fee is currently charged for a late installment.";
        }
        String fee = "PERCENT".equals(settings.getLateFeeType())
                ? String.format(Locale.US, "%s%% of the unpaid amount", trim(settings.getLateFeeAmount()))
                : money(settings.getLateFeeAmount());
        int grace = settings.getLateFeeGraceDays();
        return "Late fees: a one-time late fee of " + fee + " is added " + grace + (grace == 1 ? " day" : " days")
                + " after the due date to any itemized charge still unpaid. Late fees do not compound.";
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.US, "%.2f", value);
    }

    private static String money(double amount) {
        return String.format(Locale.US, "$%,.2f", amount);
    }

    private static String dateText(String iso) {
        try {
            return LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US));
        } catch (Exception e) {
            return iso == null ? "" : iso;
        }
    }
}
