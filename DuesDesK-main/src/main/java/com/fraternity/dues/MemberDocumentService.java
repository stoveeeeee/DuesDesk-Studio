package com.fraternity.dues;

import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fraternity.dues.ExecutivePdf.Cell;
import com.fraternity.dues.ExecutivePdf.Column;
import com.fraternity.dues.ExecutivePdf.InfoBlock;
import com.fraternity.dues.ExecutivePdf.SummaryLine;
import com.fraternity.dues.ExecutivePdf.Tone;

/** Member-facing documents: the account statement and the payment receipt (PDF), plus the statement spreadsheet. */
public class MemberDocumentService {
    private record StatementRow(String date, String type, String description, double amount) {}

    private final java.util.function.Supplier<String> chapterName;

    public MemberDocumentService() { this(() -> ""); }

    /** The chapter name is read when a document is made, so a renamed chapter shows up on the next statement. */
    public MemberDocumentService(java.util.function.Supplier<String> chapterName) { this.chapterName = chapterName == null ? () -> "" : chapterName; }

    public byte[] statementPdf(Member member) throws IOException {
        List<PaymentRecord> payments = new ArrayList<>(member.getPaymentHistory() == null ? List.of() : member.getPaymentHistory());
        payments.sort(Comparator.comparing(payment -> payment.getDate() == null ? "" : payment.getDate()));
        List<MemberCredit> credits = new ArrayList<>(member.getCredits() == null ? List.of() : member.getCredits());
        credits.sort(Comparator.comparing(credit -> credit.getDate() == null ? "" : credit.getDate()));
        List<ChargeRecord> charges = new ArrayList<>(member.getCharges() == null ? List.of() : member.getCharges());
        charges.sort(Comparator.comparing(charge -> charge.getDueDate() == null ? nullToEmpty(charge.getDate()) : charge.getDueDate()));
        List<PaymentInstallment> installments = new ArrayList<>(member.getInstallments() == null ? List.of() : member.getInstallments());
        installments.sort(Comparator.comparing(installment -> nullToEmpty(installment.getDueDate())));

        double paid = payments.stream().mapToDouble(PaymentRecord::getAmount).sum();
        double credited = credits.stream().mapToDouble(MemberCredit::getAmount).sum();
        double charged = charges.stream().mapToDouble(ChargeRecord::getAmount).sum();
        boolean settled = member.getTotalOwed() < Money.HALF_CENT;
        Tone tone = settled ? Tone.SUCCESS : member.getPastDueBalance() > 0 ? Tone.DANGER : Tone.WARNING;
        String status = settled ? "Paid" : member.getPastDueBalance() > 0 ? "Past due" : "Unpaid";

        ExecutivePdf pdf = new ExecutivePdf(chapterName.get(), "Member Statement - " + safe(member.getName()), false);
        pdf.letterhead("Member Statement  |  " + safe(member.getName()), "Member Statement", List.of(
                new String[] {"Statement date", LocalDate.now().toString()},
                new String[] {"Member", safe(member.getName())},
                new String[] {"Account status", nullToEmpty(member.getStatus())}
        ), status, tone);
        pdf.infoBand(List.of(
                InfoBlock.of("Prepared for", safe(member.getName()), nullToEmpty(member.getPhoneNumber()),
                        member.getEmail().isBlank() ? nullToEmpty(member.getStatus()) : member.getEmail()),
                InfoBlock.of("Account details", "Status: " + nullToEmpty(member.getStatus()),
                        "Past due: " + money(member.getPastDueBalance()), "Paid this month: " + money(member.getPaidThisMonth())),
                InfoBlock.callout("Current balance", money(member.getTotalOwed()), settled ? ExecutivePdf.GREEN : ExecutivePdf.INK)
        ));

        float w = pdf.width();
        pdf.section("Payment history");
        List<List<Cell>> paymentRows = new ArrayList<>();
        for (PaymentRecord payment : payments) {
            paymentRows.add(List.of(Cell.muted(payment.getDate()), Cell.of(payment.getDescription()),
                    Cell.pill("Received", Tone.SUCCESS), Cell.colored(money(payment.getAmount()), ExecutivePdf.GREEN)));
        }
        pdf.table(List.of(new Column("Date", w * 0.18f, false), new Column("Description", w * 0.47f, false),
                new Column("Status", w * 0.15f, false), new Column("Amount", w * 0.20f, true)), paymentRows, "No payments recorded.");

        if (!credits.isEmpty()) {
            pdf.section("Applied dues credits");
            List<List<Cell>> creditRows = new ArrayList<>();
            for (MemberCredit credit : credits) {
                creditRows.add(List.of(Cell.muted(credit.getDate()), Cell.of(credit.getDescription()),
                        Cell.pill("Credit", Tone.INDIGO), Cell.colored("-" + money(credit.getAmount()), ExecutivePdf.GREEN)));
            }
            pdf.table(List.of(new Column("Date", w * 0.18f, false), new Column("Description", w * 0.47f, false),
                    new Column("Type", w * 0.15f, false), new Column("Credit", w * 0.20f, true)), creditRows, null);
        }

        pdf.section("Charges");
        List<List<Cell>> chargeRows = new ArrayList<>();
        for (ChargeRecord charge : charges) {
            boolean chargePaid = charge.getRemainingAmount() < Money.HALF_CENT;
            String chargeStatus = chargePaid ? "Paid" : charge.getPastDueAmount() > 0 ? "Overdue" : charge.getPaidAmount() >= Money.HALF_CENT ? "Partial" : "Open";
            Tone chargeTone = chargePaid ? Tone.SUCCESS : charge.getPastDueAmount() > 0 ? Tone.DANGER : charge.getPaidAmount() >= Money.HALF_CENT ? Tone.WARNING : Tone.NEUTRAL;
            chargeRows.add(List.of(Cell.muted(charge.getDueDate() == null ? charge.getDate() : charge.getDueDate()), Cell.of(charge.getDescription()),
                    Cell.pill(chargeStatus, chargeTone), Cell.bold(money(charge.getAmount()))));
        }
        pdf.table(List.of(new Column("Due", w * 0.18f, false), new Column("Description", w * 0.47f, false),
                new Column("Status", w * 0.15f, false), new Column("Amount", w * 0.20f, true)), chargeRows, "No itemized charges recorded.");

        pdf.section("Installments");
        List<List<Cell>> installmentRows = new ArrayList<>();
        for (PaymentInstallment installment : installments) {
            String label = installment.isPaid() ? "Paid" : installment.isOverdue() ? "Overdue" : installment.getPaidAmount() >= Money.HALF_CENT ? "Partial" : "Due";
            Tone installmentTone = installment.isPaid() ? Tone.SUCCESS : installment.isOverdue() ? Tone.DANGER : installment.getPaidAmount() >= Money.HALF_CENT ? Tone.WARNING : Tone.NEUTRAL;
            installmentRows.add(List.of(Cell.muted(installment.getDueDate()), Cell.of(installment.getDescription()), Cell.pill(label, installmentTone),
                    Cell.bold(money(installment.getAmount())), Cell.colored(money(installment.getRemainingAmount()), installment.isPaid() ? ExecutivePdf.GREEN : ExecutivePdf.INK)));
        }
        pdf.table(List.of(new Column("Due date", w * 0.16f, false), new Column("Description", w * 0.38f, false), new Column("Status", w * 0.14f, false),
                new Column("Amount", w * 0.16f, true), new Column("Remaining", w * 0.16f, true)), installmentRows, "No payment plan installments recorded.");

        pdf.space(6);
        pdf.totals("Payment instructions", List.of(
                "Pay the balance due through the chapter's listed payment accounts, or hand a payment to the treasurer so it can be recorded against this account.",
                "Payments apply to the earliest unpaid charge or installment first. Questions about any line above go to the chapter treasurer."
        ), List.of(), List.of(
                new SummaryLine("Total charged", money(charged)),
                new SummaryLine("Payments received", "-" + money(paid)),
                new SummaryLine("Dues credits", "-" + money(credited)),
                new SummaryLine("Past due", money(member.getPastDueBalance())),
                new SummaryLine("Balance due", money(member.getTotalOwed()), true)
        ));
        return pdf.finish();
    }

