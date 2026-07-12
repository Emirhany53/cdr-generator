package com.turkcell.cdrgenerator1.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldValueGeneratorTest {

    private final FieldValueGenerator generator = new FieldValueGenerator();

    @Test
    void msisdnLikeNamesProducePhoneNumbers() {
        for (String name : new String[]{"msisdn", "bNumber", "origIMSI", "imeiCode"}) {
            String value = generator.generateValue(name, "OCTET STRING");
            assertTrue(value.startsWith("90532"), name + " should look like a phone number: " + value);
            assertTrue(value.matches("\\d+"), name + " should be all digits: " + value);
        }
    }

    @Test
    void timeLikeNamesProduceTimestamps() {
        String value = generator.generateValue("timeStamp", "OCTET STRING");
        assertEquals(14, value.length(), "yyyyMMddHHmmss expected: " + value);
        assertTrue(value.matches("\\d{14}"));
    }

    @Test
    void durationLikeNamesProduceNumbers() {
        String value = generator.generateValue("duration", "INTEGER");
        assertTrue(value.matches("\\d+"));
        assertTrue(Integer.parseInt(value) < 1000);
    }

    @Test
    void integerTypeProducesNumber() {
        String value = generator.generateValue("someField", "INTEGER");
        assertTrue(value.matches("\\d+"));
    }

    @Test
    void booleanTypeProducesZeroOrOne() {
        String value = generator.generateValue("someField", "BOOLEAN");
        assertTrue(value.equals("0") || value.equals("1"));
    }

    @Test
    void stringTypesProduceAlphanumericText() {
        for (String type : new String[]{"IA5String", "OCTET STRING", "TBCD STRING"}) {
            String value = generator.generateValue("someField", type);
            assertEquals(8, value.length());
            assertTrue(value.matches("[A-Z0-9]+"), type + " -> " + value);
        }
    }

    @Test
    void booleanTypeWinsOverNameHeuristics() {
        // Regression: a BOOLEAN field named e.g. "chargingTime" used to get a
        // 14-digit timestamp, which the BER encoder rejects as a boolean.
        for (String name : new String[]{"chargingTime", "retryCount", "startDate"}) {
            String value = generator.generateValue(name, "BOOLEAN");
            assertTrue(value.equals("0") || value.equals("1"),
                    name + " of type BOOLEAN must stay 0/1 but was: " + value);
        }
    }

    @Test
    void enumeratedTypeProducesEncodableInteger() {
        String value = generator.generateValue("recordType", "ENUMERATED { threegpp, nin }");
        assertTrue(value.matches("\\d+"), "ENUMERATED must be an integer: " + value);
    }

    @Test
    void integerTypeWinsOverTimestampNameHeuristic() {
        String value = generator.generateValue("eventTime", "INTEGER");
        assertTrue(value.matches("\\d+"));
        assertTrue(Long.parseLong(value) < 1000, "must be a plain number, not a timestamp: " + value);
    }

    @Test
    void nullTypeFallsBackToUnknown() {
        assertEquals("UNKNOWN", generator.generateValue("someField", null));
    }

    @Test
    void unrecognizedTypeGetsGenericValue() {
        String value = generator.generateValue("someField", "WeirdCustomType");
        assertTrue(value.startsWith("VAL"));
    }
}
