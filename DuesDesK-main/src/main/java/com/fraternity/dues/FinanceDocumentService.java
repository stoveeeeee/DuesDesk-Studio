package com.fraternity.dues;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FinanceDocumentService {
    private final Store store;
    private final BackupEncryptionService encryption = new BackupEncryptionService();

    public FinanceDocumentService(Store store) {
        this.store = store;
    }

    public byte[] socialClearancePdf() throws IOException {
        List<Member> members = store.getMembers().stream()
                .sorted(java.util.Comparator.comparing(Member::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(document, page);
            float y = page.getMediaBox().getHeight() - 54;
            text(stream, "Social Clearance List", 16, 50, y, 15, 23, 40);
            y -= 28;
            text(stream, "As of " + LocalDate.now() + "  |  Name and clearance only", 9, 50, y, 90, 105, 120);
            y -= 28;
            for (Member member : members) {
                if (y < 55) {
                    stream.close();
                    page = new PDPage();
                    document.addPage(page);
                    stream = new PDPageContentStream(document, page);
                    y = page.getMediaBox().getHeight() - 55;
                }
                boolean cleared = store.isSociallyCleared(member);
                text(stream, safe(member.getName()), 10, 50, y, 15, 23, 40);
                text(stream, cleared ? "CLEARED" : "NOT CLEARED", 9, 420, y,
                        cleared ? 5 : 190, cleared ? 130 : 24, cleared ? 90 : 50);
                y -= 19;
            }
            stream.close();
            document.save(output);
            return output.toByteArray();
        }
    }

    public byte[] socialClearanceCsv() {
        StringBuilder csv = new StringBuilder("Name,Clearance\n");
        store.getMembers().stream().sorted(java.util.Comparator.comparing(Member::getName,
                String.CASE_INSENSITIVE_ORDER)).forEach(member -> csv.append(csv(member.getName())).append(',')
                .append(store.isSociallyCleared(member) ? "CLEARED" : "NOT CLEARED").append('\n'));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] meetingPacketPdf() throws IOException {
        AppSettings settings = store.getSettings();
        List<Member> members = store.getMembers();
        double totalOwed = members.stream().mapToDouble(Member::getTotalOwed).sum();
        double cashPayments = 0;
        java.time.YearMonth currentMonth = java.time.YearMonth.now();
        for (Member member : members) {
            double allHistory = 0;
            double currentMonthHistory = 0;
            if (member.getPaymentHistory() != null) {
                for (PaymentRecord payment : member.getPaymentHistory()) {
                    allHistory += payment.getAmount();
                    try {
                        if (java.time.YearMonth.from(LocalDate.parse(payment.getDate().substring(0, 10)))
                                .equals(currentMonth)) currentMonthHistory += payment.getAmount();
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                }
            }
            cashPayments += allHistory + Math.max(0, member.getPaidThisMonth() - currentMonthHistory);
        }
        double billed = totalOwed + cashPayments;
        double collectionRate = billed > 0 ? cashPayments / billed * 100.0 : 100.0;
        double ledgerNet = store.getFlowEntries().stream()
                .filter(entry -> !"IGNORED".equals(entry.getReconciliationStatus()))
                .mapToDouble(FlowEntry::getAmount).sum();
        double cashEstimate = settings.getOpeningCashBalance() + ledgerNet;

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(document, page);
            float y = 730;
            text(stream, "Chapter Meeting Packet", 18, 55, y, 15, 23, 40);
            y -= 26;
            text(stream, "Anonymous financial summary | " + LocalDate.now(), 10, 55, y, 90, 105, 120);
            y -= 58;
            text(stream, "Estimated cash on hand", 11, 55, y, 90, 105, 120);
            text(stream, money(cashEstimate), 24, 55, y - 34, 15, 23, 40);
            y -= 105;
            text(stream, "Dues collected", 11, 55, y, 90, 105, 120);
            text(stream, String.format(Locale.US, "%.1f%%", collectionRate), 24, 55, y - 34, 5, 150, 105);
            y -= 105;
            text(stream, "Upcoming chapter bills", 11, 55, y, 90, 105, 120);
            y -= 24;
            List<String> bills = upcomingBills(settings);
            if (bills.isEmpty()) {
                text(stream, "No upcoming bills entered.", 10, 55, y, 60, 70, 80);
                y -= 20;
            } else {
                for (String bill : bills.stream().limit(6).toList()) {
                    text(stream, bill, 10, 55, y, 30, 41, 59);
                    y -= 18;
                }
            }
            y -= 20;
            text(stream, "Total uncollected balance", 11, 55, y, 90, 105, 120);
            text(stream, money(totalOwed), 22, 55, y - 34, 15, 23, 40);
            stream.close();
            document.save(output);
            return output.toByteArray();
        }
    }

    public byte[] handoffPackage(char[] password) throws Exception {
        byte[] encryptedBackup = encryption.encrypt(store.exportData(), password);
        byte[] summary = handoffSummaryPdf();
        byte[] instructions = ("DuesDesk Officer Handoff\n\n"
                + "1. Install DuesDesk on the new computer using the official release provided by the chapter.\n"
                + "2. Open Import & Export, choose Import saved data, select the .ddbk file, and enter its password.\n"
                + "3. Keep the backup password separate from the package and share it securely with the incoming treasurer.\n"
                + "4. Verify the roster, balances, settings, and ledger before continuing chapter operations.\n"
                + "5. Reconnect Twilio credentials on the new computer; they are intentionally excluded from backups.\n")
                .getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            addZipEntry(zip, "DuesDesk-backup-" + LocalDate.now() + ".ddbk", encryptedBackup);
            addZipEntry(zip, "Officer-handoff-summary.pdf", summary);
            addZipEntry(zip, "New-treasurer-instructions.txt", instructions);
            zip.finish();
            return bytes.toByteArray();
        }
    }

    private byte[] handoffSummaryPdf() throws IOException {
        AppSettings settings = store.getSettings();
        List<Member> members = store.getMembers();
        double owed = members.stream().mapToDouble(Member::getTotalOwed).sum();
        double ledger = store.getFlowEntries().stream()
                .filter(entry -> !"IGNORED".equals(entry.getReconciliationStatus()))
                .mapToDouble(FlowEntry::getAmount).sum();
        double cash = settings.getOpeningCashBalance() + ledger;
        return simplePdf("Officer Handoff Summary", List.of(
                "Prepared: " + LocalDate.now(),
                "Estimated chapter cash on hand: " + money(cash),
                "Unpaid member balances: " + money(owed),
                "Roster size: " + members.size(),
                "",
                "The estimate uses the configured opening cash balance plus imported ledger activity.",
                "Verify the balance against the bank statement during transition.",
                "",
                "New treasurer setup:",
                "1. Install DuesDesk from the official chapter distribution.",
                "2. Import the encrypted .ddbk backup from this package.",
                "3. Enter the backup password shared separately by the outgoing treasurer.",
                "4. Review balances and reconnect any external messaging service."
        ));
    }

    private byte[] simplePdf(String title, List<String> lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(document, page);
            float y = 730;
            text(stream, title, 18, 55, y, 15, 23, 40);
            y -= 38;
            for (String line : lines) {
                text(stream, line, 10, 55, y, 40, 52, 66);
                y -= 20;
            }
            stream.close();
            document.save(output);
            return output.toByteArray();
        }
    }

    private List<String> upcomingBills(AppSettings settings) {
        List<String> bills = new java.util.ArrayList<>();
        for (String raw : settings.getUpcomingBills().lines().map(String::trim).filter(line -> !line.isBlank()).toList()) {
            String[] fields = raw.split("\\|", -1);
            if (fields.length != 3 || fields[0].isBlank()) {
                throw new IllegalStateException("An upcoming bill is invalid. Use Description | YYYY-MM-DD | Amount.");
            }
            LocalDate date;
            double amount;
            try {
                date = LocalDate.parse(fields[1].trim());
                amount = Double.parseDouble(fields[2].trim());
            } catch (RuntimeException e) {
                throw new IllegalStateException("An upcoming bill has an invalid date or amount.", e);
            }
            if (!Double.isFinite(amount) || amount < 0) {
                throw new IllegalStateException("Upcoming bill amounts must be non-negative numbers.");
            }
            bills.add(safe(fields[0].trim()) + " | " + date + " | " + money(amount));
        }
        return List.copyOf(bills);
    }

    private void addZipEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private void text(PDPageContentStream stream, String value, int size, float x, float y,
                      int red, int green, int blue) throws IOException {
        stream.beginText();
        stream.setNonStrokingColor(new java.awt.Color(red, green, blue));
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
        stream.newLineAtOffset(x, y);
        stream.showText(safe(value).substring(0, Math.min(110, safe(value).length())));
        stream.endText();
    }

    private String money(double value) { return String.format(Locale.US, "$%,.2f", value); }

    private String safe(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    private String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
