package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TlvWriterTest {

    private final TlvWriter tlvWriter = new TlvWriter();

    // --- INTEGER two's-complement sign handling ---

    @Test
    void encodeIntegerSmallPositiveIsSingleByte() {
        assertArrayEquals(new byte[]{0x7F}, tlvWriter.encodeInteger(127));
    }

    @Test
    void encodeIntegerPositiveWithHighBitGetsZeroPad() {
        // Without the 0x00 pad, 128 would decode as -128.
        assertArrayEquals(new byte[]{0x00, (byte) 0x80}, tlvWriter.encodeInteger(128));
        assertArrayEquals(new byte[]{0x00, (byte) 0xFF}, tlvWriter.encodeInteger(255));
    }

    @Test
    void encodeIntegerZero() {
        assertArrayEquals(new byte[]{0x00}, tlvWriter.encodeInteger(0));
    }

    @Test
    void encodeIntegerNegativeSingleByte() {
        assertArrayEquals(new byte[]{(byte) 0xFF}, tlvWriter.encodeInteger(-1));
        assertArrayEquals(new byte[]{(byte) 0x80}, tlvWriter.encodeInteger(-128));
    }

    @Test
    void encodeIntegerNegativeNeedingPad() {
        // Without the 0xFF pad, -129 would decode as +127.
        assertArrayEquals(new byte[]{(byte) 0xFF, 0x7F}, tlvWriter.encodeInteger(-129));
    }

    @Test
    void encodeIntegerMultiBytePositive() {
        assertArrayEquals(new byte[]{0x01, 0x00}, tlvWriter.encodeInteger(256));
        assertArrayEquals(new byte[]{0x04, (byte) 0x80, (byte) 0x83}, tlvWriter.encodeInteger(295043));
    }

    // --- BOOLEAN ---

    @Test
    void encodeBooleanCanonicalBytes() {
        assertArrayEquals(new byte[]{(byte) 0xFF}, tlvWriter.encodeBoolean(true));
        assertArrayEquals(new byte[]{0x00}, tlvWriter.encodeBoolean(false));
    }

    // --- Tag encoding ---

    @Test
    void encodeTagContextPrimitiveLowNumber() {
        assertArrayEquals(new byte[]{(byte) 0x81}, tlvWriter.encodeTag(1, false));
    }

    @Test
    void encodeTagContextConstructed() {
        assertArrayEquals(new byte[]{(byte) 0xA5}, tlvWriter.encodeTag(5, true));
    }

    @Test
    void encodeTagHighNumberUsesMultiByteForm() {
        // Tag 300 = 0b10_0101100 -> first byte 0x9F (context|0x1F), then 0x82, 0x2C.
        assertArrayEquals(new byte[]{(byte) 0x9F, (byte) 0x82, 0x2C}, tlvWriter.encodeTag(300, false));
    }

    @Test
    void encodeTagBoundaryThirtyIsSingleByte() {
        assertArrayEquals(new byte[]{(byte) 0x9E}, tlvWriter.encodeTag(30, false));
    }

    @Test
    void encodeTagBoundaryThirtyOneIsMultiByte() {
        assertArrayEquals(new byte[]{(byte) 0x9F, 0x1F}, tlvWriter.encodeTag(31, false));
    }

    @Test
    void encodeTagUniversalSequence() {
        assertArrayEquals(new byte[]{0x30},
                tlvWriter.encodeTag(BerTagClass.UNIVERSAL, 16, true));
    }

    @Test
    void encodeTagApplicationClass() {
        assertArrayEquals(new byte[]{0x43},
                tlvWriter.encodeTag(BerTagClass.APPLICATION, 3, false));
    }

    // --- Length encoding ---

    @Test
    void encodeLengthShortForm() {
        assertArrayEquals(new byte[]{0x00}, tlvWriter.encodeLength(0));
        assertArrayEquals(new byte[]{0x7F}, tlvWriter.encodeLength(127));
    }

    @Test
    void encodeLengthLongForm() {
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x80}, tlvWriter.encodeLength(128));
        assertArrayEquals(new byte[]{(byte) 0x82, 0x01, 0x00}, tlvWriter.encodeLength(256));
    }

    // --- Assembled TLV & value helpers ---

    @Test
    void buildTlvAssemblesTagLengthValue() {
        byte[] tlv = tlvWriter.buildTlv(1, false, new byte[]{0x41, 0x42});
        assertArrayEquals(new byte[]{(byte) 0x81, 0x02, 0x41, 0x42}, tlv);
    }

    @Test
    void encodeStringUsesUtf8Bytes() {
        assertArrayEquals("ABC".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                tlvWriter.encodeString("ABC"));
    }

    @Test
    void encodeHexConvertsPairs() {
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
                tlvWriter.encodeHex("DEADBEEF"));
    }

    @Test
    void encodeHexEmptyProducesNoBytes() {
        assertEquals(0, tlvWriter.encodeHex("").length);
    }
}
