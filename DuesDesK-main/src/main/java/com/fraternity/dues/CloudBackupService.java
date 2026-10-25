package com.fraternity.dues;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Encrypted automatic snapshots to a local cloud-sync folder. Besides the 2:00 AM run, a snapshot is caught up when
 * DuesDesk starts (in the background) and when it shuts down, whenever none was written in the last 20 hours, so a
 * laptop that sleeps overnight still gets a daily backup.
 */
@Service
public class CloudBackupService {
    private static final Logger log = LoggerFactory.getLogger(CloudBackupService.class);
    private static final Pattern BACKUP_NAME = Pattern.compile("DuesDesk-auto-backup-\\d{4}-\\d{2}-\\d{2}\\.ddbk");
    static final Duration CATCH_UP_AFTER = Duration.ofHours(20);
    private final Store store;
    private final BackupEncryptionService encryption;
    private final SystemKeyStoreService credentials;

    public CloudBackupService(Store store, BackupEncryptionService encryption, SystemKeyStoreService credentials) {
        this.store = store;
        this.encryption = encryption;
        this.credentials = credentials;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public synchronized void scheduledBackup() {
        if (!store.getSettings().isCloudBackupEnabled()) return;
        runAndRecordFailure("Scheduled");
    }

    /** At launch: catch up in the background so the window isn't held up. */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!isCatchUpDue(Instant.now())) return;
        Thread worker = new Thread(() -> {
            synchronized (this) {
                if (isCatchUpDue(Instant.now())) runAndRecordFailure("Startup catch-up");
            }
        }, "duesdesk-backup-catch-up");
        worker.setDaemon(true);
        worker.start();
    }

    /** On a normal quit (window closed, Cmd+Q, Alt+F4, sign-out): write today's snapshot if it was missed. */
    @PreDestroy
    public synchronized void catchUpOnShutdown() {
        if (isCatchUpDue(Instant.now())) runAndRecordFailure("Shutdown catch-up");
    }

    /** True when automatic snapshots are on, a folder is set, and no snapshot there is newer than 20 hours. */
    public boolean isCatchUpDue(Instant now) {
        AppSettings settings = store.getSettings();
        if (!settings.isCloudBackupEnabled() || settings.getCloudBackupFolder().isBlank()) return false;
        Optional<Instant> latest = latestSnapshotTime(Path.of(settings.getCloudBackupFolder().trim()));
        return latest.isEmpty() || latest.get().isBefore(now.minus(CATCH_UP_AFTER));
    }

    /** When the newest DuesDesk snapshot in the folder was written, if there is one. */
    public Optional<Instant> latestSnapshotTime(Path folder) {
        if (!Files.isDirectory(folder)) return Optional.empty();
        try (var paths = Files.list(folder)) {
            return paths.filter(Files::isRegularFile)
                    .filter(item -> BACKUP_NAME.matcher(item.getFileName().toString()).matches())
                    .map(item -> {
                        try {
                            return Files.getLastModifiedTime(item).toInstant();
                        } catch (java.io.IOException e) {
                            return Instant.EPOCH;
                        }
                    })
                    .max(Instant::compareTo);
        } catch (java.io.IOException e) {
            log.warn("Unable to read the backup folder {}", folder, e);
            return Optional.empty();
        }
    }

    private void runAndRecordFailure(String reason) {
        try {
            createBackup();
            log.info("{} encrypted backup written", reason);
        } catch (Exception e) {
            log.error("{} encrypted backup failed", reason, e);
            try {
                store.getSettings().setCloudBackupStatus("Last backup failed: " + safeMessage(e));
                store.save();
            } catch (RuntimeException saveError) {
                log.warn("Unable to record the backup failure", saveError);
            }
        }
    }

    public synchronized Path createBackup() throws Exception {
        AppSettings settings = store.getSettings();
        if (!settings.isCloudBackupEnabled()) {
            throw new IllegalStateException("Enable automatic backups in Settings before creating a snapshot.");
        }
        String configuredFolder = settings.getCloudBackupFolder().trim();
        if (configuredFolder.isBlank()) throw new IllegalStateException("Choose a local cloud-sync folder first.");
        Path folder = Path.of(configuredFolder).toAbsolutePath().normalize();
        Files.createDirectories(folder);
        if (!Files.isDirectory(folder) || !Files.isWritable(folder)) {
            throw new IllegalStateException("The configured cloud-sync folder is not writable.");
        }
        String fileName = "DuesDesk-auto-backup-" + LocalDate.now() + ".ddbk";
        Path target = folder.resolve(fileName);
        Path temporary = Files.createTempFile(folder, ".duesdesk-", ".tmp");
        char[] password = null;
        try {
            password = credentials.getOrCreateBackupPassword();
            byte[] encrypted = encryption.encrypt(store.exportData(), password);
            Files.write(temporary, encrypted);
            try {
                Files.setPosixFilePermissions(temporary,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                log.debug("Owner-only file permissions are not supported for {}", temporary);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (password != null) java.util.Arrays.fill(password, '\0');
            Files.deleteIfExists(temporary);
        }
        pruneOldSnapshots(folder, settings.getCloudBackupRetentionDays());
        settings.setCloudBackupStatus("Last successful snapshot: " + LocalDate.now());
        store.save();
        return target;
    }

    public synchronized char[] getRecoveryPassword() throws Exception {
        return credentials.getOrCreateBackupPassword();
    }

    /** "macOS Keychain", "Windows Credential Protection (DPAPI)", ... */
    public String credentialStoreName() {
        return credentials.storeName();
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void pruneOldSnapshots(Path folder, int retentionDays) throws Exception {
        int retention = Math.max(1, Math.min(365, retentionDays));
        LocalDate cutoff = LocalDate.now().minusDays(retention);
        try (var paths = Files.list(folder)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(item -> BACKUP_NAME.matcher(item.getFileName().toString()).matches()).toList()) {
                String datePart = path.getFileName().toString().substring("DuesDesk-auto-backup-".length(), 10
                        + "DuesDesk-auto-backup-".length());
                try {
                    if (LocalDate.parse(datePart).isBefore(cutoff)) Files.deleteIfExists(path);
                } catch (java.time.format.DateTimeParseException e) {
                    log.warn("Leaving auto-backup with malformed date in filename: {}", path.getFileName());
                }
            }
        }
    }
}
