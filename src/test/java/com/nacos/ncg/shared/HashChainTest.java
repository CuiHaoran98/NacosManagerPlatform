package com.nacos.ncg.shared;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HashChainTest {

    @Test
    void genesisIs64Zeros() {
        assertEquals("0".repeat(64), HashChain.GENESIS);
    }

    @Test
    void eventHashIsDeterministicAndChainSensitive() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("chain_date", "2026-11-22");
        fields.put("seq", "1");
        fields.put("action", "LOGIN_SUCCESS");
        String h1 = HashChain.eventHash(HashChain.GENESIS, fields);
        String h2 = HashChain.eventHash(HashChain.GENESIS, fields);
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
        assertNotEquals(h1, HashChain.eventHash(h1, fields), "换 prevHash 必变");
        fields.put("seq", "2");
        assertNotEquals(h1, HashChain.eventHash(HashChain.GENESIS, fields), "换字段必变");
    }

    @Test
    void canonicalJsonSkipsNullsAndSortsKeys() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("b", "2");
        fields.put("a", "1");
        fields.put("c", null);
        String json = HashChain.canonicalJson(fields);
        assertEquals("{\"a\":\"1\",\"b\":\"2\"}", json);
    }

    @Test
    void canonicalJsonEscapes() {
        Map<String, String> fields = Map.of("k", "a\"b\\c\nd");
        String json = HashChain.canonicalJson(fields);
        assertFalse(json.contains("\n"), "newline must be escaped");
        assertTrue(json.contains("\\\""), "quote must be escaped");
    }
}
