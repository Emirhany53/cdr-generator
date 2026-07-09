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
    OCTET_STRING;

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
        return STRING;
    }
}