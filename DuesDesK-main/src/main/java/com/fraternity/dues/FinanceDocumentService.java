package com.fraternity.dues;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fraternity.dues.ExecutivePdf.Cell;
import com.fraternity.dues.ExecutivePdf.Column;
import com.fraternity.dues.ExecutivePdf.Kpi;
import com.fraternity.dues.ExecutivePdf.SummaryLine;
import com.fraternity.dues.ExecutivePdf.Tone;

/** Chapter-level reports: the social clearance list, the anonymous meeting packet and the officer handoff package. */
@Service
public class FinanceDocumentService {
    private final Store store;
    private final BackupEncryptionService encryption = new BackupEncryptionService();

    public FinanceDocumentService(Store store) {
        this.store = store;
    }

    private String chapter() {
        return ExecutivePdf.chapterOf(store);
    }

    /** Names and CLEARED / NOT CLEARED only: this list is meant to be posted, so it never carries balances. */
    public byte[] socialClearancePdf() throws IOException {
        List<Member> members = store.getMembers().stream()
                .sorted(java.util.Comparator.comparing(Member::getName, String.CASE_INSENSITIVE_ORDER)).toList();
        long cleared = members.stream().filter(store::isSociallyCleared).count();
        ExecutivePdf pdf = new ExecutivePdf(chapter(), "Social Clearance List", false);
        pdf.letterhead("Social Clearance List  |  Name and clearance only", "Social Clearance List", List.of(
                new String[] {"As of", LocalDate.now().toString()},
                new String[] {"Roster", members.size() + (members.size() == 1 ? " member" : " members")}
        ), "Current", Tone.INDIGO);
        pdf.kpiBand(List.of(
                new Kpi("Cleared", String.valueOf(cleared), ExecutivePdf.GREEN),
                new Kpi("Not cleared", String.valueOf(members.size() - cleared), members.size() - cleared > 0 ? ExecutivePdf.RED : ExecutivePdf.INK),
                new Kpi("Roster size", String.valueOf(members.size()))
        ));
        float w = pdf.width();
        List<List<Cell>> rows = new ArrayList<>();
        int index = 1;
        for (Member member : members) {
            boolean ok = store.isSociallyCleared(member);
            rows.add(List.of(Cell.muted(String.valueOf(index++)), Cell.bold(safe(member.getName())),
                    Cell.pill(ok ? "CLEARED" : "NOT CLEARED", ok ? Tone.SUCCESS : Tone.DANGER)));
        }
        pdf.table(List.of(new Column("#", w * 0.08f, false), new Column("Name", w * 0.64f, false), new Column("Clearance", w * 0.28f, true)),
                rows, "No members on the roster.");
        pdf.space(4);
        pdf.note("Clearance follows the chapter's configured dues tolerance, eligible payment plans and any manual vouches in force today.", ExecutivePdf.MUTED);
        return pdf.finish();
    }

