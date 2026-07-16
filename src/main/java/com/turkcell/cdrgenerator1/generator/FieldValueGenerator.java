package com.turkcell.cdrgenerator1.generator;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

/**
 * Produces a random test value for a single ASN.1 leaf field.
 *
 * <p>The ASN.1 type always decides the value space first, so a field named
 * e.g. {@code acctStatusType} or {@code durationOfStorage} can never turn a
 * BOOLEAN or ENUMERATED field into free text (which would be impossible to
 * BER-encode). Name-based heuristics (msisdn, timestamp, duration...) only
 * refine the value within that type's space.</p>
 */
@Component
public class FieldValueGenerator {

    private static final int MAX_DURATION_VALUE = 1000;
    private static final int MAX_INTEGER_VALUE = 100;
    private static final int MAX_GENERIC_SUFFIX = 999;
    /** ENUMERATED values are encoded as integers; 0 and 1 exist in virtually every enum. */
    private static final int ENUMERATED_VALUE_BOUND = 2;
    private static final int RANDOM_STRING_LENGTH = 8;
    private static final String PHONE_PREFIX = "90532";
    private static final long PHONE_POSTFIX_BASE = 1_000_000L;
    private static final long PHONE_POSTFIX_RANGE = 8_999_999L;
    private static final String GENERIC_VALUE_PREFIX = "VAL";
    private static final String RANDOM_STRING_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    /** Uppercase hex alphabet for OCTET STRING / TBCD values (even length keeps them valid hex). */
    private static final String HEX_ALPHABET = "0123456789ABCDEF";
    private static final String UNKNOWN_TYPE_VALUE = "UNKNOWN";
    private static final String BOOLEAN_TRUE_VALUE = "1";
    private static final String BOOLEAN_FALSE_VALUE = "0";

    private final Random random = new Random();
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public String generateValue(String fieldName, String fieldType) {
        // Türkçe karakter (I/İ/ı/i) dönüşüm hatalarını önlemek için Locale.ENGLISH kullanılıyor
        String upperType = Objects.isNull(fieldType) ? "" : fieldType.toUpperCase(Locale.ENGLISH);
        String lowerName = Objects.isNull(fieldName) ? "" : fieldName.toLowerCase(Locale.ENGLISH);

        // Numeric-only types come first: their value must stay an integer no
        // matter what the field name suggests, otherwise BER encoding fails.
        if (upperType.contains("BOOLEAN")) {
            return random.nextBoolean() ? BOOLEAN_TRUE_VALUE : BOOLEAN_FALSE_VALUE;
        }
        if (upperType.contains("ENUMERATED")) {
            return String.valueOf(random.nextInt(ENUMERATED_VALUE_BOUND));
        }
        if (upperType.contains("INTEGER")) {
            boolean quantityLike = lowerName.contains("duration")
                    || lowerName.contains("volume") || lowerName.contains("count");
            return String.valueOf(random.nextInt(quantityLike ? MAX_DURATION_VALUE : MAX_INTEGER_VALUE));
        }

        // Text-like types: the field name picks a realistic shape.
        if (lowerName.contains("time") || lowerName.contains("date")) {
            return generateTimestamp();
        }
        if (lowerName.contains("msisdn") || lowerName.contains("number")
                || lowerName.contains("imsi") || lowerName.contains("imei")) {
            return generatePhoneNumber();
        }
        if (lowerName.contains("duration") || lowerName.contains("volume") || lowerName.contains("count")) {
            return String.valueOf(random.nextInt(MAX_DURATION_VALUE));
        }

        if (Objects.isNull(fieldType)) {
            return UNKNOWN_TYPE_VALUE;
        }
        // OCTET STRING / TBCD values are emitted as unambiguous even-length hex so
        // the BER encoder always interprets them as raw octets. Text types stay
        // alphanumeric text. This removes the earlier ambiguity where a random
        // OCTET STRING value was hex or text depending on which letters it drew.
        if (upperType.contains("OCTET STRING") || upperType.contains("TBCD STRING")) {
            return generateHexString(RANDOM_STRING_LENGTH);
        }
        if (upperType.contains("IA5STRING")) {
            return generateRandomString(RANDOM_STRING_LENGTH);
        }

        return GENERIC_VALUE_PREFIX + random.nextInt(MAX_GENERIC_SUFFIX);
    }

    private String generatePhoneNumber() {
        long postfix = PHONE_POSTFIX_BASE + (long) (random.nextDouble() * PHONE_POSTFIX_RANGE);
        return PHONE_PREFIX + postfix;
    }

    private String generateTimestamp() {
        return LocalDateTime.now().format(timeFormatter);
    }

    private String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM_STRING_ALPHABET.charAt(random.nextInt(RANDOM_STRING_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Even-length uppercase hex string, used for OCTET STRING / TBCD values. */
    private String generateHexString(int length) {
        int evenLength = (length % 2 == 0) ? length : length + 1;
        StringBuilder sb = new StringBuilder(evenLength);
        for (int i = 0; i < evenLength; i++) {
            sb.append(HEX_ALPHABET.charAt(random.nextInt(HEX_ALPHABET.length())));
        }
        return sb.toString();
    }
}