package ru.grigold.claudebridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Секретный код. Хранится не сам код, а его хэш PBKDF2 с солью:
 * из файла secret.dat узнать код нельзя, можно только проверить, совпадает ли он.
 */
final class SecretCode {

    private static final int ITERATIONS = 20_000;

    private final File file;
    private volatile byte[] salt;
    private volatile byte[] hash;

    SecretCode(File file) {
        this.file = file;
        load();
    }

    boolean isSet() {
        return hash != null;
    }

    void set(String code) throws IOException {
        byte[] newSalt = new byte[16];
        new SecureRandom().nextBytes(newSalt);
        byte[] newHash = derive(code, newSalt);
        Base64.Encoder b64 = Base64.getEncoder();
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), "pbkdf2-sha256$" + ITERATIONS + "$"
                + b64.encodeToString(newSalt) + "$" + b64.encodeToString(newHash), StandardCharsets.UTF_8);
        salt = newSalt;
        hash = newHash;
    }

    boolean matches(String code) {
        byte[] s = salt, h = hash;
        if (h == null || code.isEmpty()) return false;
        return MessageDigest.isEqual(h, derive(code, s));
    }

    private void load() {
        if (!file.exists()) return;
        try {
            String[] parts = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim().split("\\$");
            if (parts.length != 4) return;
            salt = Base64.getDecoder().decode(parts[2]);
            hash = Base64.getDecoder().decode(parts[3]);
        } catch (IOException | IllegalArgumentException e) {
            salt = null;
            hash = null;
        }
    }

    private static byte[] derive(String code, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
