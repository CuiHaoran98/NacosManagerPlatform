package com.nacos.ncg.shared;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * ULID 生成器：26 字符 Crockford Base32（48 位毫秒时间 + 80 位随机）。
 * 详细设计 v1.1 §5.1：主键统一 varchar(26) ULID；时间 UTC。
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String generate() {
        return generate(Instant.now());
    }

    public static String generate(Instant instant) {
        long millis = instant.toEpochMilli();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        char[] out = new char[26];
        // 48 位时间 → 10 个字符
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (millis & 0x1F)];
            millis >>>= 5;
        }
        // 80 位随机 → 16 个字符
        long bits = 0;
        int bitCount = 0;
        int idx = 10;
        for (byte b : random) {
            bits = (bits << 8) | (b & 0xFF);
            bitCount += 8;
            while (bitCount >= 5 && idx < 26) {
                out[idx++] = ALPHABET[(int) ((bits >>> (bitCount - 5)) & 0x1F)];
                bitCount -= 5;
            }
        }
        return new String(out);
    }

    public static boolean isValid(String value) {
        if (value == null || value.length() != 26) {
            return false;
        }
        for (int i = 0; i < 26; i++) {
            char c = value.charAt(i);
            if (c < '0' || (c > '9' && c < 'A') || (c > 'Z') || (c == 'I' || c == 'L' || c == 'O' || c == 'U')) {
                if (indexOf(c) < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int indexOf(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) {
                return i;
            }
        }
        return -1;
    }
}
