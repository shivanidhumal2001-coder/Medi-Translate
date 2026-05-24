package com.meditranslate.service.impl;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.meditranslate.entity.MedicationReminder;
import com.meditranslate.entity.ReportAnalysis;
import com.meditranslate.entity.ReportFinding;
import com.meditranslate.service.PdfExportService;
import java.io.ByteArrayOutputStream;
import org.springframework.stereotype.Service;

@Service
public class OpenPdfExportService implements PdfExportService {

    @Override
    public byte[] exportReport(ReportAnalysis report) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, outputStream);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);

            document.add(new Paragraph("MediTranslate Report Summary", titleFont));
            document.add(new Paragraph(report.getTitle()));
            document.add(new Paragraph("Urgency: " + report.getUrgencyLevel()));
            document.add(new Paragraph("Trust score: " + report.getTrustScore() + "%"));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Simple Explanation", sectionFont));
            document.add(new Paragraph(report.getSimpleSummary()));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Verification Table", sectionFont));
            PdfPTable table = new PdfPTable(5);
            table.setWidthPercentage(100);
            addHeader(table, "Finding");
            addHeader(table, "Your Value");
            addHeader(table, "Normal Range");
            addHeader(table, "System Verified");
            addHeader(table, "Trust Match");

            for (ReportFinding finding : report.getFindings()) {
                table.addCell(finding.getParameterName());
                table.addCell((finding.getPatientValue() == null ? "-" : finding.getPatientValue()) + " " + (finding.getUnit() == null ? "" : finding.getUnit()));
                table.addCell(finding.getNormalRangeText() == null ? "-" : finding.getNormalRangeText());
                table.addCell(finding.getSystemInterpretation() == null ? "-" : finding.getSystemInterpretation());
                table.addCell(finding.isMatched() ? "Matched" : "Needs review");
            }
            document.add(table);

            if (!report.getReminders().isEmpty()) {
                document.add(new Paragraph(" "));
                document.add(new Paragraph("Medication Reminder Schedule", sectionFont));
                PdfPTable reminderTable = new PdfPTable(4);
                reminderTable.setWidthPercentage(100);
                addHeader(reminderTable, "Medicine");
                addHeader(reminderTable, "Dosage");
                addHeader(reminderTable, "Schedule");
                addHeader(reminderTable, "Instruction");

                for (MedicationReminder reminder : report.getReminders()) {
                    reminderTable.addCell(reminder.getMedicineName());
                    reminderTable.addCell(reminder.getDosage() == null ? "-" : reminder.getDosage());
                    reminderTable.addCell(reminder.getScheduleText());
                    reminderTable.addCell(reminder.getMealInstruction() == null ? "-" : reminder.getMealInstruction());
                }
                document.add(reminderTable);
            }

            document.add(new Paragraph(" "));
            document.add(new Paragraph(report.getDisclaimer() == null ? "" : report.getDisclaimer()));
            document.close();
            return outputStream.toByteArray();
        } catch (DocumentException ex) {
            throw new IllegalStateException("Unable to create PDF export", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Unexpected PDF export error", ex);
        }
    }

    private void addHeader(PdfPTable table, String label) {
        PdfPCell header = new PdfPCell(new Phrase(label));
        header.setPadding(6);
        table.addCell(header);
    }
}