    public byte[] receiptPdf(Member member, PaymentRecord payment) throws IOException {
        ExecutivePdf pdf = new ExecutivePdf(chapterName.get(), "Receipt - " + safe(member.getName()), false);
        pdf.letterhead("Payment Receipt  |  " + safe(member.getName()), "Receipt", List.of(
                new String[] {"Receipt reference", nullToEmpty(payment.getIdempotencyKey()).isBlank() ? "-" : safe(payment.getIdempotencyKey())},
                new String[] {"Payment date", safe(payment.getDate())},
                new String[] {"Issued", LocalDate.now().toString()}
        ), "Paid", Tone.SUCCESS);
        pdf.infoBand(List.of(
                InfoBlock.of("Received from", safe(member.getName()), nullToEmpty(member.getPhoneNumber()),
                        member.getEmail().isBlank() ? nullToEmpty(member.getStatus()) : member.getEmail()),
                InfoBlock.of("Payment details", "Date: " + safe(payment.getDate()),
                        "Description: " + (nullToEmpty(payment.getDescription()).isBlank() ? "Payment" : safe(payment.getDescription())),
                        "Reference: " + (nullToEmpty(payment.getIdempotencyKey()).isBlank() ? "-" : safe(payment.getIdempotencyKey()))),
                InfoBlock.callout("Amount received", money(payment.getAmount()), ExecutivePdf.GREEN)
        ));
        float w = pdf.width();
        pdf.section("Itemized payment");
        pdf.table(List.of(new Column("Date", w * 0.18f, false), new Column("Description", w * 0.47f, false),
                new Column("Status", w * 0.15f, false), new Column("Amount", w * 0.20f, true)), List.of(List.of(
                Cell.muted(payment.getDate()), Cell.of(nullToEmpty(payment.getDescription()).isBlank() ? "Payment" : payment.getDescription()),
                Cell.pill("Received", Tone.SUCCESS), Cell.colored(money(payment.getAmount()), ExecutivePdf.GREEN))), null);
        pdf.space(6);
        pdf.totals("Notes", List.of(
                "Receipt reference: " + (nullToEmpty(payment.getIdempotencyKey()).isBlank() ? "-" : safe(payment.getIdempotencyKey())),
                "Thank you. This receipt confirms the payment above was recorded on the member's chapter account.",
                "Remaining account balance after all recorded activity: " + money(member.getTotalOwed()) + "."
        ), List.of(), List.of(
                new SummaryLine("Payment date", safe(payment.getDate())),
                new SummaryLine("Account balance", money(member.getTotalOwed())),
                new SummaryLine("Amount received", money(payment.getAmount()), true)
        ));
        return pdf.finish();
    }

