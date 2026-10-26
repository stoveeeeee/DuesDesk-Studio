package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class VenmoEmailSyncServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T15:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void verifiedPaymentWithADuesCodeIsAppliedAndNeverRecordedTwiceEvenAfterARestart() throws Exception {
        Path file = tempDir.resolve("store.json");
        Store store = store(file);
        Member alex = store.getMembers().stream().filter(m -> m.getName().equals("Alex Rivera")).findFirst().orElseThrow();
        assertEquals("DUES-ARIVERA", alex.getDuesCode());
        List<LocalDate> asked = new ArrayList<>();
        VenmoEmailSyncService service = service(store, asked, "paid-you.eml", "you-paid.eml");

        VenmoEmailSyncService.SyncResult first = service.sync();

        assertEquals(1, first.added());
        assertEquals(1, first.applied());
        assertEquals(LocalDate.parse("2026-10-20"), asked.getFirst());
        Member paid = store.findMemberById(alex.getId()).orElseThrow();
        assertEquals(50.0, paid.getPaymentHistory().getFirst().getAmount());
        assertEquals(250.0, paid.getTotalOwed());
        assertTrue(service.status().lastSyncOk());
        assertTrue(service.status().lastPaymentSummary().contains("Alex Rivera"));

        // A fresh start reads the same emails again; nothing is added.
        Store reloaded = store(file);
        VenmoEmailSyncService again = service(reloaded, new ArrayList<>(), "paid-you.eml", "you-paid.eml");
        VenmoEmailSyncService.SyncResult second = again.sync();
        assertEquals(0, second.added());
        assertEquals(1, reloaded.findMemberById(alex.getId()).orElseThrow().getPaymentHistory().size());
    }

    @Test
    void unverifiedAndUnmatchedPaymentsWaitForTheTreasurer() throws Exception {
        Store store = store(tempDir.resolve("store.json"));
        VenmoEmailSyncService service = service(store, new ArrayList<>(), "paid-you-spoofed.eml", "paid-you-hand-forwarded.eml");

        VenmoEmailSyncService.SyncResult result = service.sync();

        assertEquals(2, result.added());
        assertEquals(0, result.applied());
        PaymentStore payments = new PaymentStore(store);
        assertEquals(2, payments.unmatched().size());
        assertTrue(payments.unmatched().stream().allMatch(entry -> entry.getId().startsWith("venmo-unverified-")));
        assertTrue(payments.unmatched().stream().allMatch(entry -> entry.getDescription().startsWith("Unverified email")));
        Member alex = store.getMembers().stream().filter(m -> m.getName().equals("Alex Rivera")).findFirst().orElseThrow();
        assertTrue(alex.getPaymentHistory().isEmpty());
    }

    @Test
    void aPaymentAlreadyImportedFromAVenmoCsvIsNotAddedAgain() throws Exception {
        Store store = store(tempDir.resolve("store.json"));
        store.importVenmoCsv(new MockMultipartFile("file", "venmo.csv", "text/csv", (
                "Account Statement\n"
                        + ",ID,Datetime,Type,Status,Note,From,To,Amount (total)\n"
                        + ",4700000000000000001,2026-10-25T23:08:48,Payment,Complete,Fall dues,Alex Rivera,Chapter,+ $50.00\n")
                .getBytes(StandardCharsets.UTF_8)));
        VenmoEmailSyncService service = service(store, new ArrayList<>(), "paid-you.eml");

        VenmoEmailSyncService.SyncResult result = service.sync();

        assertEquals(0, result.added());
        assertEquals(1, result.duplicates());
        assertEquals(1, store.getFlowEntries().stream().filter(e -> e.getId().equals("venmo-4700000000000000001")).count());
    }

    @Test
    void mailboxFailuresAreReportedAndChangeNothing() throws Exception {
        Path file = tempDir.resolve("store.json");
        Store store = store(file);
        String before = Files.readString(file);
        VenmoEmailSyncService service = new VenmoEmailSyncService(new PaymentStore(store), store, credentials(store),
                (host, address, password) -> new EmailSource() {
                    @Override public List<RawEmail> fetchSince(LocalDate since) throws EmailSourceException {
                        throw new EmailSourceException("Couldn't reach imap.gmail.com.", new java.net.ConnectException());
                    }
                    @Override public String testConnection() { return "ok"; }
                }, new VenmoEmailParser(ZoneOffset.UTC), CLOCK);

        assertNull(service.sync());

        assertFalse(service.status().lastSyncOk());
        assertEquals("Couldn't reach imap.gmail.com.", service.status().lastSyncMessage());
        assertEquals(before, Files.readString(file));
    }

    @Test
    void quietWarningAppearsAfterFourteenDaysWithoutVenmoEmail() throws Exception {
        Store store = store(tempDir.resolve("store.json"));
        store.getSettings().setVenmoEmailImportFrom("2026-10-01");
        VenmoEmailSyncService service = service(store, new ArrayList<>());

        assertTrue(service.status().quiet());
        store.getSettings().setVenmoEmailImportFrom("2026-10-20");
        assertFalse(service.status().quiet());
    }

    @Test
    void appPasswordIsEncryptedAndOnlyOpensWithTheSameSystemKey() throws Exception {
        Path secret = tempDir.resolve("mail.secret");
        MailCredentialStore credentials = new MailCredentialStore(() -> "system-secret".toCharArray(), () -> secret, "Test store");
        credentials.savePassword("abcdefghijklmnop".toCharArray());

        assertFalse(Files.readString(secret).contains("abcdefghijklmnop"));
        assertArrayEquals("abcdefghijklmnop".toCharArray(), credentials.loadPassword());
        MailCredentialStore otherComputer = new MailCredentialStore(() -> "other-secret".toCharArray(), () -> secret, "Test store");
        assertThrows(IllegalStateException.class, otherComputer::loadPassword);
        credentials.clear();
        assertNull(credentials.loadPassword());
    }

    @Test
    void appPasswordNeverEntersTheDataFile() throws Exception {
        Path file = tempDir.resolve("store.json");
        Store store = store(file);
        credentials(store);
        store.save();

        assertFalse(Files.readString(file).contains("abcdefghijklmnop"));
        assertTrue(Files.isRegularFile(store.companionFile(MailCredentialStore.FILE_SUFFIX)));
    }

    private VenmoEmailSyncService service(Store store, List<LocalDate> asked, String... fixtures) throws Exception {
        List<EmailSource.RawEmail> emails = new ArrayList<>();
        for (String fixture : fixtures) emails.add(new EmailSource.RawEmail(null, VenmoEmailParserTest.fixture(fixture)));
        return new VenmoEmailSyncService(new PaymentStore(store), store, credentials(store),
                (host, address, password) -> new EmailSource() {
                    @Override public List<RawEmail> fetchSince(LocalDate since) {
                        asked.add(since);
                        return emails;
                    }
                    @Override public String testConnection() { return "ok"; }
                }, new VenmoEmailParser(ZoneOffset.UTC), CLOCK);
    }

    private MailCredentialStore credentials(Store store) throws Exception {
        MailCredentialStore credentials = new MailCredentialStore(() -> "system-secret".toCharArray(),
                () -> store.companionFile(MailCredentialStore.FILE_SUFFIX), "Test store");
        credentials.savePassword("abcdefghijklmnop".toCharArray());
        return credentials;
    }

    private static Store store(Path file) throws Exception {
        if (!Files.exists(file)) {
            Files.writeString(file, """
                    {"members":[
                      {"id":"m1","name":"Alex Rivera","phoneNumber":"555-0101","totalOwed":300,"pastDueBalance":0,"paidThisMonth":0},
                      {"id":"m2","name":"Jordan Lee","phoneNumber":"555-0102","totalOwed":300,"pastDueBalance":0,"paidThisMonth":0}],
                     "settings":{"ihqBillAmount":0,"venmoEmailEnabled":true,"venmoEmailAddress":"chapter.dues@example.com",
                                 "venmoEmailImportFrom":"2026-10-20"}}""");
        }
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", file.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        return store;
    }
}
