package com.turkcell.cdrgenerator1.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helper for stripping the ASN.1 literal decoration that
 * {@code CdrRecordBuilder} adds to generated leaf values.
 *
 * <p>The builder wraps values so the BER encoder can recognise their type:
 * strings become {@code "value"} and INTEGER/BOOLEAN become {@code 'value'D}.
 * The token-separated (.dat) output and the JSON preview endpoint both need
 * the bare value instead, so the stripping logic lives here in one place
 * (DRY) rather than being duplicated across the writer and the controller.</p>
 */
public final class AsnLiteralFormatter {

    private static final int MIN_QUOTED_LENGTH = 2;
    private static final int MIN_DECIMAL_LENGTH = 3;
    private static final String DECIMAL_SUFFIX = "'D";

    private AsnLiteralFormatter() {
    }

    /**
     * Removes surrounding double quotes for string literals and the
     * {@code '...'D} wrapper for INTEGER/BOOLEAN literals, returning the bare
     * token value. Any value that is not decorated is returned unchanged.
     */
    public static String strip(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.length() >= MIN_QUOTED_LENGTH && text.startsWith("\"") && text.endsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        if (text.length() >= MIN_DECIMAL_LENGTH && text.startsWith("'") && text.endsWith(DECIMAL_SUFFIX)) {
            return text.substring(1, text.length() - DECIMAL_SUFFIX.length());
        }
        return text;
    }

    /**
     * Returns a deep copy of a record tree with every leaf value stripped of
     * its ASN.1 literal decoration. Nested objects and repeated groups are
     * preserved so the cleaned structure mirrors the original.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> stripRecord(Map<String, Object> record) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                cleaned.put(entry.getKey(), stripRecord((Map<String, Object>) nested));
            } else if (value instanceof List<?> items) {
                cleaned.put(entry.getKey(), stripList((List<Object>) items));
            } else {
                cleaned.put(entry.getKey(), strip(value));
            }
        }
        return cleaned;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> stripList(List<Object> items) {
        List<Object> cleaned = new java.util.ArrayList<>(items.size());
        for (Object item : items) {
            if (item instanceof Map<?, ?> nested) {
                cleaned.add(stripRecord((Map<String, Object>) nested));
            } else {
                cleaned.add(strip(item));
            }
        }
        return cleaned;
    }
}