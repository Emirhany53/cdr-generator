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
     * ASN.1 BIT STRING. X.690 8.6.2: the FIRST contents octet states how many
     * bits of the final octet are unused (0..7); the bit data follows.
     *
     * <p>Without this constant a BIT STRING fell through to {@link #STRING} and
     * was written as plain text with no leading unused-bits octet, which no
     * decoder can read back as a bit string. IMPLICIT tagging does not rescue
     * this: a context tag replaces the type's TAG, it does not change the
     * CONTENTS rule. 6 fields across 6 modules (CDRF-R7, CHFChargingDataTypes16,
     * LTE-R10 among them) are declared BIT STRING.</p>
     */
    BIT_STRING,
    /**
     * ASN.1 OBJECT IDENTIFIER. X.690 8.19: the arcs are packed - the first two
     * share one subidentifier (40*arc1+arc2) and the rest are base-128 - so the
     * dotted text "1.3.6.1" is NOT its own encoding. While this fell through to
     * {@link #STRING} the characters were written verbatim under OCTET STRING's
     * tag 4. 6 fields across 6 modules (CDRF-R7/R9, CHFChargingDataTypes16,
     * Newchf, MAVENIRTEST) are declared OBJECT IDENTIFIER, all untagged, so both
     * their tag and their contents were wrong.
     */
    OBJECT_IDENTIFIER,
    /**
     * ASN.1 REAL. X.690 8.5: zero has no contents octets at all, and a non-zero
     * value needs a leading octet selecting the representation before the
     * number. No REAL field is reachable from any module's selected root today
     * (UAGRecordsBer declares some, but not under its root), so this is
     * defensive rather than a fix for observed output.
     */
    REAL,
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
        if (upper.startsWith("BIT STRING") || upper.startsWith("BITSTRING")) {
            return BIT_STRING;
        }
        if (upper.startsWith("OBJECT IDENTIFIER") || upper.startsWith("OBJECTIDENTIFIER")) {
            return OBJECT_IDENTIFIER;
        }
        // Guarded against a merely REAL-prefixed name: RealUnit is a SEQUENCE of
        // two INTEGERs in GPRS-Charging-Extensions, not an ASN.1 REAL.
        if (upper.equals("REAL") || upper.startsWith("REAL ") || upper.startsWith("REAL(")) {
            return REAL;
        }
        if (upper.equals("NULL") || upper.startsWith("NULL ") || upper.startsWith("NULL(")) {
            // Guarded against a merely NULL-prefixed name (e.g. "NullableCount")
            // being misread as the NULL type.
            return NULL;
        }
        return STRING;
    }
}