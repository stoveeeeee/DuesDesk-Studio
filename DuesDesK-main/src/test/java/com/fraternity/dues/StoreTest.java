package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;

class StoreTest {
    @TempDir
    Path tempDir;

    @Test
    void simultaneousDuplicatePaymentRequestsOnlyApplyOnce() throws Exception {
        Store store = initializedStore();
        Member member = store.getMembers().getFirst();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> submitAtSameTime(store, member, ready, start));
            var second = executor.submit(() -> submitAtSameTime(store, member, ready, start));
            ready.await();
            start.countDown();

            int successfulWrites = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successfulWrites);
        }

        Member updatedMember = store.findMemberById(member.getId()).orElseThrow();
        assertEquals(60, updatedMember.getTotalOwed());
        assertEquals(1, updatedMember.getPaymentHistory().size());
    }

    @Test
    void corruptPrimaryStoreRestoresLatestValidBackup() throws Exception {
        Path file = tempDir.resolve("store.json");
        Store store = initializedStore(file);
        store.save();
        Files.writeString(file, "{ invalid json");

        Store restored = new Store();
        ReflectionTestUtils.setField(restored, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(restored, "smsDailyCap", 100);
        restored.init();

        assertEquals("Test Member", restored.getMembers().getFirst().getName());
        assertTrue(Files.isRegularFile(file.resolveSibling("store.json.bak")));
        assertTrue(new ObjectMapper().readTree(file.toFile()).path("members").isArray());
    }

    @Test
    void exportedSaveCanReplaceAnotherStoreWithAllSavedData() throws Exception {
        Path sourceFile = tempDir.resolve("source-store.json");
        Store.DataContainer sourceData = new Store.DataContainer();
        Member member = new Member("source-member", "Source Member", "555-111-1111",
                75, 25, 10, "Delinquent", "Never", true);
        member.setPaymentHistory(List.of(new PaymentRecord("2026-10-01", 20, "Dues", "payment-1")));
        member.setCharges(List.of(new ChargeRecord("charge-1", "2026-10-02", "Late fee", 5, 5)));
        sourceData.members.add(member);
        sourceData.settings.setSmsTemplate("Reminder for {name}");
        sourceData.settings.setIhqBillAmount(1200);
        sourceData.residents.add(new Resident("resident-1", "Resident", "555-222-2222",
                "A1", 800, 100, 700));
        sourceData.flowEntries.add(new FlowEntry("flow-1", "2026-10-03", "Venmo", "Member", 20, "Venmo"));
        sourceData.smsLogs.add(new SmsLog("2026-10-04T10:00:00", "Source Member",
                "555-111-1111", "Reminder", "SMS", "2026-10-04"));
        sourceData.smsUsageDate = "2026-10-21";
        sourceData.smsUsageCount = 3;
        new ObjectMapper().writeValue(sourceFile.toFile(), sourceData);

        Store source = new Store();
        ReflectionTestUtils.setField(source, "DATA_FILE", sourceFile.toString());
        ReflectionTestUtils.setField(source, "smsDailyCap", 100);
        source.init();
        Store destination = initializedStore(tempDir.resolve("destination-store.json"));
        destination.importData(source.exportData());

        assertEquals(List.of("Source Member"), destination.getMembers().stream().map(Member::getName).toList());
        Member importedMember = destination.getMembers().getFirst();
        assertEquals(20, importedMember.getPaymentHistory().getFirst().getAmount());
        assertEquals("charge-1", importedMember.getCharges().getFirst().getId());
        assertEquals("Reminder for {name}", destination.getSettings().getSmsTemplate());
        assertEquals(1200, destination.getSettings().getIhqBillAmount());
        assertEquals("resident-1", destination.getResidents().getFirst().getId());
        assertEquals("flow-1", destination.getFlowEntries().getFirst().getId());
        assertEquals("Source Member", destination.getSmsLogs().getFirst().getMemberName());

        Store restoredDestination = new Store();
        ReflectionTestUtils.setField(restoredDestination, "DATA_FILE",
                tempDir.resolve("destination-store.json").toString());
        ReflectionTestUtils.setField(restoredDestination, "smsDailyCap", 100);
        restoredDestination.init();
        assertEquals(List.of("Source Member"),
                restoredDestination.getMembers().stream().map(Member::getName).toList());
        assertEquals("flow-1", restoredDestination.getFlowEntries().getFirst().getId());
    }

    @Test
    void smsCapIsReservedAtomicallyAndPersistsForTheDay() throws Exception {
        Store store = initializedStore();
        ReflectionTestUtils.setField(store, "smsDailyCap", 1);

        assertTrue(store.reserveSmsSlot());
        assertFalse(store.reserveSmsSlot());
        assertEquals(0, store.getSmsRemainingToday());
    }

    @Test
    void metricHistoryTracksAndPersistsLatestDailySnapshot() throws Exception {
        Path file = tempDir.resolve("metric-store.json");
        Store store = initializedStore(file);
        Member member = store.getMembers().getFirst();
        member.setPaymentHistory(List.of(new PaymentRecord(LocalDate.now().toString(), 40, "Dues")));
        store.save();

        List<Store.MetricSnapshot> history = store.getMetricHistory();
        assertEquals(1, history.size());
        assertEquals(40.0 / 140 * 100,
                history.getFirst().values().get("net-collection-rate"), 0.001);

        Store restored = new Store();
        ReflectionTestUtils.setField(restored, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(restored, "smsDailyCap", 100);
        restored.init();
        assertEquals(history, restored.getMetricHistory());
    }

    @Test
    void twilioCredentialsPersistSeparatelyFromExportedSave() throws Exception {
        Path file = tempDir.resolve("twilio-store.json");
        Store store = initializedStore(file);
        Store.TwilioCredentials credentials =
                new Store.TwilioCredentials("AC0123456789abcdef0123456789abcdef", "secret-token", "+15551234567", true);

        store.saveTwilioCredentials(credentials);
        String exported = new String(store.exportData(), java.nio.charset.StandardCharsets.UTF_8);
        Path credentialsFile = file.resolveSibling("twilio-store.json.twilio.json");
        Store restored = new Store();
        ReflectionTestUtils.setField(restored, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(restored, "smsDailyCap", 100);
        restored.init();

        assertFalse(exported.contains("secret-token"));
        assertTrue(Files.isRegularFile(credentialsFile));
        assertEquals(credentials, restored.getTwilioCredentials());
        assertTrue(restored.isTwilioEnabled());
    }

    @Test
    void bankTransactionsRequireConfirmedReconciliationAndApplyPaymentOnce() throws Exception {
        Store store = initializedStore();
        MockMultipartFile bankCsv = new MockMultipartFile("file", "bank.csv", "text/csv",
                ("Date,Description,Name,Amount\n"
                        + LocalDate.now() + ",Dues payment,Test Member,25.00\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, store.importBankCsv(bankCsv)[0]);
        FlowEntry entry = store.getFlowEntries().getFirst();

        assertEquals("PENDING", entry.getReconciliationStatus());
        assertTrue(store.confirmReconciliation(entry.getId(), "member-1"));
        assertEquals("RECONCILED", entry.getReconciliationStatus());
        assertEquals(75, store.findMemberById("member-1").orElseThrow().getTotalOwed());
        assertFalse(store.confirmReconciliation(entry.getId(), "member-1"));
        assertEquals(1, store.findMemberById("member-1").orElseThrow().getPaymentHistory().size());
    }

    @Test
    void rememberedPayerAliasSuggestsTheMemberButBankDepositsStillWaitForConfirmation() throws Exception {
        Store store = initializedStore();
        String firstRow = "Date,Description,Name,Amount\n" + LocalDate.now()
                + ",Dues payment,Venmo Mike,25.00\n";
        store.importBankCsv(new MockMultipartFile("file", "first.csv", "text/csv",
                firstRow.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        FlowEntry first = store.getFlowEntries().getFirst();

        assertTrue(store.confirmReconciliation(first.getId(), "member-1", true));
        assertEquals("member-1", store.findMemberByCounterpartyAlias(" Venmo Mike ").orElseThrow().getId());
        assertEquals(List.of("Venmo Mike"), store.findMemberById("member-1").orElseThrow().getRecognizedPayerAliases());

        String secondRow = "Date,Description,Name,Amount\n" + LocalDate.now()
                + ",Dues payment,Venmo Mike,15.00\n";
        store.importBankCsv(new MockMultipartFile("file", "second.csv", "text/csv",
                secondRow.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertEquals("PENDING", store.getFlowEntries().getLast().getReconciliationStatus(),
                "a bank deposit never reaches a balance without the treasurer confirming it");
        assertEquals(1, store.findMemberById("member-1").orElseThrow().getPaymentHistory().size());
        assertEquals(75, store.findMemberById("member-1").orElseThrow().getTotalOwed());
        assertEquals("member-1", store.findMemberByCounterpartyAlias("Venmo Mike").orElseThrow().getId(), "but it is suggested");
    }

    @Test
    void lateFeesAreDisabledByDefaultAndAppliedOnceAfterConfiguredGrace() throws Exception {
        Store store = initializedStore();
        Member member = store.findMemberById("member-1").orElseThrow();
        ChargeRecord charge = new ChargeRecord("old-charge", LocalDate.now().minusDays(10).toString(),
                "Semester dues", 100, 100);
        charge.setDueDate(LocalDate.now().minusDays(7).toString());
        member.getCharges().add(charge);
        store.getSettings().setLateFeeGraceDays(7);

        assertEquals(100, store.getMembers().getFirst().getTotalOwed());
        store.getSettings().setLateFeesEnabled(true);
        store.getMembers();

        assertEquals(2, member.getCharges().size());
        assertEquals(125, member.getTotalOwed());
        assertEquals("old-charge", member.getCharges().getLast().getLateFeeForChargeId());
        store.getMembers();
        assertEquals(125, member.getTotalOwed());
        assertEquals(2, member.getCharges().size());
    }

    @Test
    void clearanceRulesSupportTolerancePlansManualVouchAndOptionalRent() throws Exception {
        Store store = initializedStore();
        Member member = store.findMemberById("member-1").orElseThrow();
        store.getSettings().setSocialClearanceTolerance(10);
        member.setTotalOwed(8);
        assertTrue(store.isSociallyCleared(member));

        member.setTotalOwed(80);
        member.setStatus("Payment Plan");
        member.setInstallments(List.of(new PaymentInstallment("future", LocalDate.now().plusDays(5).toString(),
                "Plan installment", 80)));
        assertTrue(store.isSociallyCleared(member));
        member.getInstallments().getFirst().setDueDate(LocalDate.now().minusDays(1).toString());
        assertFalse(store.isSociallyCleared(member));

        member.setClearedUntilDate(LocalDate.now().plusDays(3).toString());
        assertTrue(store.isSociallyCleared(member));
        member.setClearedUntilDate(null);
        member.setTotalOwed(0);
        store.addResident(new Resident("resident-1", "Test Member", "555-000-0000", "A1", 500, 40, 0));
        store.getSettings().setSocialClearanceDuesOnly(false);
        assertFalse(store.isSociallyCleared(member));
        store.getSettings().setSocialClearanceDuesOnly(true);
        assertTrue(store.isSociallyCleared(member));
    }

    @Test
    void chairChargeReceiptFilesSurviveSaveImport() throws Exception {
        Store source = initializedStore(tempDir.resolve("receipt-source.json"));
        ChairBudget budget = source.saveChairBudget("Rush", 3000);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(8, 8,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream imageBytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", imageBytes);
        String receiptFile = source.saveReceipt(new MockMultipartFile("receipt", "receipt.png",
                "image/png", imageBytes.toByteArray()));
        ReimbursementRecord reimbursement = new ReimbursementRecord("reimbursement-1", LocalDate.now().toString(),
                "Rush supplies", 20, receiptFile, null);

        assertTrue(source.addReimbursement(budget.getId(), reimbursement));
        Member member = source.findMemberById("member-1").orElseThrow();
        assertEquals(100, member.getTotalOwed(), "a chair charge never touches a member's dues");
        assertTrue(member.getCredits().isEmpty());

        Store destination = initializedStore(tempDir.resolve("receipt-destination.json"));
        destination.importData(source.exportData());
        assertEquals(List.of("Test Member"), destination.getMembers().stream().map(Member::getName).toList());
        assertTrue(java.util.Arrays.equals(imageBytes.toByteArray(), destination.readReceipt(receiptFile)));
        assertEquals(20, destination.getChairBudgets().getFirst().getReimbursements().getFirst().getAmount());
    }

    @Test
    void financePdfsAreAnonymousAndSocialClearanceOmitsBalances() throws Exception {
        Store store = initializedStore();
        Member member = store.findMemberById("member-1").orElseThrow();
        member.setName("Private Member");
        member.setTotalOwed(100);
        store.getSettings().setOpeningCashBalance(500);
        store.getSettings().setUpcomingBills("Housing | " + LocalDate.now().plusDays(30) + " | 800");
        FinanceDocumentService documents = new FinanceDocumentService(store);

        try (var clearance = org.apache.pdfbox.Loader.loadPDF(documents.socialClearancePdf());
             var packet = org.apache.pdfbox.Loader.loadPDF(documents.meetingPacketPdf())) {
            String clearanceText = new org.apache.pdfbox.text.PDFTextStripper().getText(clearance);
            String packetText = new org.apache.pdfbox.text.PDFTextStripper().getText(packet);
            assertTrue(clearanceText.contains("Private Member"));
            assertTrue(clearanceText.contains("NOT CLEARED"));
            assertFalse(clearanceText.contains("$100.00"));
            assertEquals(1, packet.getNumberOfPages());
            assertFalse(packetText.contains("Private Member"));
            assertTrue(packetText.contains("Estimated cash on hand"));
            assertTrue(packetText.contains("Housing"));
            assertTrue(packetText.contains("Total uncollected balance"));
        }
    }

    @Test
    void encryptedDailyBackupIncludesDataAndPrunesExpiredSnapshots() throws Exception {
        Store store = initializedStore();
        AppSettings settings = store.getSettings();
        settings.setCloudBackupEnabled(true);
        settings.setCloudBackupFolder(tempDir.resolve("sync").toString());
        settings.setCloudBackupRetentionDays(30);
        Path expired = Path.of(settings.getCloudBackupFolder())
                .resolve("DuesDesk-auto-backup-" + LocalDate.now().minusDays(40) + ".ddbk");
        Files.createDirectories(expired.getParent());
        Files.write(expired, new byte[]{1});
        SystemKeyStoreService credentials = org.mockito.Mockito.mock(SystemKeyStoreService.class);
        org.mockito.Mockito.when(credentials.getOrCreateBackupPassword())
                .thenReturn("private automatic backup key".toCharArray());
        BackupEncryptionService encryption = new BackupEncryptionService();
        CloudBackupService backups = new CloudBackupService(store, encryption, credentials);

        Path target = backups.createBackup();

        byte[] encrypted = Files.readAllBytes(target);
        assertTrue(encryption.isEncrypted(encrypted));
        assertTrue(new String(encryption.decrypt(encrypted, "private automatic backup key".toCharArray()),
                java.nio.charset.StandardCharsets.UTF_8).contains("Test Member"));
        assertFalse(Files.exists(expired));
    }

    @Test
    void officerPackageContainsEncryptedSaveAndHandoffInstructions() throws Exception {
        FinanceDocumentService documents = new FinanceDocumentService(initializedStore());
        byte[] packageBytes = documents.handoffPackage("secure handoff password".toCharArray());

        try (java.util.zip.ZipInputStream zip =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(packageBytes))) {
            assertEquals("DuesDesk-backup-" + LocalDate.now() + ".ddbk", zip.getNextEntry().getName());
            assertTrue(new BackupEncryptionService().isEncrypted(zip.readAllBytes()));
            assertEquals("Officer-handoff-summary.pdf", zip.getNextEntry().getName());
            assertTrue(zip.readAllBytes().length > 4);
            assertEquals("New-treasurer-instructions.txt", zip.getNextEntry().getName());
            assertTrue(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("Import saved data"));
        }
    }

    @Test
    void batchChargesAndCustomInstallmentsUpdateBalancesAndPaymentProgress() throws Exception {
        Store store = initializedStore();
        LocalDate dueDate = LocalDate.now().plusDays(10);
        assertEquals(1, store.addBatchCharges(List.of("member-1"), "Semester dues", 40, dueDate.toString()));
        Member member = store.findMemberById("member-1").orElseThrow();
        assertEquals(140, member.getTotalOwed());
        assertEquals(20, member.getPastDueBalance());
        assertEquals(dueDate.toString(), member.getCharges().getLast().getDueDate());

        assertTrue(store.addInstallments("member-1", List.of(
                new PaymentInstallment("installment-1", dueDate.toString(), "First installment", 50),
                new PaymentInstallment("installment-2", dueDate.plusDays(30).toString(), "Second installment", 50)
        )));
        assertTrue(store.recordPayment("member-1", new PaymentRecord(LocalDate.now().toString(),
                60, "Payment plan", "installment-payment")));
        assertTrue(member.getInstallments().getFirst().isPaid());
        assertEquals(40, member.getInstallments().get(1).getRemainingAmount(), 0.001);
        assertTrue(store.removeInstallment("member-1", "installment-2"));
        assertEquals(1, member.getInstallments().size());
        assertEquals(80, member.getTotalOwed());
    }

    @Test
    void datedChargesBecomePastDueAfterTheirDueDateAndOnlyOnce() throws Exception {
        Store store = initializedStore();
        Member member = store.findMemberById("member-1").orElseThrow();
        ChargeRecord charge = new ChargeRecord("dated-charge", LocalDate.now().minusDays(1).toString(),
                "Past-due dues", 30, 0);
        charge.setDueDate(LocalDate.now().minusDays(1).toString());
        member.getCharges().add(charge);
        member.setTotalOwed(member.getTotalOwed() + 30);
        store.save();

        assertEquals(50, store.getMembers().getFirst().getPastDueBalance());
        assertEquals(50, store.getMembers().getFirst().getPastDueBalance());
    }

    @Test
    void bankCsvParserSupportsDateAndAmountAndSeparateDebitCreditColumns() {
        List<FlowEntry> amountRows = BankCsvParser.parse(
                "Date,Description,Name,Amount\n10/21/2026,Dues,Alex Member,\"$1,200.00\"\n");
        assertEquals(1, amountRows.size());
        assertEquals(LocalDate.of(2026, 10, 21).toString(), amountRows.getFirst().getDate());
        assertEquals(1200, amountRows.getFirst().getAmount(), 0.001);

        List<FlowEntry> debitRows = BankCsvParser.parse(
                "Posted Date,Memo,Debit,Credit\n2026-10-21,Office supplies,12.50,\n");
        assertEquals(-12.5, debitRows.getFirst().getAmount(), 0.001);
    }

    @Test
    void memberDocumentsProducePdfAndCsvStatements() throws Exception {
        Member member = new Member("m1", "Test Member", "555-000-0000", 75, 10, 0,
                "Delinquent", "Never", false);
        member.setPaymentHistory(List.of(new PaymentRecord("2026-10-21", 25,
                "Installment", "receipt-key")));
        MemberDocumentService documents = new MemberDocumentService();

        assertTrue(new String(documents.statementPdf(member), 0, 4,
                java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF"));
        assertTrue(new String(documents.receiptPdf(member, member.getPaymentHistory().getFirst()), 0, 4,
                java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF"));
        assertTrue(new String(documents.statementCsv(member), java.nio.charset.StandardCharsets.UTF_8)
                .contains("Installment"));
    }

    private boolean submitAtSameTime(Store store, Member member, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        return store.recordPayment(member.getId(), new PaymentRecord(
                LocalDate.now().toString(), 40, "Installment", "same-request"));
    }

    @Test
    void rentChargesRaiseBalancesAndRemovalRestoresThemAcrossReload() throws Exception {
        Path file = tempDir.resolve("rent-charges.json");
        Store store = initializedStore(file);
        store.addResident(new Resident("r1", "Ana", "555-0001", "1A", 500, 100, 0));
        store.addResident(new Resident("r2", "Ben", "555-0002", "1B", 500, 0, 0));

        assertEquals(2, store.addResidentCharges(List.of("r1", "r2", "r1"), " Utilities ", 40.5, "2026-11-01"));
        Resident ana = store.findResidentById("r1").orElseThrow();
        assertEquals(140.5, ana.getBalanceDue());
        assertEquals(40.5, store.findResidentById("r2").orElseThrow().getBalanceDue());
        assertEquals(1, ana.getCharges().size());
        assertEquals("Utilities", ana.getCharges().getFirst().getDescription());
        assertEquals("2026-11-01", ana.getCharges().getFirst().getDueDate());

        Store reloaded = new Store();
        ReflectionTestUtils.setField(reloaded, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(reloaded, "smsDailyCap", 100);
        reloaded.init();
        Resident reloadedAna = reloaded.findResidentById("r1").orElseThrow();
        assertEquals(1, reloadedAna.getCharges().size());

        assertTrue(reloaded.removeResidentCharge("r1", reloadedAna.getCharges().getFirst().getId()));
        assertEquals(100, reloaded.findResidentById("r1").orElseThrow().getBalanceDue());
        assertTrue(reloaded.findResidentById("r1").orElseThrow().getCharges().isEmpty());
    }

    @Test
    void invalidRentChargesChangeNothing() throws Exception {
        Store store = initializedStore(tempDir.resolve("rent-charges-invalid.json"));
        store.addResident(new Resident("r1", "Ana", "555-0001", "1A", 500, 100, 0));

        assertEquals(0, store.addResidentCharges(List.of("r1", "missing"), "Fee", 10, ""));
        assertEquals(0, store.addResidentCharges(List.of("r1"), "Fee", 0, ""));
        assertEquals(0, store.addResidentCharges(List.of("r1"), " ", 10, ""));
        assertEquals(0, store.addResidentCharges(List.of("r1"), "Fee", 10, "not-a-date"));
        assertEquals(0, store.addResidentCharges(List.of(), "Fee", 10, ""));
        assertFalse(store.removeResidentCharge("r1", "nope"));
        assertEquals(100, store.findResidentById("r1").orElseThrow().getBalanceDue());
        assertTrue(store.findResidentById("r1").orElseThrow().getCharges().isEmpty());
    }

    @Test
    void manualFlowEntriesPersistAreDedupedAndStayOutOfReconciliation() throws Exception {
        Path file = tempDir.resolve("manual-flow.json");
        Store store = initializedStore(file);
        FlowEntry entry = new FlowEntry("manual-abc", "2026-10-14", "Pizza", "Tony's", -86.4, "Manual entry");
        entry.setCategory("Food & beverages");
        entry.setReconciliationStatus("MANUAL");

        assertTrue(store.addManualFlowEntry(entry));
        assertFalse(store.addManualFlowEntry(entry));

        Store reloaded = new Store();
        ReflectionTestUtils.setField(reloaded, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(reloaded, "smsDailyCap", 100);
        reloaded.init();
        FlowEntry saved = reloaded.getFlowEntries().getFirst();
        assertEquals(1, reloaded.getFlowEntries().size());
        assertEquals("Food & beverages", saved.getCategory());
        assertEquals("MANUAL", saved.getReconciliationStatus());
        assertFalse(reloaded.ignoreReconciliation("manual-abc"));
    }

    @Test
    void chairChargesAreAddedTogetherOrNotAtAll() throws Exception {
        Store store = initializedStore(tempDir.resolve("chair-charges.json"));
        ChairBudget budget = store.saveChairBudget("Rush", 500);

        assertEquals(2, store.addChairCharges(budget.getId(), List.of(
                new ReimbursementRecord("c1", "2026-10-01", "Shirts", 40, null, null),
                new ReimbursementRecord("c2", "2026-10-02", "Pizza", 25, null, null))));
        assertEquals(0, store.addChairCharges(budget.getId(), List.of(
                new ReimbursementRecord("c3", "2026-10-03", "Valid", 10, null, null),
                new ReimbursementRecord("c4", "bad-date", "Invalid", 10, null, null))));
        assertEquals(0, store.addChairCharges(budget.getId(), List.of(
                new ReimbursementRecord("c5", "2026-10-03", "Credit sneaks in", 10, null, "member-1"))));
        assertEquals(0, store.addChairCharges("missing", List.of(
                new ReimbursementRecord("c6", "2026-10-03", "Shirts", 10, null, null))));

        ChairBudget saved = store.getChairBudgets().getFirst();
        assertEquals(2, saved.getReimbursements().size());
        assertEquals(65, saved.getSpent());
    }

    private Store initializedStore() throws Exception {
        return initializedStore(tempDir.resolve("concurrent-store.json"));
    }

    private Store initializedStore(Path file) throws Exception {
        Store.DataContainer data = new Store.DataContainer();
        data.members.add(new Member("member-1", "Test Member", "555-000-0000",
                100, 20, 0, "Delinquent", "Never", false));
        new ObjectMapper().writeValue(file.toFile(), data);

        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }
}
