package com.fraternity.dues;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Keeps the chapter mailbox's app password encrypted (AES-256-GCM) in its own file beside the data file, never in the
 * data file, exports or backups. The encryption key is derived from the secret that {@link SystemKeyStoreService}
 * keeps in the operating system's credential store (macOS Keychain, Windows DPAPI), so the file is useless on
 * another computer or to another user account.
 */
@Component
public class MailCredentialStore {

    /** The OS-protected root secret; the caller wipes the returned array. */
    @FunctionalInterface
    interface RootSecret {
        char[] get() throws Exception;
    }

    static final String FILE_SUFFIX = ".venmo-mail.secret";
    private static final byte[] KEY_CONTEXT = "DuesDesk venmo mail password v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD = "DuesDesk-venmo-mail".getBytes(StandardCharsets.UTF_8);

    private final RootSecret rootSecret;
    private final Supplier<Path> file;
    private final String storeName;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private byte[] cachedKey;

    @Autowired
    public MailCredentialStore(SystemKeyStoreService keys, Store store) {
        this(keys::getOrCreateBackupPassword, () -> store.companionFile(FILE_SUFFIX), keys.storeName());
    }

    MailCredentialStore(RootSecret rootSecret, Supplier<Path> file, String storeName) {
        this.rootSecret = rootSecret;
        this.file = file;
        this.storeName = storeName;
    }

    /** "macOS Keychain", "Windows Credential Protection (DPAPI)", ... for the Settings page. */
    public String storeName() {
        return storeName;
    }

    public synchronized boolean hasPassword() {
        return Files.isRegularFile(file.get());
    }

    public synchronized void savePassword(char[] password) throws Exception {
        byte[] plain = toBytes(password);
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            byte[] sealed = cipher.doFinal(plain);
            String json = mapper.writeValueAsString(Map.of("v", 1,
                    "iv", Base64.getEncoder().encodeToString(iv),
                    "data", Base64.getEncoder().encodeToString(sealed)));
            writeOwnerOnly(file.get(), json);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** The saved app password, or null when none is saved. The caller should wipe the returned array. */
    public synchronized char[] loadPassword() throws Exception {
        Path target = file.get();
        if (!Files.isRegularFile(target)) return null;
        JsonNode saved = mapper.readTree(Files.readString(target, StandardCharsets.UTF_8));
        byte[] iv = Base64.getDecoder().decode(saved.path("iv").asText(""));
        byte[] sealed = Base64.getDecoder().decode(saved.path("data").asText(""));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(AAD);
        byte[] plain;
        try {
            plain = cipher.doFinal(sealed);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IllegalStateException("The saved app password can't be unlocked on this computer account. Enter it again in Settings.");
        }
        try {
            CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
            char[] password = Arrays.copyOf(chars.array(), chars.limit());
            Arrays.fill(chars.array(), '\0');
            return password;
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public synchronized void clear() throws Exception {
        Files.deleteIfExists(file.get());
    }

    private byte[] key() throws Exception {
        if (cachedKey != null) return cachedKey;
        char[] secret = rootSecret.get();
        byte[] secretBytes = toBytes(secret);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY_CONTEXT);
            digest.update((byte) 0);
            digest.update(secretBytes);
            cachedKey = digest.digest();
            return cachedKey;
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
            if (secret != null) Arrays.fill(secret, '\0');
        }
    }

    private static byte[] toBytes(char[] chars) {
        if (chars == null || chars.length == 0) throw new IllegalArgumentException("The password is empty.");
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] bytes = Arrays.copyOf(encoded.array(), encoded.limit());
        Arrays.fill(encoded.array(), (byte) 0);
        return bytes;
    }

    private static void writeOwnerOnly(Path target, String content) throws Exception {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".duesdesk-mail-", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: the file is in the user's own data folder and is useless without this user's DPAPI key.
            }
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
