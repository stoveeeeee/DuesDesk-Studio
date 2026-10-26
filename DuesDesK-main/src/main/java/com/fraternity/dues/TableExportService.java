package com.fraternity.dues;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fraternity.dues.ExecutivePdf.Cell;
import com.fraternity.dues.ExecutivePdf.Column;
import com.fraternity.dues.ExecutivePdf.Tone;

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

    private static final float CELL_PAD = 8;
    private static final float BODY_SIZE = 8.5f;
    private static final float HEAD_SIZE = 8.5f;
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private final java.util.function.Supplier<String> chapterName;

    public TableExportService() { this(() -> ""); }

    public TableExportService(java.util.function.Supplier<String> chapterName) { this.chapterName = chapterName == null ? () -> "" : chapterName; }

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
        String title = data.title().isBlank() ? "Table" : data.title();
        String rowCount = data.rows().size() + (data.rows().size() == 1 ? " row" : " rows");
        String details = data.subtitle().isBlank() ? rowCount : rowCount + "   |   " + data.subtitle();

        ExecutivePdf pdf = new ExecutivePdf(chapterName.get(), title, true);
        pdf.letterhead(title + "  |  " + details, "Table Export", List.of(
                new String[] {"Generated", LocalDate.now().format(DISPLAY_DATE)},
                new String[] {"Rows", String.valueOf(data.rows().size())},
                new String[] {"Source", "DuesDesk full-screen view"}
        ), data.rows().isEmpty() ? "Empty" : "Exported", data.rows().isEmpty() ? Tone.NEUTRAL : Tone.SUCCESS);

        float[] widths = columnWidths(data, regular, bold, pdf.width());
        List<Column> spec = new ArrayList<>();
        for (int c = 0; c < columns; c++) spec.add(new Column(data.headers().get(c), widths[c], numeric(data, c)));
        List<List<Cell>> rows = new ArrayList<>();
        for (List<String> row : data.rows()) {
            List<Cell> cells = new ArrayList<>(columns);
            for (int c = 0; c < columns; c++) {
                String value = row.get(c);
                cells.add(c == 0 ? Cell.bold(value) : statusCell(data.headers().get(c), value));
            }
            rows.add(cells);
        }
        pdf.table(spec, rows, "No rows to show.");
        return pdf.finish();
    }

    /** Status-like columns render as print-safe pills; everything else is plain text. */
    private static Cell statusCell(String header, String value) {
        String key = header == null ? "" : header.toLowerCase(Locale.ROOT);
        String v = value == null ? "" : value.trim();
        if (!(key.contains("status") || key.contains("clearance")) || v.isEmpty() || v.length() > 18) return Cell.of(value);
        String lower = v.toLowerCase(Locale.ROOT);
        Tone tone = lower.contains("paid") && !lower.contains("unpaid") && !lower.contains("past") ? Tone.SUCCESS
                : lower.contains("past") || lower.contains("overdue") || lower.contains("delinq") || lower.contains("not ") ? Tone.DANGER
                : lower.contains("plan") || lower.contains("pending") || lower.contains("review") ? Tone.WARNING
                : lower.contains("clear") ? Tone.SUCCESS : Tone.NEUTRAL;
        return Cell.pill(v, tone);
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
        for (int c = 0; c < columns; c++) {
            float widest = ExecutivePdf.width(data.headers().get(c).toUpperCase(Locale.US), bold, HEAD_SIZE);
            for (List<String> row : data.rows()) widest = Math.max(widest, ExecutivePdf.width(row.get(c), c == 0 ? bold : regular, BODY_SIZE));
            widths[c] = Math.max(widest + 2 * CELL_PAD, 44);
        }
        return ExecutivePdf.fitWidths(widths, available);
    }
}
