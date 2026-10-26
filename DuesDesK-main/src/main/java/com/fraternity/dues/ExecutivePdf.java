package com.fraternity.dues;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The executive document engine behind every PDF DuesDesk produces: white paper, slate ink, a royal indigo accent rule,
 * a two-column letterhead, KPI scorecard bands, borderless "receipt rule" tables that repeat their header on every page,
 * pinned totals and sign-off blocks, and a confidential footer with page numbers on every page.
 *
 * Layout flows top to bottom. Each block checks that it fits and starts a new page when it does not, so KPI strips,
 * totals, signature blocks and table rows are never split across a page break.
 */
final class ExecutivePdf {
    static final Color INK = new Color(0x0F, 0x17, 0x2A);
    static final Color SLATE = new Color(0x47, 0x55, 0x69);
    static final Color MUTED = new Color(0x64, 0x74, 0x8B);
    static final Color CAPTION = new Color(0x94, 0xA3, 0xB8);
    static final Color INDIGO = new Color(0x4F, 0x46, 0xE5);
    static final Color INDIGO_TINT = new Color(0xEE, 0xF2, 0xFF);
    static final Color HAIRLINE = new Color(0xE2, 0xE8, 0xF0);
    static final Color RULE = new Color(0xCB, 0xD5, 0xE1);
    static final Color PAPER = Color.WHITE;
    static final Color PANEL = new Color(0xF8, 0xFA, 0xFC);
    static final Color SOFT = new Color(0xF1, 0xF5, 0xF9);
    static final Color GREEN = new Color(0x04, 0x78, 0x57);
    static final Color RED = new Color(0xB9, 0x1C, 0x1C);

    /** Semantic tint for status pills and highlighted figures. */
    enum Tone {
        SUCCESS(new Color(0x04, 0x78, 0x57), new Color(0xD1, 0xFA, 0xE5), new Color(0x6E, 0xE7, 0xB7)),
        WARNING(new Color(0xB4, 0x53, 0x09), new Color(0xFE, 0xF3, 0xC7), new Color(0xFC, 0xD3, 0x4D)),
        DANGER(new Color(0xB9, 0x1C, 0x1C), new Color(0xFE, 0xE2, 0xE2), new Color(0xFC, 0xA5, 0xA5)),
        NEUTRAL(new Color(0x33, 0x41, 0x55), new Color(0xE2, 0xE8, 0xF0), new Color(0xCB, 0xD5, 0xE1)),
        INDIGO(new Color(0x43, 0x38, 0xCA), new Color(0xEE, 0xF2, 0xFF), new Color(0xC7, 0xD2, 0xFE));

        final Color text;
        final Color fill;
        final Color border;

        Tone(Color text, Color fill, Color border) { this.text = text; this.fill = fill; this.border = border; }
    }

    /** One headline figure in a KPI scorecard strip. */
    record Kpi(String label, String value, Color valueColor) {
        Kpi(String label, String value) { this(label, value, INK); }
    }

    /** A labelled column in the metadata band: a heading, then lines (or a single highlighted callout value). */
    record InfoBlock(String heading, List<String> lines, String calloutValue, Color calloutColor) {
        static InfoBlock of(String heading, String... lines) { return new InfoBlock(heading, List.of(lines), null, null); }
        static InfoBlock callout(String heading, String value, Color color) { return new InfoBlock(heading, List.of(), value, color); }
    }

    /** A table column: label, relative width (points when the table is sized explicitly), and alignment. */
    record Column(String label, float width, boolean numeric) {}

    /** One cell: text, optional emphasis, optional colour, or a status pill when {@code tone} is set. */
    record Cell(String text, boolean bold, Color color, Tone tone) {
        static Cell of(String text) { return new Cell(text, false, null, null); }
        static Cell bold(String text) { return new Cell(text, true, INK, null); }
        static Cell muted(String text) { return new Cell(text, false, MUTED, null); }
        static Cell colored(String text, Color color) { return new Cell(text, true, color, null); }
        static Cell pill(String text, Tone tone) { return new Cell(text, true, null, tone); }
    }

    /** A line in the right-hand summary table of a totals block. */
    record SummaryLine(String label, String value, boolean emphasized) {
        SummaryLine(String label, String value) { this(label, value, false); }
    }

