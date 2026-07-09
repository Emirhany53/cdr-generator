package com.turkcell.cdrgenerator1.model;

/**
 * ASN.1 types this encoder supports, mapped to how their value bytes
 * are produced and whether they are constructed (i.e. contain child TLVs).
 */
public enum AsnType {

    INTEGER(false),
    ENUMERATED(false),
    BOOLEAN(false),
    /** IA5String / UTF8String / printable text: encoded as ASCII bytes. */
    STRING(false),
    /** OCTET STRING carrying a hex value (the 'H suffix in the ASCII dump). */
    OCTET_STRING(false),

    /** Constructed container whose value is the concatenation of child TLVs. */
    SEQUENCE(true),
    SET(true);

    private final boolean constructed;

    AsnType(boolean constructed) {
        this.constructed = constructed;
    }

    public boolean isConstructed() {
        return constructed;
    }
}