    public byte[] statementCsv(Member member) {
        StringBuilder csv = new StringBuilder("Date,Type,Description,Amount\n");
        java.util.ArrayList<StatementRow> rows = new java.util.ArrayList<>();
        List<PaymentRecord> payments = member.getPaymentHistory() == null ? List.of() : member.getPaymentHistory();
        for (PaymentRecord payment : payments) {
            rows.add(new StatementRow(payment.getDate(), "Payment", payment.getDescription(), payment.getAmount()));
        }
        if (member.getCredits() != null) {
            for (MemberCredit credit : member.getCredits()) {
                rows.add(new StatementRow(credit.getDate(), "Dues credit", credit.getDescription(), -credit.getAmount()));
            }
        }
        if (member.getCharges() != null) {
            for (ChargeRecord charge : member.getCharges()) {
                rows.add(new StatementRow(charge.getDueDate() == null ? charge.getDate() : charge.getDueDate(),
                        "Charge", charge.getDescription(), charge.getAmount()));
            }
        }
        rows.sort(java.util.Comparator.comparing(row -> row.date() == null ? "" : row.date()));
        for (StatementRow row : rows) {
            appendCsv(csv, row.date(), row.type(), row.description(),
                    String.format(java.util.Locale.US, "%.2f", row.amount()));
        }
        appendCsv(csv, "", "Current balance", member.getName(),
                String.format(java.util.Locale.US, "%.2f", member.getTotalOwed()));
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void appendCsv(StringBuilder csv, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) csv.append(',');
            String value = fields[i] == null ? "" : fields[i];
            // A leading = + - @ would run as a spreadsheet formula; plain numbers (amounts like -50.00) stay numeric.
            if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 && !value.matches("-?\\d+(\\.\\d+)?")) {
                value = "'" + value;
            }
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append('\n');
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private String money(double amount) {
        return String.format(java.util.Locale.US, "$%,.2f", amount);
    }

    private String safe(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return normalized.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
