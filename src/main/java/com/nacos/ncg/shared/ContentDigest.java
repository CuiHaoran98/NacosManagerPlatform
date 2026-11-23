package com.nacos.ncg.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容摘要工具：MD5 用于基线/读回比对（与 Nacos 一致），SHA-256 用于内容完整性与审计哈希链。
 * MD5 仅用于非安全性的内容比对，不用于任何安全判定。
 */
public final class ContentDigest {

    private ContentDigest() {
    }

    public static String md5Hex(byte[] content) {
        return hex(digest("MD5", content));
    }

    public static String md5Hex(String content) {
        return md5Hex(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] content) {
        return hex(digest("SHA-256", content));
    }

    public static String sha256Hex(String content) {
        return sha256Hex(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(String algorithm, byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return md.digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
