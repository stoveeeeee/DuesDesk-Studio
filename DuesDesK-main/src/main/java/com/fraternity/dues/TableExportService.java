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
import java.util.List;
import java.util.Locale;

/**
 * Printable PDF of any table on screen. The page collects what the table currently shows (after its filters, search and
 * sort) and posts it here, so the file always matches the full-screen view it was exported from.
 */
public class TableExportService {

    /** A table as displayed: header labels, one list of cell text per visible row, and which columns are numeric. */
    public record TableData(String title, String subtitle, List<String> headers, List<List<String>> rows,
                            List<Boolean> numeric) {}

    static final int MAX_COLUMNS = 20;
    static final int MAX_ROWS = 5000;
    private static final int MAX_CELL_LENGTH = 500;

    private static final float MARGIN = 40;
    private static final float ROW_HEIGHT = 18;
    private static final float CELL_PAD = 6;
    private static final float BODY_SIZE = 8.5f;
    private static final float HEAD_SIZE = 7.5f;
    private static final Color INK = new Color(15, 23, 42);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color HEADER_FILL = new Color(238, 242, 247);
    private static final Color STRIPE_FILL = new Color(248, 250, 252);
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    /** e.g. DuesDesk-member-dues-ledger-2026-10-24.pdf */
    public static String fileName(String title, LocalDate today) {
        String slug = (title == null ? "" : title).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return "DuesDesk-" + (slug.isEmpty() ? "table" : slug) + "-" + today + ".pdf";
    }

    /** Checks the posted table is a sane size and returns a copy with every row padded or cut to the header count. */
    static TableData validate(TableData data) {
        if (data == null || data.headers() == null || data.headers().isEmpty()) {
            throw new IllegalArgumentException("The table has no columns to export.");
        }
        if (data.headers().size() > MAX_COLUMNS) throw new IllegalArgumentException("The table has too many columns to export.");
        List<List<String>> source = data.rows() == null ? List.of() : data.rows();
        if (source.size() > MAX_ROWS) throw new IllegalArgumentException("The table has too many rows to export.");
        int columns = data.headers().size();
        List<List<String>> rows = new ArrayList<>();
        for (List<String> row : source) {
            List<String> cells = new ArrayList<>(columns);
            for (int c = 0; c < columns; c++) cells.add(clean(row != null && c < row.size() ? row.get(c) : ""));
            rows.add(cells);
        }
        List<String> headers = data.headers().stream().map(TableExportService::clean).toList();
        return new TableData(clean(data.title()), clean(data.subtitle()), headers, rows, data.numeric());
    }

    private static String clean(String value) {
        if (value == null) return "";
        // The page joins a cell's lines with a middle dot, which the PDF's built-in font can't draw.
        String text = value.replace(" · ", " - ").replaceAll("\\s+", " ").trim();
        return text.length() > MAX_CELL_LENGTH ? text.substring(0, MAX_CELL_LENGTH) : text;
    }

    public byte[] pdf(TableData posted) throws IOException {
        TableData data = validate(posted);
        int columns = data.headers().size();
        PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDRectangle landscape = new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth());
        float top = landscape.getHeight() - MARGIN;
        float[] widths = columnWidths(data, regular, bold, landscape.getWidth() - 2 * MARGIN);
        float tableWidth = 0;
        for (float width : widths) tableWidth += width;
        String title = data.title().isBlank() ? "Table" : data.title();
        String generated = "Generated " + LocalDate.now().format(DISPLAY_DATE);
        String rowCount = data.rows().size() + (data.rows().size() == 1 ? " row" : " rows");
        String details = data.subtitle().isBlank() ? rowCount : rowCount + "   |   " + data.subtitle();

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(landscape);
            document.addPage(page);
            PDPageContentStream stream = new PDPageContentStream(document, page);
            float y = top;
            FlowReportService.text(stream, bold, 16, MARGIN, y - 14, title, INK);
            y -= 34;
            FlowReportService.text(stream, regular, 9.5f, MARGIN, y, details + "   |   " + generated, MUTED);
            y -= 22;
            y = header(stream, bold, data, widths, y, tableWidth);

