package com.fraternity.dues;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupEncryptionServiceTest {
    private final BackupEncryptionService encryption = new BackupEncryptionService();

    @Test
    void encryptedBackupCanBeDecryptedAndDoesNotContainPlaintext() {
        byte[] plaintext = "{\"members\":[{\"name\":\"Private Member\"}]}".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = encryption.encrypt(plaintext, "correct horse battery".toCharArray());

        assertTrue(encryption.isEncrypted(encrypted));
        assertNotEquals(new String(plaintext, StandardCharsets.UTF_8),
                new String(encrypted, StandardCharsets.ISO_8859_1));
        assertArrayEquals(plaintext, encryption.decrypt(encrypted, "correct horse battery".toCharArray()));
    }

    @Test
    void encryptedBackupsUseDifferentRandomSaltsAndIv() {
        byte[] plaintext = "same backup".getBytes(StandardCharsets.UTF_8);
        byte[] first = encryption.encrypt(plaintext, "correct horse battery".toCharArray());
        byte[] second = encryption.encrypt(plaintext, "correct horse battery".toCharArray());

        assertTrue(!Arrays.equals(first, second));
    }

    @Test
    void wrongPasswordAndTamperedBackupsAreRejected() {
        byte[] encrypted = encryption.encrypt("backup".getBytes(StandardCharsets.UTF_8),
                "correct horse battery".toCharArray());
        byte[] tampered = encrypted.clone();
        tampered[tampered.length - 1] ^= 1;

        assertThrows(IllegalArgumentException.class,
                () -> encryption.decrypt(encrypted, "incorrect password".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> encryption.decrypt(tampered, "correct horse battery".toCharArray()));
    }

    @Test
    void passwordsMustMeetMinimumLength() {
        char[] password = "short".toCharArray();
        assertThrows(IllegalArgumentException.class,
                () -> encryption.encrypt(new byte[]{1}, password));
        assertTrue(Arrays.equals(new char[password.length], password));
    }
}
