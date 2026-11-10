package com.sakupay.statement;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.sakupay.statement.StatementService.Line;
import com.sakupay.statement.StatementService.Statement;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Renders a statement as CSV or PDF. */
public final class StatementExports {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Locale INDONESIA = Locale.forLanguageTag("id-ID");

    private StatementExports() {
    }

    // ---- CSV ----

    /** UTF-8 with a byte-order mark, so Excel shows the text correctly. */
    public static byte[] csv(Statement s) {
        StringBuilder out = new StringBuilder("﻿");
        row(out, List.of("Date", "Reference", "Type", "Status", "Direction", "Amount", "Balance after", "Counterparty",
                "Description"));
        DateTimeFormatter time = TIME.withZone(s.timeZone());
        for (Line l : s.lines()) {
            row(out, List.of(time.format(l.date()), l.reference(), l.type().name(), l.status().name(), l.direction().name(),
                    Long.toString(l.amount()), Long.toString(l.balanceAfter()), nullToEmpty(l.counterparty()),
                    nullToEmpty(l.description())));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(csvCell(cells.get(i)));
        }
        out.append("\r\n");
    }

    /**
     * Quotes a cell and defuses spreadsheet formulas (CSV / formula injection): a description such as
     * {@code =HYPERLINK("http://evil",...)} typed by another user would otherwise run when the statement is opened
     * in Excel. Cells starting with = + - @ or a control character get a leading apostrophe (OWASP advice).
     */
    static String csvCell(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !isNumber(v)) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static boolean isNumber(String v) {
        return v.matches("-?\\d+");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---- PDF ----

    public static byte[] pdf(Statement s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
        PdfWriter.getInstance(doc, out);
        doc.addTitle("SakuPay statement " + s.accountNumber());
        doc.addCreator("SakuPay");
        doc.open();

        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        NumberFormat rupiah = NumberFormat.getIntegerInstance(INDONESIA);

        doc.add(new Paragraph("SakuPay - Account statement", title));
        doc.add(new Paragraph("Account " + s.accountNumber() + "   Period " + s.from() + " to " + s.to()
                + " (" + s.timeZone() + ")", normal));
        doc.add(new Paragraph("Opening balance Rp" + rupiah.format(s.openingBalance())
                + "   Credits Rp" + rupiah.format(s.totalCredits())
                + "   Debits Rp" + rupiah.format(s.totalDebits())
                + "   Closing balance Rp" + rupiah.format(s.closingBalance()), bold));
        doc.add(new Paragraph(" "));

        PdfPTable table = new PdfPTable(new float[]{14, 14, 6, 12, 12, 12, 30});
        table.setWidthPercentage(100);
        for (String h : List.of("Date", "Type", "D/C", "Amount", "Balance", "Counterparty", "Description")) {
            PdfPCell cell = new PdfPCell(new Phrase(h, bold));
            cell.setGrayFill(0.9f);
            table.addCell(cell);
        }
        DateTimeFormatter time = TIME.withZone(s.timeZone());
        for (Line l : s.lines()) {
            table.addCell(new Phrase(time.format(l.date()), normal));
            String type = l.status() == com.sakupay.ledger.TransactionStatus.COMPLETED
                    ? l.type().name() : l.type().name() + " (" + l.status() + ")";
            table.addCell(new Phrase(type, normal));
            table.addCell(new Phrase(l.direction() == com.sakupay.ledger.EntryDirection.CREDIT ? "CR" : "DB", normal));
            table.addCell(right(rupiah.format(l.amount()), normal));
            table.addCell(right(rupiah.format(l.balanceAfter()), normal));
            table.addCell(new Phrase(nullToEmpty(l.counterparty()), normal));
            table.addCell(new Phrase(nullToEmpty(l.description()), normal));
        }
        if (s.lines().isEmpty()) {
            PdfPCell none = new PdfPCell(new Phrase("No transactions in this period.", normal));
            none.setColspan(7);
            table.addCell(none);
        }
        doc.add(table);
        doc.close();
        return out.toByteArray();
    }

    private static PdfPCell right(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return cell;
    }
}