            if (data.rows().isEmpty()) {
                FlowReportService.text(stream, regular, 10, MARGIN + CELL_PAD, y - 12, "No rows to show.", MUTED);
            }
            for (int i = 0; i < data.rows().size(); i++) {
                if (y - ROW_HEIGHT < MARGIN + 18) {
                    stream.close();
                    page = new PDPage(landscape);
                    document.addPage(page);
                    stream = new PDPageContentStream(document, page);
                    y = header(stream, bold, data, widths, top, tableWidth);
                }
                if (i % 2 == 1) {
                    stream.setNonStrokingColor(STRIPE_FILL);
                    stream.addRect(MARGIN, y - ROW_HEIGHT, tableWidth, ROW_HEIGHT);
                    stream.fill();
                }
                float x = MARGIN;
                for (int c = 0; c < columns; c++) {
                    String cell = FlowReportService.fit(data.rows().get(i).get(c), regular, BODY_SIZE, widths[c] - 2 * CELL_PAD);
                    float textX = numeric(data, c) ? x + widths[c] - CELL_PAD - FlowReportService.width(cell, regular, BODY_SIZE) : x + CELL_PAD;
                    FlowReportService.text(stream, regular, BODY_SIZE, textX, y - 12.5f, cell, INK);
                    x += widths[c];
                }
                y -= ROW_HEIGHT;
            }
            stream.close();

            int pages = document.getNumberOfPages();
            for (int p = 0; p < pages; p++) {
                try (PDPageContentStream footer = new PDPageContentStream(document, document.getPage(p),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    FlowReportService.text(footer, regular, 8, MARGIN, MARGIN - 16, "DuesDesk  |  " + title, MUTED);
                    String pageText = "Page " + (p + 1) + " of " + pages;
                    FlowReportService.text(footer, regular, 8,
                            MARGIN + tableWidth - FlowReportService.width(pageText, regular, 8), MARGIN - 16, pageText, MUTED);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static boolean numeric(TableData data, int column) {
        return data.numeric() != null && column < data.numeric().size() && Boolean.TRUE.equals(data.numeric().get(column));
    }

    /**
     * Each column gets the width of its longest text. When that is wider than the page the columns shrink in
     * proportion (long text is then cut with "..."); when it is narrower the spare room is shared out evenly.
     */
    private static float[] columnWidths(TableData data, PDFont regular, PDFont bold, float available) throws IOException {
        int columns = data.headers().size();
        float[] widths = new float[columns];
        float total = 0;
        for (int c = 0; c < columns; c++) {
            float widest = FlowReportService.width(data.headers().get(c).toUpperCase(Locale.US), bold, HEAD_SIZE);
            for (List<String> row : data.rows()) widest = Math.max(widest, FlowReportService.width(row.get(c), regular, BODY_SIZE));
            widths[c] = Math.max(widest + 2 * CELL_PAD, 40);
            total += widths[c];
        }
        for (int c = 0; c < columns; c++) {
            widths[c] = total > available ? widths[c] * available / total : widths[c] + (available - total) / columns;
        }
        return widths;
    }

    private float header(PDPageContentStream stream, PDFont bold, TableData data, float[] widths, float y, float tableWidth)
            throws IOException {
        stream.setNonStrokingColor(HEADER_FILL);
        stream.addRect(MARGIN, y - ROW_HEIGHT, tableWidth, ROW_HEIGHT);
        stream.fill();
        float x = MARGIN;
        for (int c = 0; c < widths.length; c++) {
            String label = FlowReportService.fit(data.headers().get(c).toUpperCase(Locale.US), bold, HEAD_SIZE, widths[c] - 2 * CELL_PAD);
            float textX = numeric(data, c) ? x + widths[c] - CELL_PAD - FlowReportService.width(label, bold, HEAD_SIZE) : x + CELL_PAD;
            FlowReportService.text(stream, bold, HEAD_SIZE, textX, y - 12, label, MUTED);
            x += widths[c];
        }
        return y - ROW_HEIGHT;
    }
}
