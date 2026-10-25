package com.fraternity.dues;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.List;

public class MemberDocumentService {
    private record StatementRow(String date, String type, String description, double amount) {}

    public byte[] statementPdf(Member member) throws IOException {
        return pdf("Member Statement", member, statementLines(member));
    }

    public byte[] receiptPdf(Member member, PaymentRecord payment) throws IOException {
        return pdf("Payment Receipt", member, List.of(
                "Payment date: " + safe(payment.getDate()),
                "Amount received: " + money(payment.getAmount()),
                "Description: " + safe(payment.getDescription()),
                "Receipt reference: " + safe(payment.getIdempotencyKey())
        ));
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

    private List<String> statementLines(Member member) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        lines.add("Member: " + member.getName());
        lines.add("Statement date: " + java.time.LocalDate.now());
        lines.add("Current balance: " + money(member.getTotalOwed()));
        lines.add("Past due: " + money(member.getPastDueBalance()));
        lines.add("");
        lines.add("Payment history:");
        if (member.getPaymentHistory() == null || member.getPaymentHistory().isEmpty()) {
            lines.add("No payments recorded.");
        } else {
            member.getPaymentHistory().stream().sorted(java.util.Comparator.comparing(payment ->
                    payment.getDate() == null ? "" : payment.getDate())).forEach(payment -> lines.add(
                    safe(payment.getDate()) + " | " + money(payment.getAmount()) + " | " + safe(payment.getDescription())));
        }
        if (member.getCredits() != null && !member.getCredits().isEmpty()) {
            lines.add("");
            lines.add("Applied dues credits:");
            member.getCredits().stream().sorted(java.util.Comparator.comparing(credit ->
                    credit.getDate() == null ? "" : credit.getDate())).forEach(credit -> lines.add(
                    safe(credit.getDate()) + " | -" + money(credit.getAmount()) + " | " + safe(credit.getDescription())));
        }
        lines.add("");
        lines.add("Charges:");
        if (member.getCharges() == null || member.getCharges().isEmpty()) {
            lines.add("No itemized charges recorded.");
        } else {
            member.getCharges().stream().sorted(java.util.Comparator.comparing(charge ->
                    charge.getDueDate() == null ? charge.getDate() : charge.getDueDate())).forEach(charge -> lines.add(
                    safe(charge.getDueDate() == null ? charge.getDate() : charge.getDueDate()) + " | "
                            + money(charge.getAmount()) + " | " + safe(charge.getDescription())));
        }
        lines.add("");
        lines.add("Installments:");
        if (member.getInstallments() == null || member.getInstallments().isEmpty()) {
            lines.add("No payment plan installments recorded.");
        } else {
            member.getInstallments().stream().sorted(java.util.Comparator.comparing(PaymentInstallment::getDueDate)).forEach(installment -> lines.add(
                    safe(installment.getDueDate()) + " | " + money(installment.getAmount())
                            + " | remaining " + money(installment.getRemainingAmount())
                            + " | " + safe(installment.getDescription())));
        }
        return lines;
    }

    private byte[] pdf(String title, Member member, List<String> lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(document, page);
            float y = 750;
            stream.beginText();
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
            stream.newLineAtOffset(50, y);
            stream.showText(title + " - " + safe(member.getName()));
            stream.endText();
            y -= 36;
            stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
            for (String line : lines) {
                if (y < 50) {
                    stream.close();
                    page = new PDPage();
                    document.addPage(page);
                    stream = new PDPageContentStream(document, page);
                    y = 750;
                }
                stream.beginText();
                stream.newLineAtOffset(50, y);
                stream.showText(safe(line).substring(0, Math.min(140, safe(line).length())));
                stream.endText();
                y -= 16;
            }
            stream.close();
            document.save(output);
            return output.toByteArray();
        }
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

    private String money(double amount) {
        return String.format(java.util.Locale.US, "$%.2f", amount);
    }

    private String safe(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return normalized.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
