package com.fraternity.dues;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
 * The formal past-due notice issued by step 3 of the reminder sequence: who it is for, the amounts as they stood on
 * the day it was issued, every unpaid item still open, how to pay, and a reply-by date. The notice keeps the figures
 * recorded when it was issued, so a copy downloaded later still matches what the member was sent.
 */
public class PastDueNoticeService {
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    static final int REPLY_DAYS = 7;

    /** The past-due notices on a member's account, newest first. */
    public static List<ReminderStep> notices(Member member) {
        List<ReminderStep> notices = new ArrayList<>(member.getReminderLog().stream()
                .filter(step -> ReminderStep.NOTICE.equals(step.getStage())).toList());
        notices.sort(Comparator.comparing(ReminderStep::getAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return notices;
    }

    public byte[] noticePdf(Member member, ReminderStep step, AppSettings settings) throws IOException {
        if (step == null || !ReminderStep.NOTICE.equals(step.getStage())) {
            throw new IllegalArgumentException("That past-due notice was not found.");
        }
        LocalDate issued = issuedOn(step);
        String chapter = settings.getChapterName().isBlank() ? "the Chapter" : settings.getChapterName().trim();
        String reference = "PDN-" + issued.toString().replace("-", "") + "-"
                + (step.getId() == null ? "0000" : step.getId().substring(0, Math.min(4, step.getId().length())).toUpperCase(Locale.ROOT));

        ExecutivePdf pdf = new ExecutivePdf(settings.getChapterName(), "Past-due notice - " + member.getName(), false);
        pdf.letterhead("Past-Due Notice  |  Dues account of " + member.getName(), "Past-Due Notice", List.of(
                new String[] {"Issued", issued.format(LONG_DATE)},
                new String[] {"Reference", reference},
                new String[] {"Reply by", issued.plusDays(REPLY_DAYS).format(LONG_DATE)}
        ), "Past due", Tone.DANGER);

        List<String> memberLines = new ArrayList<>();
        memberLines.add(member.getName());
        if (member.getPhoneNumber() != null && !member.getPhoneNumber().isBlank()) memberLines.add(member.getPhoneNumber());
        if (!member.getEmail().isBlank()) memberLines.add(member.getEmail());
        pdf.infoBand(List.of(
                new InfoBlock("Issued to", memberLines, null, null),
                new InfoBlock("Account", List.of("Total balance " + ExecutivePdf.money(step.getBalance()),
                        "Oldest item due " + date(step.getDueDate())), null, null),
                InfoBlock.callout("Amount past due", ExecutivePdf.money(step.getPastDue() > 0 ? step.getPastDue() : step.getAmount()), ExecutivePdf.RED)
        ));

        pdf.section("Notice");
        pdf.paragraph("Our records show that your dues account with " + chapter + " is past due. As of "
                + issued.format(LONG_DATE) + ", " + ExecutivePdf.money(step.getPastDue() > 0 ? step.getPastDue() : step.getAmount())
                + " is past due, and your total balance is " + ExecutivePdf.money(step.getBalance()) + ".");
        pdf.space(4);
        pdf.paragraph("Please pay the past-due amount, or contact the Treasurer to agree a payment plan, by "
                + issued.plusDays(REPLY_DAYS).format(LONG_DATE) + ". Accounts that stay past due are referred to the chapter officers for review, "
                + "which can affect social clearance.");
        pdf.space(6);

        pdf.section("Unpaid items");
        float width = pdf.width();
        List<Column> columns = List.of(
                new Column("Item", width * 0.42f, false),
                new Column("Due date", width * 0.2f, false),
                new Column("Days past due", width * 0.18f, true),
                new Column("Still owed", width * 0.2f, true));
        List<List<Cell>> rows = new ArrayList<>();
        double itemized = 0;
        for (ChargeRecord charge : member.getCharges() == null ? List.<ChargeRecord>of() : member.getCharges()) {
            if (!Money.positive(charge.getRemainingAmount())) continue;
            rows.add(itemRow(charge.getDescription(), charge.getDueDate(), charge.getRemainingAmount(), issued));
            itemized += charge.getRemainingAmount();
        }
        for (PaymentInstallment installment : member.getInstallments() == null ? List.<PaymentInstallment>of() : member.getInstallments()) {
            if (installment.isPaid()) continue;
            rows.add(itemRow(installment.getDescription() == null || installment.getDescription().isBlank()
                    ? "Payment plan installment" : installment.getDescription(), installment.getDueDate(), installment.getRemainingAmount(), issued));
        }
        double unitemized = Money.cents(member.getTotalOwed() - itemized);
        if (rows.isEmpty() || (member.getInstallments().isEmpty() && Money.positive(unitemized))) {
            rows.add(List.of(Cell.bold("Balance carried on the account"), Cell.muted("-"), Cell.muted("-"),
                    Cell.of(ExecutivePdf.money(rows.isEmpty() ? member.getTotalOwed() : unitemized))));
        }
        pdf.table(columns, rows, "No unpaid items remain on this account.");

        List<PaymentLinks.Handle> handles = settings.getChapterCashServiceHandles();
        List<String> howToPay = new ArrayList<>();
        for (PaymentLinks.Handle handle : handles) {
            if (handle.getHandle().isBlank()) continue;
            howToPay.add(handle.getServiceName() + ": " + handle.getHandle());
        }
        if (howToPay.isEmpty()) howToPay.add("Pay the Treasurer directly, or ask for the chapter's payment details.");
        howToPay.add("Put your name and \"Dues\" in the payment note" + (member.getDuesCode().isBlank() ? "." : ", or your dues code " + member.getDuesCode() + "."));
        pdf.space(4);
        pdf.totals("How to pay", howToPay, List.<String[]>of(new String[] {"Treasurer", chapter}),
                List.of(new SummaryLine("Past due", ExecutivePdf.money(step.getPastDue() > 0 ? step.getPastDue() : step.getAmount())),
                        new SummaryLine("Total balance", ExecutivePdf.money(step.getBalance()), true)));
        pdf.note("Figures are as of the notice date. Payments made since then are not shown here.", ExecutivePdf.MUTED);
        return pdf.finish();
    }

    private static List<Cell> itemRow(String description, String dueDate, double remaining, LocalDate issued) {
        LocalDate due = parse(dueDate);
        long late = due == null ? 0 : ChronoUnit.DAYS.between(due, issued);
        return List.of(Cell.bold(description == null || description.isBlank() ? "Dues charge" : description),
                Cell.of(due == null ? "-" : due.format(SHORT_DATE)),
                late > 0 ? Cell.colored(String.valueOf(late), ExecutivePdf.RED) : Cell.muted(late == 0 && due != null ? "Due" : "-"),
                Cell.of(ExecutivePdf.money(remaining)));
    }

    public static String fileName(Member member, ReminderStep step) {
        String name = member.getName() == null ? "member" : member.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return (name.isBlank() ? "member" : name) + "-past-due-notice-" + issuedOn(step) + ".pdf";
    }

    static LocalDate issuedOn(ReminderStep step) {
        try {
            return LocalDateTime.parse(step.getAt()).toLocalDate();
        } catch (RuntimeException e) {
            LocalDate day = parse(step.getDay());
            return day == null ? LocalDate.now() : day;
        }
    }

    private static String date(String value) {
        LocalDate parsed = parse(value);
        return parsed == null ? "-" : parsed.format(SHORT_DATE);
    }

    private static LocalDate parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim().length() > 10 ? value.trim().substring(0, 10) : value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