    static final float MARGIN = 44;
    private static final float ROW_HEIGHT = 18;
    private static final float HEAD_HEIGHT = 20;
    private static final float FOOTER_HEIGHT = 26;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US);

    private final PDDocument document = new PDDocument();
    private final PDRectangle pageSize;
    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final String organization;
    private final String footerSubject;
    private PDPageContentStream stream;
    private float y;
    private boolean firstPage = true;
    private PDImageXObject mark;

    ExecutivePdf(String organization, String footerSubject, boolean landscape) throws IOException {
        this.organization = organization == null || organization.isBlank() ? "DuesDesk" : organization.trim();
        this.footerSubject = footerSubject == null ? "" : footerSubject;
        this.pageSize = landscape ? new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth()) : PDRectangle.LETTER;
        newPage();
    }

    float width() { return pageSize.getWidth() - 2 * MARGIN; }

    float left() { return MARGIN; }

    float right() { return pageSize.getWidth() - MARGIN; }

    float y() { return y; }

    PDFont regular() { return regular; }

    PDFont bold() { return bold; }

    float bottomLimit() { return MARGIN + FOOTER_HEIGHT; }

    private void newPage() throws IOException {
        if (stream != null) stream.close();
        PDPage page = new PDPage(pageSize);
        document.addPage(page);
        stream = new PDPageContentStream(document, page);
        y = pageSize.getHeight() - MARGIN;
        if (firstPage) {
            // Top accent rule: a crisp 4pt royal indigo bar across the top edge of page 1.
            stream.setNonStrokingColor(INDIGO);
            stream.addRect(0, pageSize.getHeight() - 4, pageSize.getWidth(), 4);
            stream.fill();
        }
    }

    /** Starts a new page if {@code height} points would not fit above the footer. */
    void ensure(float height) throws IOException {
        if (y - height < bottomLimit()) {
            firstPage = false;
            newPage();
        }
    }

    void space(float points) { y -= points; }

    // ---------------------------------------------------------------- letterhead

    /**
     * Two-column asymmetric header. Left: monogram badge, organization name, subtitle. Right (hard-right aligned):
     * tracked uppercase classification title, key/value metadata, optional status pill.
     */
    void letterhead(String subtitle, String classification, List<String[]> meta, String status, Tone tone) throws IOException {
        float top = y;
        float badge = 34;
        drawMark(left(), top - badge, badge);
        float textX = left() + badge + 10;
        String title = safe(classification).toUpperCase(Locale.US);
        float titleSize = 18;
        float titleWidth = width(title, bold, titleSize) + Math.max(0, title.length() - 1) * 0.04f * titleSize;
        if (titleWidth > width() * 0.5f) {
            titleSize = 14;
            titleWidth = width(title, bold, titleSize) + Math.max(0, title.length() - 1) * 0.04f * titleSize;
        }
        float leftRoom = right() - titleWidth - 16 - textX;
        float orgSize = width(organization, bold, 16) <= leftRoom ? 16 : 13;
        text(bold, orgSize, textX, top - 15, fit(organization, bold, orgSize, leftRoom), INK);
        if (subtitle != null && !subtitle.isBlank()) {
            text(regular, 9, textX, top - 29, fit(subtitle, regular, 9, Math.max(leftRoom, width() * 0.45f)), MUTED);
        }
        float rightY = top - 15;
        tracked(bold, titleSize, right(), rightY, title, INK, 0.04f);
        rightY -= 17;
        float metaSize = 9;
        for (String[] pair : meta == null ? List.<String[]>of() : meta) {
            String label = pair[0] == null ? "" : pair[0];
            String value = pair[1] == null ? "" : pair[1];
            float valueWidth = width(value, bold, metaSize);
            text(bold, metaSize, right() - valueWidth, rightY, value, INK);
            text(regular, metaSize, right() - valueWidth - 6 - width(label, regular, metaSize), rightY, label, MUTED);
            rightY -= 13;
        }
        if (status != null && !status.isBlank()) {
            float pillWidth = width(status.toUpperCase(Locale.US), bold, 8) + 16;
            pill(right() - pillWidth, rightY - 11, pillWidth, 15, status.toUpperCase(Locale.US), tone == null ? Tone.NEUTRAL : tone, 8);
            rightY -= 18;
        }
        float leftBottom = top - (subtitle == null || subtitle.isBlank() ? 24 : 38);
        y = Math.min(leftBottom, rightY) - 4;
        hairline(left(), y, right());
        y -= 16;
    }

    private void drawMark(float x, float yBottom, float size) throws IOException {
        if (mark == null) mark = loadMark();
        if (mark != null) {
            stream.drawImage(mark, x, yBottom, size, size);
            return;
        }
        // Fallback monogram: indigo squircle with the organization's initial.
        fillRounded(x, yBottom, size, size, 9, INDIGO);
        String initial = organization.substring(0, 1).toUpperCase(Locale.US);
        float w = width(initial, bold, 16);
        text(bold, 16, x + (size - w) / 2, yBottom + size / 2 - 5.5f, initial, PAPER);
    }

    private PDImageXObject loadMark() {
        try (InputStream in = ExecutivePdf.class.getResourceAsStream("/static/brand/duesdesk-mark.png")) {
            if (in == null) return null;
            return PDImageXObject.createFromByteArray(document, in.readAllBytes(), "duesdesk-mark");
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- bands

    /** 3- or 4-column KPI scorecard strip: 8pt uppercase labels above 15pt bold tabular figures. */
    void kpiBand(List<Kpi> kpis) throws IOException {
        if (kpis.isEmpty()) return;
        float colWidth = width() / kpis.size();
        float labelRoom = (colWidth - 28) * 0.86f;
        List<List<String>> labels = new ArrayList<>();
        int labelLines = 1;
        for (Kpi kpi : kpis) {
            List<String> lines = wrap(safe(kpi.label()).toUpperCase(Locale.US), bold, 8, labelRoom);
            if (lines.size() > 2) lines = List.of(lines.get(0), fit(String.join(" ", lines.subList(1, lines.size())), bold, 8, labelRoom));
            labels.add(lines);
            labelLines = Math.max(labelLines, lines.size());
        }
        float extra = (labelLines - 1) * 10;
        float height = 54 + extra;
        ensure(height + 12);
        float top = y;
        strokeRounded(left(), top - height, width(), height, 8, PANEL, HAIRLINE);
        for (int i = 0; i < kpis.size(); i++) {
            Kpi kpi = kpis.get(i);
            float x = left() + i * colWidth + 16;
            if (i > 0) {
                stream.setStrokingColor(HAIRLINE);
                stream.setLineWidth(0.5f);
                stream.moveTo(left() + i * colWidth, top - 10);
                stream.lineTo(left() + i * colWidth, top - height + 10);
                stream.stroke();
            }
            float labelY = top - 17;
            for (String line : labels.get(i)) {
                trackedAt(bold, 8, x, labelY, line, MUTED, 0.06f);
                labelY -= 10;
            }
            text(bold, 15, x, top - 38 - extra, fit(kpi.value(), bold, 15, colWidth - 28), kpi.valueColor() == null ? INK : kpi.valueColor());
        }
        y = top - height - 14;
    }

    /** Metadata band split into logical columns (Prepared for / Details / highlighted Balance Due callout). */
    void infoBand(List<InfoBlock> blocks) throws IOException {
        if (blocks.isEmpty()) return;
        int maxLines = 0;
        for (InfoBlock block : blocks) maxLines = Math.max(maxLines, block.calloutValue() != null ? 2 : block.lines().size());
        float height = 30 + maxLines * 12.5f;
        ensure(height + 12);
        float top = y;
        strokeRounded(left(), top - height, width(), height, 8, PANEL, HAIRLINE);
        float colWidth = width() / blocks.size();
        for (int i = 0; i < blocks.size(); i++) {
            InfoBlock block = blocks.get(i);
            float x = left() + i * colWidth + 16;
            if (i > 0) {
                stream.setStrokingColor(HAIRLINE);
                stream.setLineWidth(0.5f);
                stream.moveTo(left() + i * colWidth, top - 10);
                stream.lineTo(left() + i * colWidth, top - height + 10);
                stream.stroke();
            }
            trackedAt(bold, 8, x, top - 17, fit(safe(block.heading()).toUpperCase(Locale.US), bold, 8, (colWidth - 28) * 0.86f), MUTED, 0.06f);
            if (block.calloutValue() != null) {
                text(bold, 16, x, top - 39, fit(block.calloutValue(), bold, 16, colWidth - 28), block.calloutColor() == null ? INK : block.calloutColor());
            } else {
                float lineY = top - 31;
                for (int l = 0; l < block.lines().size(); l++) {
                    String line = block.lines().get(l);
                    text(l == 0 ? bold : regular, 9.5f, x, lineY, fit(line, l == 0 ? bold : regular, 9.5f, colWidth - 28), l == 0 ? INK : SLATE);
                    lineY -= 12.5f;
                }
            }
        }
        y = top - height - 14;
    }

    // ---------------------------------------------------------------- text

    /** Section micro-header with a short indigo indicator. */
    void section(String title) throws IOException {
        ensure(28);
        y -= 10;
        stream.setNonStrokingColor(INDIGO);
        stream.addRect(left(), y - 1, 3, 9);
        stream.fill();
        trackedAt(bold, 9, left() + 9, y, safe(title).toUpperCase(Locale.US), SLATE, 0.06f);
        y -= 12;
    }

    void paragraph(String value) throws IOException { paragraph(value, 9.5f, SLATE); }

    void paragraph(String value, float size, Color color) throws IOException {
        for (String line : wrap(value, regular, size, width())) {
            ensure(size + 5);
            y -= size;
            text(regular, size, left(), y, line, color);
            y -= 4;
        }
    }

    /** Label on the left, value hard-right of a fixed 200pt column. */
    void pair(String label, String value) throws IOException {
        ensure(16);
        y -= 11;
        text(regular, 9.5f, left(), y, label, MUTED);
        text(bold, 9.5f, left() + 200, y, value == null || value.isBlank() ? "-" : value, INK);
        y -= 5;
    }

    /**
     * A group heading inside a section: bold title on the left, muted detail hard-right, hairline under both. Kept
     * together with at least the first rows of the table that follows it.
     */
    void groupHeading(String title, String detail) throws IOException {
        ensure(22 + HEAD_HEIGHT + ROW_HEIGHT + 6);
        y -= 13;
        String right = detail == null ? "" : safe(detail);
        float rightWidth = width(right, regular, 9);
        text(bold, 10.5f, left(), y, fit(title, bold, 10.5f, width() - rightWidth - 16), INK);
        if (!right.isEmpty()) text(regular, 9, right() - rightWidth, y, right, MUTED);
        y -= 6;
        hairline(left(), y, right());
        y -= 4;
    }

    void note(String value, Color color) throws IOException {
        ensure(14);
        y -= 9;
        text(regular, 9, left(), y, value, color);
        y -= 5;
    }

    // ---------------------------------------------------------------- tables

    /**
     * Receipt-rule table: deep slate header with white uppercase labels, no vertical borders, 0.5pt hairlines between
     * rows, zebra striping, text hard-left and numbers hard-right. The header repeats at the top of every new page.
     * Column widths are points; pass {@code null} for {@code emptyMessage} to render nothing when there are no rows.
     */
    void table(List<Column> columns, List<List<Cell>> rows, String emptyMessage) throws IOException {
        float tableWidth = 0;
        for (Column column : columns) tableWidth += column.width();
        ensure(HEAD_HEIGHT + ROW_HEIGHT + 6);
        tableHeader(columns, tableWidth);
        if (rows.isEmpty()) {
            if (emptyMessage != null) {
                ensure(ROW_HEIGHT);
                text(regular, 9.5f, left() + 8, y - 12.5f, emptyMessage, MUTED);
                y -= ROW_HEIGHT;
            }
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (y - ROW_HEIGHT < bottomLimit()) {
                firstPage = false;
                newPage();
                tableHeader(columns, tableWidth);
            }
            if (i % 2 == 1) {
                stream.setNonStrokingColor(PANEL);
                stream.addRect(left(), y - ROW_HEIGHT, tableWidth, ROW_HEIGHT);
                stream.fill();
            }
            float x = left();
            List<Cell> row = rows.get(i);
            for (int c = 0; c < columns.size(); c++) {
                Column column = columns.get(c);
                Cell cell = c < row.size() && row.get(c) != null ? row.get(c) : Cell.of("");
                float inner = column.width() - 16;
                if (cell.tone() != null) {
                    String label = safe(cell.text()).toUpperCase(Locale.US);
                    float pillWidth = Math.min(inner, width(label, bold, 7.5f) + 14);
                    float pillX = column.numeric() ? x + column.width() - 8 - pillWidth : x + 8;
                    pill(pillX, y - ROW_HEIGHT + 3, pillWidth, ROW_HEIGHT - 6, fit(label, bold, 7.5f, pillWidth - 10), cell.tone(), 7.5f);
                } else {
                    PDFont font = cell.bold() ? bold : regular;
                    String textValue = fit(cell.text(), font, 8.5f, inner);
                    float textX = column.numeric() ? x + column.width() - 8 - width(textValue, font, 8.5f) : x + 8;
                    text(font, 8.5f, textX, y - 12.5f, textValue, cell.color() == null ? INK : cell.color());
                }
                x += column.width();
            }
            y -= ROW_HEIGHT;
            stream.setStrokingColor(HAIRLINE);
            stream.setLineWidth(0.5f);
            stream.moveTo(left(), y);
            stream.lineTo(left() + tableWidth, y);
            stream.stroke();
        }
        y -= 8;
    }

    private void tableHeader(List<Column> columns, float tableWidth) throws IOException {
        fillRounded(left(), y - HEAD_HEIGHT, tableWidth, HEAD_HEIGHT, 4, INK);
        float x = left();
        for (Column column : columns) {
            String label = fit(safe(column.label()).toUpperCase(Locale.US), bold, 8.5f, column.width() - 16);
            float textX = column.numeric() ? x + column.width() - 8 - width(label, bold, 8.5f) : x + 8;
            text(bold, 8.5f, textX, y - 13.5f, label, PAPER);
            x += column.width();
        }
        y -= HEAD_HEIGHT;
    }

    /**
     * Shares spare width or shrinks columns proportionally so the given natural widths fill {@code available}.
     */
    static float[] fitWidths(float[] natural, float available) {
        float total = 0;
        for (float w : natural) total += w;
        float[] widths = new float[natural.length];
        for (int i = 0; i < natural.length; i++) {
            widths[i] = total > available ? natural[i] * available / total : natural[i] + (available - total) / natural.length;
        }
        return widths;
    }

    // ---------------------------------------------------------------- totals & sign-off

    /**
     * Pinned two-column bottom block. Left (55%): a notes / instructions / checklist box with an indigo left rule and
     * optional signature lines. Right (40%): a right-aligned summary table whose final line is a prominent total bar.
     */
    void totals(String noteTitle, List<String> noteLines, List<String[]> signatures, List<SummaryLine> summary) throws IOException {
        float leftWidth = width() * 0.55f;
        float rightWidth = width() * 0.40f;
        float rightX = right() - rightWidth;
        List<String> wrapped = new ArrayList<>();
        for (String line : noteLines) {
            if (line == null || line.isBlank()) { wrapped.add(""); continue; }
            wrapped.addAll(wrap(line, regular, 8.5f, leftWidth - 24));
        }
        float noteHeight = 14 + (noteTitle == null ? 0 : 14) + wrapped.size() * 11.5f + (signatures.isEmpty() ? 0 : 10 + signatures.size() * 34);
        float summaryHeight = 0;
        for (SummaryLine line : summary) summaryHeight += line.emphasized() ? 26 : 17;
        float height = Math.max(noteHeight, summaryHeight);
        ensure(height + 16);
        float top = y;

        if (noteTitle != null || !wrapped.isEmpty() || !signatures.isEmpty()) {
            stream.setNonStrokingColor(PANEL);
            stream.addRect(left(), top - noteHeight, leftWidth, noteHeight);
            stream.fill();
            stream.setNonStrokingColor(INDIGO);
            stream.addRect(left(), top - noteHeight, 3, noteHeight);
            stream.fill();
            float ny = top - 14;
            if (noteTitle != null) {
                trackedAt(bold, 8, left() + 12, ny, safe(noteTitle).toUpperCase(Locale.US), MUTED, 0.06f);
                ny -= 14;
            }
            for (String line : wrapped) {
                if (!line.isEmpty()) text(regular, 8.5f, left() + 12, ny, line, SLATE);
                ny -= 11.5f;
            }
            if (!signatures.isEmpty()) {
                ny -= 6;
                for (String[] signature : signatures) {
                    ny -= 14;
                    float lineWidth = leftWidth - 24;
                    float sigWidth = lineWidth * 0.62f;
                    stream.setStrokingColor(INK);
                    stream.setLineWidth(0.6f);
                    stream.moveTo(left() + 12, ny);
                    stream.lineTo(left() + 12 + sigWidth, ny);
                    stream.moveTo(left() + 12 + sigWidth + 12, ny);
                    stream.lineTo(left() + 12 + lineWidth, ny);
                    stream.stroke();
                    ny -= 10;
                    String who = signature[0] + (signature.length > 1 && signature[1] != null && !signature[1].isBlank() ? "  (" + signature[1] + ")" : "");
                    text(regular, 7.5f, left() + 12, ny, fit(who, regular, 7.5f, sigWidth), MUTED);
                    text(regular, 7.5f, left() + 12 + sigWidth + 12, ny, "Date", MUTED);
                    ny -= 10;
                }
            }
        }

        float sy = top;
        for (SummaryLine line : summary) {
            if (line.emphasized()) {
                fillRounded(rightX, sy - 26, rightWidth, 26, 5, INK);
                text(bold, 9, rightX + 12, sy - 16.5f, line.label(), PAPER);
                float vw = width(line.value(), bold, 13);
                text(bold, 13, rightX + rightWidth - 12 - vw, sy - 17.5f, line.value(), PAPER);
                sy -= 26;
            } else {
                text(regular, 9, rightX + 12, sy - 12, line.label(), MUTED);
                float vw = width(line.value(), bold, 9.5f);
                text(bold, 9.5f, rightX + rightWidth - 12 - vw, sy - 12, line.value(), INK);
                sy -= 17;
                stream.setStrokingColor(HAIRLINE);
                stream.setLineWidth(0.5f);
                stream.moveTo(rightX + 12, sy);
                stream.lineTo(rightX + rightWidth - 12, sy);
                stream.stroke();
            }
        }
        y = top - height - 12;
    }

    /** Standalone signature block (two lines side by side) when a document has no totals. */
    void signatures(List<String[]> signatures) throws IOException {
        float height = 20 + signatures.size() * 36;
        ensure(height);
        float ny = y - 6;
        for (String[] signature : signatures) {
            ny -= 22;
            float lineWidth = width();
            float sigWidth = lineWidth * 0.6f;
            stream.setStrokingColor(INK);
            stream.setLineWidth(0.6f);
            stream.moveTo(left(), ny);
            stream.lineTo(left() + sigWidth, ny);
            stream.moveTo(left() + sigWidth + 24, ny);
            stream.lineTo(left() + lineWidth, ny);
            stream.stroke();
            ny -= 11;
            String who = signature[0] + (signature.length > 1 && signature[1] != null && !signature[1].isBlank() ? "  (" + signature[1] + ")" : "");
            text(regular, 8, left(), ny, fit(who, regular, 8, sigWidth), MUTED);
            text(regular, 8, left() + sigWidth + 24, ny, "Date", MUTED);
        }
        y = ny - 8;
    }

    // ---------------------------------------------------------------- finish

    /** Footer on every page, then the bytes. */
    byte[] finish() throws IOException {
        stream.close();
        stream = null;
        int pages = document.getNumberOfPages();
        String stamp = "Generated on " + LocalDateTime.now().format(STAMP);
        String leftText = "Generated by " + organization + (organization.equals("DuesDesk") ? "" : " with DuesDesk");
        String record = footerSubject.isBlank() ? "Official Financial Record" : "Official Financial Record  |  " + footerSubject;
        for (int p = 0; p < pages; p++) {
            try (PDPageContentStream footer = new PDPageContentStream(document, document.getPage(p),
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                float fy = MARGIN - 6;
                footer.setStrokingColor(HAIRLINE);
                footer.setLineWidth(0.5f);
                footer.moveTo(left(), fy + 12);
                footer.lineTo(right(), fy + 12);
                footer.stroke();
                String pageText = "Page " + (p + 1) + " of " + pages;
                float pageWidth = width(pageText, bold, 8);
                float sw = width(stamp, regular, 8);
                float stampX = right() - pageWidth - 24 - sw;
                float leftRoom = stampX - 12 - left();
                String leftLine = fit(leftText, regular, 8, leftRoom * 0.55f);
                float lw = width(leftLine, regular, 8);
                show(footer, regular, 8, left(), fy, leftLine, CAPTION);
                footer.setNonStrokingColor(CAPTION);
                footer.addRect(left() + lw + 5, fy + 2.5f, 2.2f, 2.2f);
                footer.fill();
                show(footer, regular, 8, left() + lw + 11, fy, fit(record, regular, 8, leftRoom - lw - 11), CAPTION);
                show(footer, regular, 8, stampX, fy, stamp, CAPTION);
                show(footer, bold, 8, right() - pageWidth, fy, pageText, MUTED);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.save(output);
            document.close();
            return output.toByteArray();
        }
    }

    // ---------------------------------------------------------------- primitives

    void text(PDFont font, float size, float x, float yPos, String value, Color color) throws IOException {
        show(stream, font, size, x, yPos, value, color);
    }

    private static void show(PDPageContentStream target, PDFont font, float size, float x, float yPos, String value, Color color) throws IOException {
        target.beginText();
        target.setNonStrokingColor(color);
        target.setFont(font, size);
        target.newLineAtOffset(x, yPos);
        target.showText(safe(value));
        target.endText();
    }

    /** Right-aligned tracked text (letter-spacing as a fraction of the size). */
    private void tracked(PDFont font, float size, float rightX, float yPos, String value, Color color, float tracking) throws IOException {
        float w = width(value, font, size) + Math.max(0, value.length() - 1) * tracking * size;
        trackedAt(font, size, rightX - w, yPos, value, color, tracking);
    }

    private void trackedAt(PDFont font, float size, float x, float yPos, String value, Color color, float tracking) throws IOException {
        stream.beginText();
        stream.setNonStrokingColor(color);
        stream.setFont(font, size);
        stream.setCharacterSpacing(tracking * size);
        stream.newLineAtOffset(x, yPos);
        stream.showText(safe(value));
        stream.setCharacterSpacing(0);
        stream.endText();
    }

    void pill(float x, float yBottom, float w, float h, String label, Tone tone, float size) throws IOException {
        strokeRounded(x, yBottom, w, h, h / 2, tone.fill, tone.border);
        float tw = width(label, bold, size);
        text(bold, size, x + (w - tw) / 2, yBottom + (h - size) / 2 + 1, label, tone.text);
    }

    void hairline(float x1, float yPos, float x2) throws IOException {
        stream.setStrokingColor(HAIRLINE);
        stream.setLineWidth(0.5f);
        stream.moveTo(x1, yPos);
        stream.lineTo(x2, yPos);
        stream.stroke();
    }

    void fillRounded(float x, float yBottom, float w, float h, float r, Color fill) throws IOException {
        roundedPath(x, yBottom, w, h, r);
        stream.setNonStrokingColor(fill);
        stream.fill();
    }

    void strokeRounded(float x, float yBottom, float w, float h, float r, Color fill, Color border) throws IOException {
        roundedPath(x, yBottom, w, h, r);
        stream.setNonStrokingColor(fill);
        stream.setStrokingColor(border);
        stream.setLineWidth(1);
        stream.fillAndStroke();
    }

    private void roundedPath(float x, float yBottom, float w, float h, float r) throws IOException {
        float k = 0.5523f * r;
        float top = yBottom + h;
        float rightEdge = x + w;
        stream.moveTo(x + r, yBottom);
        stream.lineTo(rightEdge - r, yBottom);
        stream.curveTo(rightEdge - r + k, yBottom, rightEdge, yBottom + r - k, rightEdge, yBottom + r);
        stream.lineTo(rightEdge, top - r);
        stream.curveTo(rightEdge, top - r + k, rightEdge - r + k, top, rightEdge - r, top);
        stream.lineTo(x + r, top);
        stream.curveTo(x + r - k, top, x, top - r + k, x, top - r);
        stream.lineTo(x, yBottom + r);
        stream.curveTo(x, yBottom + r - k, x + r - k, yBottom, x + r, yBottom);
        stream.closePath();
    }

    static float width(String value, PDFont font, float size) throws IOException {
        return font.getStringWidth(safe(value)) / 1000 * size;
    }

    /** Shortens text with "..." so it fits {@code maxWidth}. */
    static String fit(String value, PDFont font, float size, float maxWidth) throws IOException {
        String text = safe(value);
        if (width(text, font, size) <= maxWidth) return text;
        int end = text.length();
        while (end > 0 && width(text.substring(0, end) + "...", font, size) > maxWidth) end--;
        return text.substring(0, end).stripTrailing() + "...";
    }

    static List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : safe(text).split(" ")) {
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

    /** The built-in fonts only cover WinAnsi; accents are folded and anything else becomes "?". */
    static String safe(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "?");
    }

    /** The chapter name for a letterhead; blank when settings are unavailable (unit tests mock the store). */
    static String chapterOf(Store store) {
        if (store == null || store.getSettings() == null || store.getSettings().getChapterName() == null) return "";
        return store.getSettings().getChapterName();
    }

    static String money(double value) {
        return String.format(Locale.US, "$%,.2f", value);
    }

    static String signedMoney(double value) {
        return (value < 0 ? "-" : "+") + money(Math.abs(value));
    }
}
