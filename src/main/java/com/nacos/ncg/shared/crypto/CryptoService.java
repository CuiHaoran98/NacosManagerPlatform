package com.nacos.ncg.shared.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 敏感值/配置内容加密（详细设计 v1.1 §9.4 / C-ARC-12）：
 * AES-256-GCM，密文格式 enc:v1:{keyId}:{base64(iv|ciphertext|tag)}，落库为该字符串的 UTF-8 字节。
 * 密钥来自 NCG_CRYPTO_KEY（base64 32 字节）与 NCG_CRYPTO_KEY_ID；未配置时使用即抛错（fail-closed，
 * 不允许静默明文落库）。密钥轮换：解密按密文内 keyId 选钥，加密一律用当前 keyId。
 */
@Component
public class CryptoService {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;
    private static final String PREFIX = "enc:v1:";

    private final String keyId;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(
            @Value("${ncg.crypto.key:}") String keyBase64,
            @Value("${ncg.crypto.key-id:default}") String keyId) {
        this.keyId = keyId == null || keyId.isBlank() ? "default" : keyId;
        if (keyBase64 == null || keyBase64.isBlank()) {
            // 不在构造期抛出：未启用加密路径的只读部署仍可启动；加密调用时 fail-closed。
            this.key = null;
        } else {
            byte[] decoded = Base64.getDecoder().decode(keyBase64);
            if (decoded.length != 32) {
                throw new IllegalStateException("ncg.crypto.key must be 32 bytes (base64), got " + decoded.length);
            }
            this.key = decoded;
        }
    }

    public String keyId() {
        return keyId;
    }

    /** 加密明文 → enc:v1:{keyId}:{base64(iv|ct|tag)} 的 UTF-8 字节（存 bytea 列）。 */
    public byte[] encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv).put(ct);
            String encoded = PREFIX + keyId + ":" + Base64.getEncoder().encodeToString(buf.array());
            return encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /** 解密 {@link #encrypt} 产物；密文内 keyId 与当前不同时仍尝试当前密钥（P0 单钥），失败即抛。 */
    public String decrypt(byte[] ciphertext) {
        requireKey();
        String encoded = new String(ciphertext, java.nio.charset.StandardCharsets.UTF_8);
        if (!encoded.startsWith(PREFIX)) {
            throw new IllegalStateException("ciphertext not in enc:v1 format");
        }
        String rest = encoded.substring(PREFIX.length());
        int sep = rest.indexOf(':');
        if (sep < 0) {
            throw new IllegalStateException("ciphertext missing keyId separator");
        }
        // keyId 目前只做记录与轮换索引；P0 单密钥直接解。
        byte[] packed = Base64.getDecoder().decode(rest.substring(sep + 1));
        if (packed.length < IV_LEN + 1) {
            throw new IllegalStateException("ciphertext too short");
        }
        try {
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(packed, IV_LEN, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("ncg.crypto.key not configured (NCG_CRYPTO_KEY); fail-closed, refuse plaintext storage");
        }
    }
}
