package com.fraternity.dues;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the automatic-backup encryption password in the operating system's credential protection:
 * <ul>
 *   <li><b>macOS</b> - the macOS Keychain, exactly as before (so existing snapshots stay restorable);</li>
 *   <li><b>Windows</b> - Windows Data Protection (DPAPI) for the current Windows user, via PowerShell's
 *       ConvertFrom-SecureString; only the protected blob is written, to %LOCALAPPDATA%\DuesDesk;</li>
 *   <li><b>other systems</b> - an owner-only key file in the DuesDesk data folder.</li>
 * </ul>
 */
@Component
public class SystemKeyStoreService {

    /** Runs an external command, feeding {@code stdin}, and returns its standard output. */
    @FunctionalInterface
    interface CommandRunner {
        String run(List<String> command, String stdin) throws Exception;
    }

    static final String DPAPI_FILE = "backup-key.dpapi";
    static final String KEY_FILE = "backup-key.secret";

    // The secret travels on stdin, never on the command line, so it can't be read from the process list.
    private static final String PROTECT_SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "$plain = [Console]::In.ReadLine()",
            "$secure = ConvertTo-SecureString -String $plain -AsPlainText -Force",
            "[Console]::Out.Write((ConvertFrom-SecureString -SecureString $secure))");
    private static final String UNPROTECT_SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "$blob = [Console]::In.ReadLine()",
            "$secure = ConvertTo-SecureString -String $blob",
            "$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)",
            "try { [Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)) }",
            "finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }");

    private final OsCredentialStore macKeychain;
    private final AppPaths.Os os;
    private final Path keyFolder;
    private final CommandRunner runner;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SystemKeyStoreService(OsCredentialStore macKeychain) {
        this(macKeychain, AppPaths.currentOs(), AppPaths.appDataDir(), SystemKeyStoreService::runProcess);
    }

    SystemKeyStoreService(OsCredentialStore macKeychain, AppPaths.Os os, Path keyFolder, CommandRunner runner) {
        this.macKeychain = macKeychain;
        this.os = os;
        this.keyFolder = keyFolder;
        this.runner = runner;
    }

    /** "macOS Keychain", "Windows Credential Protection (DPAPI)", ... for the Settings page. */
    public String storeName() {
        return AppPaths.credentialStoreName(os);
    }

    /** The automatic-backup password, created on first use. The caller should wipe the returned array. */
    public synchronized char[] getOrCreateBackupPassword() throws Exception {
        return switch (os) {
            case MAC -> macKeychain.getOrCreateBackupPassword();
            case WINDOWS -> windowsPassword();
            case OTHER -> keyFilePassword();
        };
    }

    private char[] windowsPassword() throws Exception {
        Path blobFile = keyFolder.resolve(DPAPI_FILE);
        if (Files.isRegularFile(blobFile)) {
            String blob = Files.readString(blobFile, StandardCharsets.US_ASCII).trim();
            String secret = runPowerShell(UNPROTECT_SCRIPT, blob).trim();
            if (secret.isEmpty()) {
                throw new IllegalStateException("Windows could not unlock the saved backup key for this user account.");
            }
            return secret.toCharArray();
        }
        String secret = newSecret();
        String blob = runPowerShell(PROTECT_SCRIPT, secret).trim();
        if (blob.isEmpty() || !blob.matches("[0-9a-fA-F]+")) {
            throw new IllegalStateException("Windows Data Protection did not return a protected backup key.");
        }
        Files.createDirectories(keyFolder);
        writeOwnerOnly(blobFile, blob);
        return secret.toCharArray();
    }

    private char[] keyFilePassword() throws Exception {
        Path keyFile = keyFolder.resolve(KEY_FILE);
        if (Files.isRegularFile(keyFile)) {
            String secret = Files.readString(keyFile, StandardCharsets.US_ASCII).trim();
            if (secret.isEmpty()) throw new IllegalStateException("The saved backup key file is empty.");
            return secret.toCharArray();
        }
        String secret = newSecret();
        Files.createDirectories(keyFolder);
        writeOwnerOnly(keyFile, secret);
        return secret.toCharArray();
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        try {
            return Base64.getEncoder().encodeToString(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void writeOwnerOnly(Path file, String content) throws Exception {
        Path temporary = Files.createTempFile(file.getParent(), ".duesdesk-key-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: the file sits in the user's own %LOCALAPPDATA%, and the blob is only readable by DPAPI for this user.
            }
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String runPowerShell(String script, String stdin) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return runner.run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded), stdin);
    }

    static String runProcess(List<String> command, String stdin) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write((stdin + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread errReader = new Thread(() -> copy(process.getErrorStream(), err), "duesdesk-keystore-stderr");
        errReader.setDaemon(true);
        errReader.start();
        copy(process.getInputStream(), out);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("The system credential store did not respond.");
        }
        errReader.join(2000);
        if (process.exitValue() != 0) {
            String message = err.toString(StandardCharsets.UTF_8).trim();
            throw new IllegalStateException("The system credential store failed" + (message.isEmpty() ? "." : ": " + firstLine(message)));
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void copy(InputStream from, ByteArrayOutputStream to) {
        try (from) {
            from.transferTo(to);
        } catch (java.io.IOException ignored) {
            // The process ended; whatever was read is kept.
        }
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return (newline < 0 ? text : text.substring(0, newline)).trim();
    }
}
