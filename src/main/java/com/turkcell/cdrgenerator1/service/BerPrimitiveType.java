package com.turkcell.cdrgenerator1.service;

import java.util.Locale;

/**
 * Maps the raw ASN.1 type expression (kept as a String in AsnField.fieldType)
 * to how a primitive value must be turned into BER value bytes.
 *
 * <p>The encoder decides "constructed vs primitive" from whether the field has
 * children; this enum only classifies the primitive leaf types.</p>
 */
public enum BerPrimitiveType {

    INTEGER,
    ENUMERATED,
    BOOLEAN,
    /** Any textual type (IA5String, UTF8String, PrintableString, GraphicString...). */
    STRING,
    /** OCTET STRING: value supplied as a hex string (the 'H dump values). */
    OCTET_STRING,
    /**
     * ASN.1 NULL: a pure presence marker. X.690 8.8 requires its contents octets
     * to be ABSENT, so it always encodes with length 0 no matter what value the
     * generator produced for it.
     *
     * <p>Without this constant NULL fell through to {@link #STRING} and got a
     * random generated text as its value - {@code iMSEmergencyIndicator [52]
     * NULL} came out as an 8-byte string, and {@code mediaInitiatorFlag [3]
     * NULL} as a 1-byte one, where an EMM-accepted reference capture has
     * zero-length. That is what made EMM reject the record with "Invalid
     * length" inside list-Of-SDP-Media-Components.</p>
     */
    NULL;

    /**
     * Classifies an ASN.1 type expression. Unknown/custom type names are treated
     * as STRING, which is the safest default for text-like leaf values.
     */
    public static BerPrimitiveType fromTypeExpression(String typeExpression) {
        if (typeExpression == null) {
            return STRING;
        }
        String upper = typeExpression.toUpperCase(Locale.ROOT).trim();

        if (upper.startsWith("INTEGER")) {
            return INTEGER;
        }
        if (upper.startsWith("ENUMERATED")) {
            return ENUMERATED;
        }
        if (upper.startsWith("BOOLEAN")) {
            return BOOLEAN;
        }
        if (upper.startsWith("OCTET STRING") || upper.startsWith("OCTETSTRING")) {
            return OCTET_STRING;
        }
        if (upper.equals("NULL") || upper.startsWith("NULL ") || upper.startsWith("NULL(")) {
            // Guarded against a merely NULL-prefixed name (e.g. "NullableCount")
            // being misread as the NULL type.
            return NULL;
        }
        return STRING;
    }
}