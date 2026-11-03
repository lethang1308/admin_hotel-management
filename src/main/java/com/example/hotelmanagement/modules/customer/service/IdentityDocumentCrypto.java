package com.example.hotelmanagement.modules.customer.service;

import com.example.hotelmanagement.common.util.CmsMessages;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

@Service
public class IdentityDocumentCrypto {
    private final byte[] key;
    private final CmsMessages messages;

    public IdentityDocumentCrypto(
            @Value("${app.identity.encryption-key:}") String encoded, CmsMessages messages) {
        this.messages = messages;
        try {
            key = encoded.isBlank() ? null : Base64.getDecoder().decode(encoded.trim());
            if (key != null && key.length != 32) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "CUSTOMER_DOCUMENT_ENCRYPTION_KEY must be a base64 encoded 32-byte key");
        }
    }

    public boolean configured() {
        return key != null;
    }

    private void check() {
        if (key == null) throw messages.error("cms.error.identityKey");
    }

    public String normalize(String s) {
        return s.replaceAll("[ -]", "").toUpperCase(Locale.ROOT);
    }

    public String encrypt(String value) {
        check();
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] encrypted = c.doFinal(normalize(value).getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Identity encryption failed");
        }
    }

    public String masked(String encrypted) {
        if (encrypted == null) return "-";
        check();
        try {
            byte[] packed = Base64.getDecoder().decode(encrypted.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(packed, 0, 12)));
            String value =
                    new String(
                            c.doFinal(Arrays.copyOfRange(packed, 12, packed.length)),
                            StandardCharsets.UTF_8);
            return "•••• " + value.substring(Math.max(0, value.length() - 4));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Identity document cannot be decrypted with the configured key");
        }
    }

    public String hash(String value) {
        if (!configured() || value == null || value.isBlank()) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(key);
            byte[] searchKey = digest.digest("identity-search-v1".getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(searchKey, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(normalize(value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Identity lookup failed");
        }
    }
}