    public byte[] socialClearanceCsv() {
        StringBuilder csv = new StringBuilder("Name,Clearance\n");
        store.getMembers().stream().sorted(java.util.Comparator.comparing(Member::getName,
                String.CASE_INSENSITIVE_ORDER)).forEach(member -> csv.append(csv(member.getName())).append(',')
                .append(store.isSociallyCleared(member) ? "CLEARED" : "NOT CLEARED").append('\n'));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** One anonymous page for chapter meetings: cash position, collection rate, upcoming bills, uncollected balance. */
    public byte[] meetingPacketPdf() throws IOException {
        AppSettings settings = store.getSettings();
        List<Member> members = store.getMembers();
        double totalOwed = members.stream().mapToDouble(Member::getTotalOwed).sum();
        double cashPayments = duesReceived(members);
        double billed = totalOwed + cashPayments;
        double collectionRate = billed > 0 ? cashPayments / billed * 100.0 : 100.0;
        double cashEstimate = estimatedCash(settings);
        List<String[]> bills = upcomingBills(settings);
        double billTotal = 0;
        for (String[] bill : bills) billTotal += Double.parseDouble(bill[3]);

        ExecutivePdf pdf = new ExecutivePdf(chapter(), "Chapter Meeting Packet", false);
        pdf.letterhead("Chapter Meeting Packet  |  Anonymous financial summary", "Executive Financial Summary", List.of(
                new String[] {"Meeting date", LocalDate.now().toString()},
                new String[] {"Roster", members.size() + (members.size() == 1 ? " member" : " members")},
                new String[] {"Basis", "Opening cash + ledger activity"}
        ), "Anonymous", Tone.INDIGO);
        return meetingPacketBody(pdf, bills, cashEstimate, collectionRate, billTotal, totalOwed);
    }

    /** The chapter's position right now: what the rollover reveal and the handoff performance card show. */
    public record Position(double cashOnHand, double receivables, int activeMembers, int rosterSize) {}

    public Position position() {
        List<Member> members = store.getMembers();
        return new Position(Money.cents(estimatedCash(store.getSettings())),
                Money.cents(members.stream().mapToDouble(Member::getTotalOwed).sum()),
                (int) members.stream().filter(member -> "Member".equals(member.getMembershipStatus())).count(),
                members.size());
    }

    /** The treasurer's term in numbers, from the same figures as the meeting packet and handoff summary. */
    public record TermPerformance(String termName, double duesCollected, double billed, double collectionRate,
                                  double receivables, double budgeted, double spent, double budgetSurplus,
                                  int chairCount, int overBudgetChairs, double cashOnHand, int rosterSize,
                                  String encryption) {}

    public TermPerformance termPerformance() {
        AppSettings settings = store.getSettings();
        List<Member> members = store.getMembers();
        double owed = members.stream().mapToDouble(Member::getTotalOwed).sum();
        double collected = duesReceived(members);
        double billed = owed + collected;
        List<ChairBudget> budgets = store.getChairBudgets();
        double budgeted = budgets.stream().mapToDouble(ChairBudget::getBudgetCap).sum();
        double spent = budgets.stream().mapToDouble(ChairBudget::getSpent).sum();
        return new TermPerformance(settings.getCurrentTermName(), Money.cents(collected), Money.cents(billed),
                billed > 0 ? collected / billed * 100.0 : 100.0, Money.cents(owed), Money.cents(budgeted),
                Money.cents(spent), Money.cents(budgeted - spent), budgets.size(),
                (int) budgets.stream().filter(budget -> budget.getRemaining() < 0).count(),
                Money.cents(estimatedCash(settings)), members.size(), "AES-256-GCM");
    }

    private double estimatedCash(AppSettings settings) {
        double ledgerNet = store.getFlowEntries().stream()
                .filter(entry -> !"IGNORED".equals(entry.getReconciliationStatus()))
                .mapToDouble(FlowEntry::getAmount).sum();
        return settings.getOpeningCashBalance() + ledgerNet;
    }

    /** Dues received: every recorded payment, plus this month's paid amount not yet in the payment history. */
    private static double duesReceived(List<Member> members) {
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
        return cashPayments;
    }

    private byte[] meetingPacketBody(ExecutivePdf pdf, List<String[]> bills, double cashEstimate, double collectionRate,
                                     double billTotal, double totalOwed) throws IOException {
        pdf.kpiBand(List.of(
                new Kpi("Estimated cash on hand", money(cashEstimate), cashEstimate < 0 ? ExecutivePdf.RED : ExecutivePdf.INK),
                new Kpi("Dues collected", String.format(Locale.US, "%.1f%%", collectionRate), ExecutivePdf.GREEN),
                new Kpi("Upcoming bills", money(billTotal)),
                new Kpi("Uncollected balance", money(totalOwed), totalOwed > 0 ? ExecutivePdf.RED : ExecutivePdf.INK)
        ));
        float w = pdf.width();
        pdf.section("Upcoming chapter bills");
        List<List<Cell>> rows = new ArrayList<>();
        for (String[] bill : bills.stream().limit(6).toList()) {
            rows.add(List.of(Cell.bold(bill[0]), Cell.muted(bill[1]), Cell.pill(bill[2], "Overdue".equals(bill[2]) ? Tone.DANGER : "Due soon".equals(bill[2]) ? Tone.WARNING : Tone.NEUTRAL),
                    Cell.bold(money(Double.parseDouble(bill[3])))));
        }
        pdf.table(List.of(new Column("Description", w * 0.46f, false), new Column("Due date", w * 0.18f, false),
                new Column("Status", w * 0.16f, false), new Column("Amount", w * 0.20f, true)), rows, "No upcoming bills entered.");
        pdf.space(8);
        pdf.totals("Notes for the chapter", List.of(
                "Estimated cash on hand is the configured opening cash balance plus imported and recorded ledger activity. It should be verified against the bank statement.",
                "Dues collected is the share of everything billed this term that has been received so far.",
                "This packet is anonymous: no member names or individual balances appear in it."
        ), List.of(), List.of(
                new SummaryLine("Estimated cash on hand", money(cashEstimate)),
                new SummaryLine("Dues collected", String.format(Locale.US, "%.1f%%", collectionRate)),
                new SummaryLine("Upcoming bills", money(billTotal)),
                new SummaryLine("Total uncollected balance", money(totalOwed), true)
        ));
        return pdf.finish();
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
        long pastDue = members.stream().filter(member -> member.getPastDueBalance() > 0).count();

        ExecutivePdf pdf = new ExecutivePdf(chapter(), "Officer Handoff Summary", false);
        pdf.letterhead("Officer Handoff Summary  |  Treasurer transition", "Officer Handoff Package", List.of(
                new String[] {"Prepared", LocalDate.now().toString()},
                new String[] {"Term", settings.getCurrentTermName().isBlank() ? "Current term" : settings.getCurrentTermName()},
                new String[] {"Roster size", String.valueOf(members.size())}
        ), "Finalized", Tone.SUCCESS);
        pdf.kpiBand(List.of(
                new Kpi("Chapter cash on hand", money(cash), cash < 0 ? ExecutivePdf.RED : ExecutivePdf.INK),
                new Kpi("Unpaid member balances", money(owed), owed > 0 ? ExecutivePdf.RED : ExecutivePdf.INK),
                new Kpi("Members past due", String.valueOf(pastDue)),
                new Kpi("Roster size", String.valueOf(members.size()))
        ));
        float w = pdf.width();
        pdf.section("Package contents");
        pdf.table(List.of(new Column("File", w * 0.42f, false), new Column("Purpose", w * 0.40f, false), new Column("Status", w * 0.18f, true)), List.of(
                List.of(Cell.bold("DuesDesk-backup-" + LocalDate.now() + ".ddbk"), Cell.of("AES-encrypted copy of every record"), Cell.pill("Encrypted", Tone.INDIGO)),
                List.of(Cell.bold("Officer-handoff-summary.pdf"), Cell.of("This summary"), Cell.pill("Included", Tone.SUCCESS)),
                List.of(Cell.bold("New-treasurer-instructions.txt"), Cell.of("Step-by-step setup on the new computer"), Cell.pill("Included", Tone.SUCCESS))
        ), null);
        pdf.section("New treasurer setup");
        pdf.paragraph("1. Install DuesDesk from the official chapter distribution.");
        pdf.paragraph("2. Import the encrypted .ddbk backup from this package.");
        pdf.paragraph("3. Enter the backup password shared separately by the outgoing treasurer.");
        pdf.paragraph("4. Review balances and reconnect any external messaging service.");
        pdf.space(10);
        pdf.totals("Handoff verification checklist", List.of(
                "The estimate uses the configured opening cash balance plus imported ledger activity.",
                "Verify the balance against the bank statement during transition.",
                "Confirm the roster, unpaid balances, settings and ledger match what the outgoing treasurer reported.",
                ""
        ), List.of(new String[] {"Prepared by", "Outgoing treasurer"}, new String[] {"Reviewed / accepted by", "Incoming treasurer"}), List.of(
                new SummaryLine("Prepared", LocalDate.now().toString()),
                new SummaryLine("Roster size", String.valueOf(members.size())),
                new SummaryLine("Unpaid member balances", money(owed)),
                new SummaryLine("Estimated cash on hand", money(cash), true)
        ));
        return pdf.finish();
    }

    /** Each bill as {description, due date, status, amount}. */
    private List<String[]> upcomingBills(AppSettings settings) {
        List<String[]> bills = new ArrayList<>();
        LocalDate today = LocalDate.now();
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
            String status = date.isBefore(today) ? "Overdue" : !date.isAfter(today.plusDays(14)) ? "Due soon" : "Scheduled";
            bills.add(new String[] {safe(fields[0].trim()), date.toString(), status, String.format(Locale.US, "%.2f", amount)});
        }
        return List.copyOf(bills);
    }

    private void addZipEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
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
