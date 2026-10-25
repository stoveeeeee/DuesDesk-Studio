package com.fraternity.dues;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** macOS / Windows parity: app-data paths, cloud-folder detection, the credential store and catch-up snapshots. */
class CrossPlatformTest {
    @TempDir
    Path tempDir;

    @Test
    void appDataLivesInEachOperatingSystemsUsualPlace() {
        Path home = Path.of("home", "sam");
        assertEquals(home.resolve("Library").resolve("Application Support").resolve("DuesDesk"),
                AppPaths.appDataDir(AppPaths.Os.MAC, home, key -> null));
        assertEquals(Path.of("C:", "Users", "sam", "AppData", "Local").resolve("DuesDesk"),
                AppPaths.appDataDir(AppPaths.Os.WINDOWS, home, Map.of("LOCALAPPDATA", Path.of("C:", "Users", "sam", "AppData", "Local").toString())::get));
        assertEquals(home.resolve("AppData").resolve("Local").resolve("DuesDesk"),
                AppPaths.appDataDir(AppPaths.Os.WINDOWS, home, key -> null), "falls back when %LOCALAPPDATA% is missing");
        assertEquals(home.resolve(".local").resolve("share").resolve("DuesDesk"), AppPaths.appDataDir(AppPaths.Os.OTHER, home, key -> null));

        assertEquals(AppPaths.Os.MAC, AppPaths.osFromName("Mac OS X"));
        assertEquals(AppPaths.Os.WINDOWS, AppPaths.osFromName("Windows 11"));
        assertEquals(AppPaths.Os.OTHER, AppPaths.osFromName("Linux"));
        assertEquals("Windows Credential Protection (DPAPI)", AppPaths.credentialStoreName(AppPaths.Os.WINDOWS));
        assertEquals("macOS Keychain", AppPaths.credentialStoreName(AppPaths.Os.MAC));
    }

    @Test
    void anExistingInstallKeepsItsLegacyDataFolder() throws Exception {
        Path home = tempDir.resolve("home");
        Path appData = tempDir.resolve("appdata").resolve("DuesDesk");
        assertEquals(appData.resolve("store_data.json"), AppPaths.defaultDataFile(home, appData), "new installs use the per-OS folder");

        Path legacy = home.resolve(".fraternity-dues").resolve("store_data.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{}");
        assertEquals(legacy, AppPaths.defaultDataFile(home, appData), "an existing ~/.fraternity-dues save keeps being used");
    }

    @Test
    void installedCloudSyncFoldersAreOfferedOnMacAndWindows() throws Exception {
        Path macHome = tempDir.resolve("mac");
        Files.createDirectories(macHome.resolve("Library/Mobile Documents/com~apple~CloudDocs"));
        Files.createDirectories(macHome.resolve("Library/CloudStorage/GoogleDrive-sam@example.com/My Drive"));
        Files.createDirectories(macHome.resolve("Library/CloudStorage/OneDrive-Personal"));
        Files.createDirectories(macHome.resolve("Dropbox"));
        List<AppPaths.CloudFolder> mac = AppPaths.detectCloudFolders(AppPaths.Os.MAC, macHome, key -> null, List.of());
        assertEquals(List.of("iCloud Drive", "Google Drive", "OneDrive", "Dropbox"), mac.stream().map(AppPaths.CloudFolder::provider).toList());
        assertTrue(mac.get(1).path().endsWith(Path.of("My Drive", "DuesDesk Backups").toString()));

        Path winHome = tempDir.resolve("win");
        Path oneDrive = tempDir.resolve("OneDriveRoot");
        Path driveG = tempDir.resolve("G");
        Files.createDirectories(winHome.resolve("iCloudDrive"));
        Files.createDirectories(oneDrive);
        Files.createDirectories(driveG.resolve("My Drive"));
        List<AppPaths.CloudFolder> windows = AppPaths.detectCloudFolders(AppPaths.Os.WINDOWS, winHome,
                Map.of("OneDrive", oneDrive.toString())::get, List.of(driveG));
        assertEquals(List.of("iCloud Drive", "OneDrive", "Google Drive"), windows.stream().map(AppPaths.CloudFolder::provider).toList());
        assertEquals(oneDrive.resolve("DuesDesk Backups").toString(), windows.get(1).path());
    }

