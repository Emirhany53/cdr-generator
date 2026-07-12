package com.turkcell.cdrgenerator1.model;

/**
 * The four BER tag classes. The class occupies the two most significant bits
 * of the identifier (first tag) byte, so each constant carries its bit
 * pattern ready to be OR'ed into that byte.
 */
public enum BerTagClass {

    UNIVERSAL(0x00),
    APPLICATION(0x40),
    CONTEXT(0x80),
    PRIVATE(0xC0);

    private final int classBits;

    BerTagClass(int classBits) {
        this.classBits = classBits;
    }

    public int getClassBits() {
        return classBits;
    }
}
