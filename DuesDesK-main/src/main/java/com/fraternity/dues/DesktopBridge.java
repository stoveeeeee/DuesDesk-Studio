package com.fraternity.dues;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;

/** Exposed to page JavaScript as {@code window.desktopBridge}; WebView cannot download files itself. */
public class DesktopBridge {
    private final Stage stage;
    private final Store store;
    private final MemberDocumentService documentService = new MemberDocumentService();
    private final BackupEncryptionService backupEncryptionService = new BackupEncryptionService();
    private final FinanceDocumentService financeDocumentService;
    private final FlowReportService flowReportService;
    private final TableExportService tableExportService = new TableExportService();
    private final javafx.application.HostServices hostServices;

    public DesktopBridge(Stage stage, Store store, javafx.application.HostServices hostServices) {
        this.stage = stage;
        this.store = store;
        this.hostServices = hostServices;
        this.financeDocumentService = new FinanceDocumentService(store);
        this.flowReportService = new FlowReportService(store);
    }

    /**
     * Opens a reimbursement receipt in the computer's image viewer. Following the link inside the WebView would
     * replace the whole app with the image, and the desktop window has no back button.
     */
    public void openReceipt(String fileName) {
        try {
            byte[] bytes = store.readReceipt(fileName); // validates the name and that it lives in the receipts folder
            java.nio.file.Path copy = Files.createTempFile("DuesDesk-receipt-", fileName.endsWith(".png") ? ".png" : ".jpg");
            Files.write(copy, bytes);
            copy.toFile().deleteOnExit();
            hostServices.showDocument(copy.toUri().toString());
        } catch (Exception e) {
            show(Alert.AlertType.ERROR, "The receipt could not be opened: " + e.getMessage());
        }
    }