    @Test
    void windowsKeepsOnlyTheDpapiProtectedKeyAndReturnsTheSameSecretEveryTime() throws Exception {
        List<String> scripts = new ArrayList<>();
        // A stand-in for PowerShell: "protects" by hex-encoding, "unprotects" by decoding.
        SystemKeyStoreService.CommandRunner fake = (command, stdin) -> {
            String script = new String(java.util.Base64.getDecoder().decode(command.getLast()), java.nio.charset.StandardCharsets.UTF_16LE);
            scripts.add(script);
            if (script.contains("ConvertFrom-SecureString")) return java.util.HexFormat.of().formatHex(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(java.util.HexFormat.of().parseHex(stdin), java.nio.charset.StandardCharsets.UTF_8);
        };
        Path folder = tempDir.resolve("LocalAppData").resolve("DuesDesk");
        SystemKeyStoreService service = new SystemKeyStoreService(null, AppPaths.Os.WINDOWS, folder, fake);

        char[] first = service.getOrCreateBackupPassword();
        char[] second = service.getOrCreateBackupPassword();
        assertArrayEquals(first, second);
        assertEquals(44, first.length, "32 random bytes, Base64-encoded");
        String blob = Files.readString(folder.resolve(SystemKeyStoreService.DPAPI_FILE));
        assertFalse(blob.contains(new String(first)), "the secret itself is never written to disk");
        assertTrue(scripts.get(0).contains("ConvertFrom-SecureString"));
        assertTrue(scripts.get(1).contains("SecureStringToBSTR"));
        assertEquals("Windows Credential Protection (DPAPI)", service.storeName());
    }

    @Test
    void realWindowsDataProtectionRoundTripsOnAWindowsMachine() throws Exception {
        if (AppPaths.currentOs() != AppPaths.Os.WINDOWS) return; // DPAPI only exists on Windows
        Path folder = tempDir.resolve("dpapi");
        SystemKeyStoreService service = new SystemKeyStoreService(null, AppPaths.Os.WINDOWS, folder, SystemKeyStoreService::runProcess);
        char[] created = service.getOrCreateBackupPassword();
        char[] reread = new SystemKeyStoreService(null, AppPaths.Os.WINDOWS, folder, SystemKeyStoreService::runProcess).getOrCreateBackupPassword();
        assertArrayEquals(created, reread);
        assertTrue(Files.readString(folder.resolve(SystemKeyStoreService.DPAPI_FILE)).matches("[0-9a-fA-F]+"));
    }

    @Test
    void otherSystemsUseAnOwnerOnlyKeyFileAndMacKeepsTheKeychain() throws Exception {
        Path folder = tempDir.resolve("linux");
        SystemKeyStoreService linux = new SystemKeyStoreService(null, AppPaths.Os.OTHER, folder, (c, s) -> { throw new AssertionError("no commands"); });
        assertArrayEquals(linux.getOrCreateBackupPassword(), linux.getOrCreateBackupPassword());
        assertTrue(Files.isRegularFile(folder.resolve(SystemKeyStoreService.KEY_FILE)));

        OsCredentialStore keychain = org.mockito.Mockito.mock(OsCredentialStore.class);
        org.mockito.Mockito.when(keychain.getOrCreateBackupPassword()).thenReturn("from-keychain".toCharArray());
        SystemKeyStoreService mac = new SystemKeyStoreService(keychain, AppPaths.Os.MAC, folder, (c, s) -> { throw new AssertionError("no commands"); });
        assertArrayEquals("from-keychain".toCharArray(), mac.getOrCreateBackupPassword(), "existing Mac snapshots stay restorable");
    }

    @Test
    void aMissedDailySnapshotIsCaughtUpAtLaunchOrShutdown() throws Exception {
        Path dataFile = tempDir.resolve("store.json");
        Files.writeString(dataFile, "{\"members\":[],\"settings\":{\"ihqBillAmount\":0}}");
        Store store = new Store();
        ReflectionTestUtils.setField(store, "DATA_FILE", dataFile.toString());
        ReflectionTestUtils.setField(store, "smsDailyCap", 100);
        store.init();
        SystemKeyStoreService credentials = org.mockito.Mockito.mock(SystemKeyStoreService.class);
        org.mockito.Mockito.when(credentials.getOrCreateBackupPassword()).thenAnswer(call -> "catch-up key".toCharArray());
        CloudBackupService backups = new CloudBackupService(store, new BackupEncryptionService(), credentials);
        Path folder = tempDir.resolve("sync");
        Instant now = Instant.now();

        assertFalse(backups.isCatchUpDue(now), "automatic snapshots are off");
        store.getSettings().setCloudBackupEnabled(true);
        store.getSettings().setCloudBackupFolder(folder.toString());
        assertTrue(backups.isCatchUpDue(now), "no snapshot yet");

        backups.catchUpOnShutdown();
        Path written = folder.resolve("DuesDesk-auto-backup-" + java.time.LocalDate.now() + ".ddbk");
        assertTrue(Files.isRegularFile(written));
        assertFalse(backups.isCatchUpDue(Instant.now()), "a fresh snapshot exists");

        Files.setLastModifiedTime(written, FileTime.from(now.minus(Duration.ofHours(21))));
        assertTrue(backups.isCatchUpDue(Instant.now()), "the newest snapshot is older than 20 hours");
        Files.setLastModifiedTime(written, FileTime.from(now.minus(Duration.ofHours(19))));
        assertFalse(backups.isCatchUpDue(Instant.now()));
    }
}
