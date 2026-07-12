package com.turkcell.cdrgenerator1.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsnLiteralFormatterTest {

    @Test
    void quotedStringLiteralsLoseTheirQuotes() {
        assertEquals("905321234567", AsnLiteralFormatter.strip("\"905321234567\""));
    }

    @Test
    void decimalIntegerLiteralsLoseTheirWrapper() {
        assertEquals("42", AsnLiteralFormatter.strip("'42'D"));
    }

    @Test
    void nullBecomesEmptyString() {
        assertEquals("", AsnLiteralFormatter.strip(null));
    }

    @Test
    void undecoratedValueIsReturnedUnchanged() {
        assertEquals("plain", AsnLiteralFormatter.strip("plain"));
    }

    @Test
    void stripRecordCleansNestedMapsAndLists() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("ton", "'1'D");
        inner.put("number", "\"905\"");

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", "'7'D");
        record.put("addr", inner);
        record.put("attrs", List.of("\"A\"", "\"B\""));

        Map<String, Object> cleaned = AsnLiteralFormatter.stripRecord(record);

        assertEquals("7", cleaned.get("id"));
        Map<?, ?> cleanedAddr = (Map<?, ?>) cleaned.get("addr");
        assertEquals("1", cleanedAddr.get("ton"));
        assertEquals("905", cleanedAddr.get("number"));
        assertEquals(List.of("A", "B"), cleaned.get("attrs"));
    }
}
