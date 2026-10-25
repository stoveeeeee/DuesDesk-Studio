package com.fraternity.dues;

import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.util.Base64;
import java.security.MessageDigest;

@Component
public class OsCredentialStore {
    private static final String KEY_ALIAS = "DuesDesk.DailyBackupEncryptionKey";
    private static final char[] KEYCHAIN_ITEM_PASSWORD = "DuesDesk-Keychain-Item".toCharArray();

    public synchronized char[] getOrCreateBackupPassword() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            throw new IllegalStateException("Automatic encrypted backups currently require the macOS Keychain.");
        }
        KeyStore keychain = KeyStore.getInstance("KeychainStore");
        keychain.load(null, null);
        if (keychain.containsAlias(KEY_ALIAS)) {
            java.security.Key storedKey = keychain.getKey(KEY_ALIAS, KEYCHAIN_ITEM_PASSWORD);
            if (!(storedKey instanceof java.security.PrivateKey privateKey)) {
                throw new IllegalStateException("The saved backup credential in Keychain has an unsupported format.");
            }
            return encodeSecret(privateKey.getEncoded());
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        keychain.setKeyEntry(KEY_ALIAS, keyPair.getPrivate(), KEYCHAIN_ITEM_PASSWORD,
                new java.security.cert.Certificate[0]);
        keychain.store(null, null);
        return encodeSecret(keyPair.getPrivate().getEncoded());
    }

    private char[] encodeSecret(byte[] privateKey) throws Exception {
        if (privateKey == null || privateKey.length == 0) {
            throw new IllegalStateException("The macOS Keychain returned an invalid automatic backup key.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(privateKey);
            try {
                return Base64.getEncoder().encodeToString(digest).toCharArray();
            } finally {
                java.util.Arrays.fill(digest, (byte) 0);
            }
        } finally {
            java.util.Arrays.fill(privateKey, (byte) 0);
        }
    }
}
