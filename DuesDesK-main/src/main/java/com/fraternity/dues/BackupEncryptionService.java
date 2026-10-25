package com.fraternity.dues;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

@Component
public class BackupEncryptionService {
    private static final byte[] MAGIC = "DUESDESK".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;
    private static final int ITERATIONS = 310_000;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int KEY_BITS = 256;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int HEADER_LENGTH = MAGIC.length + 1 + Integer.BYTES + SALT_LENGTH + IV_LENGTH;
    private final SecureRandom random = new SecureRandom();

    public byte[] encrypt(byte[] plaintext, char[] password) {
        try {
            validatePassword(password);
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(salt);
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt),
                    new GCMParameterSpec(128, iv));
            cipher.updateAAD(header(salt, iv));
            byte[] encrypted = cipher.doFinal(plaintext);
            ByteArrayOutputStream output = new ByteArrayOutputStream(HEADER_LENGTH + encrypted.length);
            output.write(header(salt, iv));
            output.write(encrypted);
            return output.toByteArray();
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("Backup encryption failed.", e);
        } finally {
            clearPassword(password);
        }
    }

    public byte[] decrypt(byte[] encryptedBackup, char[] password) {
        try {
            validatePassword(password);
            if (!isEncrypted(encryptedBackup) || encryptedBackup.length <= HEADER_LENGTH + 16) {
                throw new IllegalArgumentException("That file is not a valid encrypted DuesDesk backup.");
            }
            ByteBuffer buffer = ByteBuffer.wrap(encryptedBackup);
            buffer.position(MAGIC.length);
            byte version = buffer.get();
            if (version != VERSION) throw new IllegalArgumentException("This DuesDesk backup version is not supported.");
            int iterations = buffer.getInt();
            if (iterations < 100_000 || iterations > 2_000_000) {
                throw new IllegalArgumentException("The encrypted backup has invalid encryption parameters.");
            }
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(salt);
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations),
                    new GCMParameterSpec(128, iv));
            cipher.updateAAD(Arrays.copyOf(encryptedBackup, HEADER_LENGTH));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("The password is incorrect or the backup file was changed.", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The encrypted backup could not be decrypted.", e);
        } finally {
            clearPassword(password);
        }
    }

    public boolean isEncrypted(byte[] data) {
        if (data == null || data.length < MAGIC.length + 1) return false;
        return Arrays.equals(MAGIC, Arrays.copyOf(data, MAGIC.length));
    }

    private byte[] header(byte[] salt, byte[] iv) {
        return ByteBuffer.allocate(HEADER_LENGTH)
                .put(MAGIC)
                .put(VERSION)
                .putInt(ITERATIONS)
                .put(salt)
                .put(iv)
                .array();
    }

    private SecretKeySpec deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        return deriveKey(password, salt, ITERATIONS);
    }

    private SecretKeySpec deriveKey(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification).getEncoded();
            try {
                return new SecretKeySpec(key, "AES");
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } finally {
            specification.clearPassword();
        }
    }

    private void validatePassword(char[] password) {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Use a backup password with at least 8 characters.");
        }
    }

    private void clearPassword(char[] password) {
        if (password != null) Arrays.fill(password, '\0');
    }
}
