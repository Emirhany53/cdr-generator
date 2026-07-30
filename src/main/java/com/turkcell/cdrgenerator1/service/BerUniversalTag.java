package com.turkcell.cdrgenerator1.service;

import java.util.Locale;
import java.util.Objects;

/**
 * The universal-class tag numbers used by this encoder when a field carries
 * no [n] annotation, or as the inner tag of an EXPLICIT wrapper.
 */
public enum BerUniversalTag {

    BOOLEAN(1),
    INTEGER(2),
    OCTET_STRING(4),
    /** X.690 8.8: NULL's contents octets are always absent (length 0). */
    NULL(5),
    ENUMERATED(10),
    UTF8_STRING(12),
    SEQUENCE(16),
    /**
     * X.690 8.11: a SET value carries universal tag 17, not 16. Emitting
     * SEQUENCE for a SET makes a strict decoder reject the record, because the
     * inner tag of an EXPLICIT wrapper must be the type's own universal tag.
     */
    SET(17),
    IA5_STRING(22);

    private static final String UTF8_KEYWORD = "UTF8STRING";
    private static final String IA5_KEYWORD = "IA5STRING";

    private final int tagNumber;

    BerUniversalTag(int tagNumber) {
        this.tagNumber = tagNumber;
    }

    public int getTagNumber() {
        return tagNumber;
    }

    /** Maps an ASN.1 leaf type expression to the universal tag to emit for it. */
    public static BerUniversalTag forPrimitiveType(String typeExpression) {
        BerPrimitiveType primitiveType = BerPrimitiveType.fromTypeExpression(typeExpression);
        return switch (primitiveType) {
            case BOOLEAN -> BOOLEAN;
            case INTEGER -> INTEGER;
            case ENUMERATED -> ENUMERATED;
            case OCTET_STRING -> OCTET_STRING;
            case NULL -> NULL;
            case STRING -> forStringType(typeExpression);
        };
    }

    private static BerUniversalTag forStringType(String typeExpression) {
        String upper = Objects.isNull(typeExpression)
                ? ""
                : typeExpression.toUpperCase(Locale.ROOT);
        if (upper.startsWith(UTF8_KEYWORD)) {
            return UTF8_STRING;
        }
        if (upper.startsWith(IA5_KEYWORD)) {
            return IA5_STRING;
        }
        // Unknown textual/custom types: OCTET STRING is the safest container.
        return OCTET_STRING;
    }
}
