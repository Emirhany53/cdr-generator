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
    /** X.690 8.6: contents are [unused-bit count][bit data]. */
    BIT_STRING(3),
    OCTET_STRING(4),
    /** X.690 8.8: NULL's contents octets are always absent (length 0). */
    NULL(5),
    ENUMERATED(10),
    UTF8_STRING(12),
    SEQUENCE(16),
    NUMERIC_STRING(18),
    PRINTABLE_STRING(19),
    TELETEX_STRING(20),
    VIDEOTEX_STRING(21),
    /**
     * X.690 8.11: a SET value carries universal tag 17, not 16. Emitting
     * SEQUENCE for a SET makes a strict decoder reject the record, because the
     * inner tag of an EXPLICIT wrapper must be the type's own universal tag.
     */
    SET(17),
    IA5_STRING(22),
    UTC_TIME(23),
    GENERALIZED_TIME(24),
    GRAPHIC_STRING(25),
    VISIBLE_STRING(26),
    GENERAL_STRING(27),
    BMP_STRING(30);

    private static final String UTF8_KEYWORD = "UTF8STRING";
    private static final String IA5_KEYWORD = "IA5STRING";
    /**
     * The remaining restricted character-string types, keyed by the keyword the
     * ASN.1 type expression starts with. They only matter where the encoder
     * actually emits a universal tag - an untagged field, or the inner TLV of an
     * EXPLICIT wrapper - since an IMPLICIT context tag replaces it. Before this
     * table they all collapsed to OCTET STRING (tag 4): 27 such fields exist
     * across roughly ten modules (VisibleString and NumericString in
     * NRTRDEINFMSInput, UTCTime in the TAP modules, OBJECT IDENTIFIER aside).
     *
     * <p>Longest keyword first, so GENERALIZEDTIME is not matched by GENERAL.</p>
     */
    private static final java.util.List<java.util.Map.Entry<String, BerUniversalTag>> STRING_KEYWORDS =
            java.util.List.of(
                    java.util.Map.entry("GENERALIZEDTIME", GENERALIZED_TIME),
                    java.util.Map.entry("GENERALSTRING", GENERAL_STRING),
                    java.util.Map.entry("GRAPHICSTRING", GRAPHIC_STRING),
                    java.util.Map.entry("PRINTABLESTRING", PRINTABLE_STRING),
                    java.util.Map.entry("NUMERICSTRING", NUMERIC_STRING),
                    java.util.Map.entry("VISIBLESTRING", VISIBLE_STRING),
                    java.util.Map.entry("TELETEXSTRING", TELETEX_STRING),
                    java.util.Map.entry("VIDEOTEXSTRING", VIDEOTEX_STRING),
                    java.util.Map.entry("BMPSTRING", BMP_STRING),
                    java.util.Map.entry("UTCTIME", UTC_TIME));

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
            case BIT_STRING -> BIT_STRING;
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
        for (java.util.Map.Entry<String, BerUniversalTag> known : STRING_KEYWORDS) {
            if (upper.startsWith(known.getKey())) {
                return known.getValue();
            }
        }
        // Unknown textual/custom types: OCTET STRING is the safest container.
        return OCTET_STRING;
    }
}
