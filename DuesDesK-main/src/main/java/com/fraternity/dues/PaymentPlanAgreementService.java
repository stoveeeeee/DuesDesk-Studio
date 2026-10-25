package com.fraternity.dues;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A printable payment plan agreement (promissory note) for a member on installments: chapter and member details, the
 * balance the plan covers, the itemized installment schedule, the late-fee and social-clearance policy in force, and
 * signature lines for the member and the treasurer. Pure Java (PDFBox standard fonts), identical on every OS.
 */
public class PaymentPlanAgreementService {
    private static final float MARGIN = 54;
    private static final float WIDTH = PDRectangle.LETTER.getWidth() - 2 * MARGIN;
    private static final Color INK = new Color(15, 23, 42);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color RULE = new Color(203, 213, 225);
    private static final Color HEADER_FILL = new Color(238, 242, 247);
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    /** True when the member has an installment schedule to put in an agreement. */
    public static boolean hasPlan(Member member) {
        return member != null && member.getInstallments() != null && !member.getInstallments().isEmpty();
    }

    public byte[] agreementPdf(Member member, AppSettings settings, LocalDate today) throws IOException {
        if (!hasPlan(member)) throw new IllegalArgumentException("This member has no installment plan to put in an agreement.");
        List<PaymentInstallment> schedule = new ArrayList<>(member.getInstallments());
        schedule.sort(Comparator.comparing(installment -> installment.getDueDate() == null ? "" : installment.getDueDate()));
        double covered = Money.cents(schedule.stream().mapToDouble(PaymentInstallment::getAmount).sum());
        double remaining = Money.cents(schedule.stream().mapToDouble(PaymentInstallment::getRemainingAmount).sum());
        String chapter = settings.getChapterName().isBlank() ? "the Chapter" : settings.getChapterName().trim();

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Writer w = new Writer(document);
            w.text(bold, 18, "Payment Plan Agreement", INK);
            w.space(4);
            w.text(regular, 10, (settings.getChapterName().isBlank() ? "Chapter dues" : chapter) + "  |  Prepared "
                    + today.format(LONG_DATE), MUTED);
            w.space(14);

            w.heading("Parties");
            w.pair("Chapter", chapter);
            w.pair("Member", member.getName());
            w.pair("Phone", member.getPhoneNumber());
            if (!member.getEmail().isBlank()) w.pair("Email", member.getEmail());
            w.space(8);

            w.heading("Balance covered by this plan");
            w.pair("Total of all installments", money(covered));
            w.pair("Still to be paid under the plan", money(remaining));
            w.pair("Member's current account balance", money(member.getTotalOwed()));
            w.space(8);

            w.heading("Installment schedule");
            w.table(schedule);
            w.space(10);

            w.heading("Terms");
            w.paragraph("The member agrees to pay " + chapter + " each installment above in full on or before its due date. "
                    + "Payments are applied to the earliest unpaid installment first. The member may pay ahead at any time "
                    + "without penalty. This agreement covers the balance shown above; charges added later are billed separately "
                    + "unless the plan is updated in writing.");
            w.space(6);
            w.paragraph(lateFeeTerms(settings));
            w.space(6);
            w.paragraph("Social standing: while every installment is paid on time, the member is treated as in good standing "
                    + "for chapter social events. If an installment becomes overdue, the member is not socially cleared until "
                    + "the overdue amount is paid" + (settings.getSocialClearanceTolerance() > 0
                    ? " (unpaid balances of " + money(settings.getSocialClearanceTolerance()) + " or less are tolerated)." : ".")
                    + " The treasurer may grant a temporary clearance in writing.");
            w.space(22);

            w.signature("Member signature", member.getName());
            w.space(18);
            w.signature("Treasurer signature", "Treasurer, " + chapter);
            w.footer(chapter, member.getName());
            document.save(output);
            return output.toByteArray();
        }
    }

    private String lateFeeTerms(AppSettings settings) {
        if (!settings.isLateFeesEnabled() || settings.getLateFeeAmount() <= 0) {
            return "Late fees: no automatic late fee is currently charged for a late installment.";
        }
        String fee = "PERCENT".equals(settings.getLateFeeType())
                ? String.format(Locale.US, "%s%% of the unpaid amount", trim(settings.getLateFeeAmount()))
                : money(settings.getLateFeeAmount());
        int grace = settings.getLateFeeGraceDays();
        return "Late fees: a one-time late fee of " + fee + " is added to any itemized charge still unpaid "
                + grace + (grace == 1 ? " day" : " days") + " after its due date. Late fees do not compound.";
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.US, "%.2f", value);
    }

    private static String money(double amount) {
        return String.format(Locale.US, "$%,.2f", amount);
    }

    /** Lays text out top to bottom, starting new pages as needed. */
    private final class Writer {
        private final PDDocument document;
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument document) throws IOException {
            this.document = document;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.LETTER.getHeight() - MARGIN;
        }

        private void ensure(float height) throws IOException {
            if (y - height < MARGIN + 24) newPage();
        }

        void space(float points) { y -= points; }

        void text(PDFont font, float size, String value, Color color) throws IOException {
            ensure(size + 4);
            y -= size;
            show(font, size, MARGIN, y, value, color);
            y -= 4;
        }

        void heading(String value) throws IOException {
            ensure(30);
            y -= 12;
            show(bold, 11, MARGIN, y, value.toUpperCase(Locale.US), MUTED);
            y -= 5;
            stream.setStrokingColor(RULE);
            stream.moveTo(MARGIN, y);
            stream.lineTo(MARGIN + WIDTH, y);
            stream.stroke();
            y -= 8;
        }

        void pair(String label, String value) throws IOException {
            ensure(16);
            y -= 11;
            show(regular, 10, MARGIN, y, label, MUTED);
            show(bold, 10, MARGIN + 200, y, value == null || value.isBlank() ? "-" : value, INK);
            y -= 5;
        }

        void paragraph(String value) throws IOException {
            for (String line : wrap(value, regular, 10, WIDTH)) {
                ensure(14);
                y -= 10;
                show(regular, 10, MARGIN, y, line, INK);
                y -= 4;
            }
        }

        void table(List<PaymentInstallment> schedule) throws IOException {
            float[] widths = {28, 92, 230, 76, 78};
            String[] headers = {"#", "Due date", "Description", "Amount", "Status"};
            row(headers, widths, bold, true);
            for (int i = 0; i < schedule.size(); i++) {
                PaymentInstallment installment = schedule.get(i);
                String status = installment.getRemainingAmount() < Money.HALF_CENT ? "Paid"
                        : installment.getPaidAmount() >= Money.HALF_CENT ? "Partly paid (" + money(installment.getRemainingAmount()) + " left)" : "Due";
                row(new String[] {String.valueOf(i + 1), dateText(installment.getDueDate()),
                        installment.getDescription(), money(installment.getAmount()), status}, widths, regular, false);
            }
        }

        private void row(String[] cells, float[] widths, PDFont font, boolean header) throws IOException {
            ensure(20);
            float height = 18;
            if (header) {
                stream.setNonStrokingColor(HEADER_FILL);
                stream.addRect(MARGIN, y - height, WIDTH, height);
                stream.fill();
            }
            float x = MARGIN;
            for (int c = 0; c < cells.length; c++) {
                String cell = fit(cells[c], font, 9, widths[c] - 8);
                float textX = c == 3 ? x + widths[c] - 6 - width(cell, font, 9) : x + 4;
                show(font, 9, textX, y - 12.5f, cell, header ? MUTED : INK);
                x += widths[c];
            }
            y -= height;
            stream.setStrokingColor(RULE);
            stream.moveTo(MARGIN, y);
            stream.lineTo(MARGIN + WIDTH, y);
            stream.stroke();
        }

        void signature(String label, String printedName) throws IOException {
            ensure(60);
            y -= 30;
            stream.setStrokingColor(INK);
            stream.moveTo(MARGIN, y);
            stream.lineTo(MARGIN + 290, y);
            stream.moveTo(MARGIN + 330, y);
            stream.lineTo(MARGIN + WIDTH, y);
            stream.stroke();
            y -= 12;
            show(regular, 9, MARGIN, y, label + "  (" + printedName + ")", MUTED);
            show(regular, 9, MARGIN + 330, y, "Date", MUTED);
            y -= 4;
        }

        void footer(String chapter, String memberName) throws IOException {
            stream.close();
            int pages = document.getNumberOfPages();
            for (int p = 0; p < pages; p++) {
                try (PDPageContentStream footer = new PDPageContentStream(document, document.getPage(p),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String left = "Payment plan agreement  |  " + memberName + "  |  " + chapter;
                    String right = "Page " + (p + 1) + " of " + pages;
                    FlowReportService.text(footer, regular, 8, MARGIN, MARGIN - 18, left, MUTED);
                    FlowReportService.text(footer, regular, 8, MARGIN + WIDTH - width(right, regular, 8), MARGIN - 18, right, MUTED);
                }
            }
        }

        private void show(PDFont font, float size, float x, float yPos, String value, Color color) throws IOException {
            FlowReportService.text(stream, font, size, x, yPos, value, color);
        }
    }

    private static String dateText(String iso) {
        try {
            return LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US));
        } catch (Exception e) {
            return iso == null ? "" : iso;
        }
    }

    private static float width(String value, PDFont font, float size) throws IOException {
        return FlowReportService.width(value, font, size);
    }

    private static String fit(String value, PDFont font, float size, float max) throws IOException {
        return FlowReportService.fit(value, font, size, max);
    }

    /** Splits a paragraph into lines that fit {@code maxWidth}. */
    private static List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : FlowReportService.safe(text).split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate, font, size) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }
}