    /**
     * Opens a mailto:, sms: or https: link with the computer's default app: Mail or Outlook for email, Messages on
     * macOS or Phone Link on Windows for texts, the browser for pay links. The WebView can't follow these itself.
     */
    public void openExternal(String uri) {
        String value = uri == null ? "" : uri.trim();
        String scheme = value.contains(":") ? value.substring(0, value.indexOf(':')).toLowerCase(java.util.Locale.ROOT) : "";
        if (!java.util.Set.of("mailto", "sms", "https").contains(scheme) || value.length() > 8000) {
            show(Alert.AlertType.ERROR, "That link can't be opened.");
            return;
        }
        Thread opener = new Thread(() -> {
            try {
                java.net.URI target = new java.net.URI(value);
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                    if ("mailto".equals(scheme) && desktop.isSupported(java.awt.Desktop.Action.MAIL)) {
                        desktop.mail(target);
                        return;
                    }
                    if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                        desktop.browse(target);
                        return;
                    }
                }
                hostServices.showDocument(value);
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> show(Alert.AlertType.ERROR,
                        "No app on this computer opened the link" + ("sms".equals(scheme)
                                ? ". On Windows, install Phone Link to send texts from your PC; you can also use Copy reminder." : ".")));
            }
        }, "duesdesk-open-link");
        opener.setDaemon(true);
        opener.start();
    }

    /** Puts text on the system clipboard (the WebView has no clipboard API). */
    public void copyToClipboard(String text) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text == null ? "" : text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    public void exportData(String password) {
        try {
            byte[] encryptedData = backupEncryptionService.encrypt(store.exportData(),
                    password == null ? null : password.toCharArray());
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export encrypted DuesDesk data");
            chooser.setInitialFileName(DashboardController.encryptedExportFileName());
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Encrypted DuesDesk backup (*.ddbk)", "*.ddbk"));
            File target = chooser.showSaveDialog(stage);
            if (target == null) return;
            Files.write(target.toPath(), encryptedData);
            show(Alert.AlertType.INFORMATION, "Encrypted backup exported to:\n" + target.getAbsolutePath()
                    + "\n\nKeep the password separate from the backup file.");
        } catch (Exception e) {
            show(Alert.AlertType.ERROR, "Export failed: " + e.getMessage());
        }
    }

    public void exportMemberDocument(String memberId, String documentType, String paymentKey) {
        try {
            Member member = store.findMemberById(memberId)
                    .orElseThrow(() -> new IllegalArgumentException("Member was not found."));
            String type = documentType == null ? "" : documentType;
            byte[] bytes;
            String extension;
            String description;
            if ("statement-pdf".equals(type)) {
                bytes = documentService.statementPdf(member);
                extension = "pdf";
                description = "PDF statement";
            } else if ("statement-csv".equals(type)) {
                bytes = documentService.statementCsv(member);
                extension = "csv";
                description = "CSV statement";
            } else if ("agreement".equals(type)) {
                if (!PaymentPlanAgreementService.hasPlan(member)) throw new IllegalArgumentException("This member has no installment plan.");
                bytes = new PaymentPlanAgreementService().agreementPdf(member, store.getSettings(), java.time.LocalDate.now());
                extension = "pdf";
                description = "Payment plan agreement";
            } else if ("receipt".equals(type)) {
                PaymentRecord payment = member.getPaymentHistory() == null ? null : member.getPaymentHistory().stream()
                        .filter(item -> paymentKey != null && paymentKey.equals(item.getIdempotencyKey()))
                        .findFirst().orElse(null);
                if (payment == null) throw new IllegalArgumentException("Payment receipt was not found.");
                bytes = documentService.receiptPdf(member, payment);
                extension = "pdf";
                description = "Payment receipt";
            } else {
                throw new IllegalArgumentException("Unknown member document type.");
            }

            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save " + description);
            String suffix = "receipt".equals(type) ? "receipt" : "agreement".equals(type) ? "payment-plan-agreement" : "statement";
            chooser.setInitialFileName(fileName(member.getName()) + "-" + suffix + "." + extension);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    extension.toUpperCase(java.util.Locale.ROOT) + " file", "*." + extension));
            File target = chooser.showSaveDialog(stage);
            if (target == null) return;
            Files.write(target.toPath(), bytes);
            show(Alert.AlertType.INFORMATION, description + " saved to:\n" + target.getAbsolutePath());
        } catch (Exception e) {
            show(Alert.AlertType.ERROR, "Document export failed: " + e.getMessage());
        }
    }

    public void exportFinanceDocument(String documentType, String password) {
        try {
            String type = documentType == null ? "" : documentType;
            byte[] bytes;
            String fileName;
            String description;
            switch (type) {
                case "clearance-pdf" -> {
                    bytes = financeDocumentService.socialClearancePdf();
                    fileName = "DuesDesk-social-clearance-" + java.time.LocalDate.now() + ".pdf";
                    description = "Social clearance PDF";
                }
                case "clearance-csv" -> {
                    bytes = financeDocumentService.socialClearanceCsv();
                    fileName = "DuesDesk-social-clearance-" + java.time.LocalDate.now() + ".csv";
                    description = "Social clearance CSV";
                }
                case "meeting-packet" -> {
                    bytes = financeDocumentService.meetingPacketPdf();
                    fileName = "DuesDesk-meeting-packet-" + java.time.LocalDate.now() + ".pdf";
                    description = "Meeting packet";
                }
                case "handoff-package" -> {
                    if (password == null || password.length() < 8) {
                        throw new IllegalArgumentException("Use a backup password with at least 8 characters.");
                    }
                    bytes = financeDocumentService.handoffPackage(password.toCharArray());
                    fileName = "DuesDesk-officer-handoff-" + java.time.LocalDate.now() + ".zip";
                    description = "Officer handoff package";
                }
                default -> throw new IllegalArgumentException("Unknown finance document type.");
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save " + description);
            chooser.setInitialFileName(fileName);
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    extension.toUpperCase(java.util.Locale.ROOT) + " file", "*." + extension));
            File target = chooser.showSaveDialog(stage);
            if (target == null) return;
            Files.write(target.toPath(), bytes);
            show(Alert.AlertType.INFORMATION, description + " saved to:\n" + target.getAbsolutePath());
        } catch (Exception e) {
            show(Alert.AlertType.ERROR, "Finance document export failed: " + e.getMessage());
        }
    }

    /**
     * Saves the Inflow/Outflow table as "csv" or "pdf" for a time period (same parameters as the page), narrowed
     * by the page's quick filter ({@code kind}) and search text ({@code query}) when given.
     */
    public void exportFlowReport(String format, String period, String from, String to, String kind, String query) {
        try {
            boolean pdf = "pdf".equals(format);
            FlowReportService.Period selected = FlowReportService.resolvePeriod(period, from, to, java.time.LocalDate.now());
            byte[] bytes = pdf ? flowReportService.pdf(selected, kind, query) : flowReportService.csv(selected, kind, query);
            String extension = pdf ? "pdf" : "csv";
            FileChooser chooser = new FileChooser();
            chooser.setTitle(pdf ? "Save Cash Flow report" : "Save Cash Flow spreadsheet");
            chooser.setInitialFileName(FlowReportService.fileName(selected, extension, java.time.LocalDate.now()));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    extension.toUpperCase(java.util.Locale.ROOT) + " file", "*." + extension));
            File target = chooser.showSaveDialog(stage);
            if (target == null) return;
            Files.write(target.toPath(), bytes);
            show(Alert.AlertType.INFORMATION, "Cash Flow " + (pdf ? "report" : "spreadsheet") + " saved to:\n" + target.getAbsolutePath());
        } catch (Exception e) {
            show(Alert.AlertType.ERROR, "Cash Flow export failed: " + e.getMessage());
        }
    }

    /** Saves a table as a PDF; {@code json} is the table as the full-screen view shows it (see TableExportService.TableData). */
    public void exportTablePdf(String json) {
        try {
            TableExportService.TableData table = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, TableExportService.TableData.class);
            byte[] bytes = tableExportService.pdf(table);
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save table as PDF");
            chooser.setInitialFileName(TableExportService.fileName(table.title(), java.time.LocalDate.now()));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF file", "*.pdf"));
            File target = chooser.showSaveDialog(stage);
            if (target == null) return;
            Files.write(target.toPath(), bytes);
            show(Alert.AlertType.INFORMATION, "Table PDF saved to:\n" + target.getAbsolutePath());
        } catch (Exception e) {
            show(Alert.AlertType.ERROR, "Table export failed: " + e.getMessage());
        }
    }

    private String fileName(String value) {
        String name = value == null ? "member" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        String safe = name.replaceAll("^-|-$", "");
        return safe.isBlank() ? "member" : safe;
    }

    private void show(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.initOwner(stage);
        alert.showAndWait();
    }
}
