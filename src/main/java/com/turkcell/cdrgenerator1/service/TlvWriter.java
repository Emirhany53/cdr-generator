package com.turkcell.cdrgenerator1.service;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Low-level Basic Encoding Rules (BER) primitives: builds the Tag, Length
 * and Value byte groups that every field is made of. Kept free of any
 * ASN.1 model knowledge so it can be unit-tested in isolation (SRP).
 */
@Component
public class TlvWriter {

    /** Context class bit pattern (10xxxxxx) for [n] tagged fields. */
    private static final int CONTEXT_CLASS = 0x80;
    /** Constructed bit (bit 6) set on top of the class for SEQUENCE/SET/EXPLICIT. */
    private static final int CONSTRUCTED_BIT = 0x20;
    /** Marker in the first tag byte meaning "tag number continues in following bytes". */
    private static final int HIGH_TAG_MARKER = 0x1F;
    /** Largest tag number that fits in the single first byte. */
    private static final int MAX_SINGLE_BYTE_TAG = 0x1E;
    /** Continuation bit for multi-byte tag numbers and long-form length. */
    private static final int CONTINUATION_BIT = 0x80;
    /** 7-bit mask used when splitting a tag number into base-128 groups. */
    private static final int SEVEN_BIT_MASK = 0x7F;
    private static final int SEVEN_BITS = 7;
    /** Values below this fit into the short-form length byte. */
    private static final int SHORT_LENGTH_LIMIT = 0x80;
    private static final int BYTE_MASK = 0xFF;
    private static final int BITS_PER_BYTE = 8;

    /**
     * Builds the identifier (tag) bytes for a context-class field.
     *
     * @param tagNumber   the number from the [n] annotation
     * @param constructed true for SEQUENCE/SET or an EXPLICIT wrapper
     */
    public byte[] encodeTag(int tagNumber, boolean constructed) {
        int firstByteClass = CONTEXT_CLASS | (constructed ? CONSTRUCTED_BIT : 0);

        if (tagNumber <= MAX_SINGLE_BYTE_TAG) {
            return new byte[]{(byte) (firstByteClass | tagNumber)};
        }

        List<Integer> groups = new ArrayList<>();
        int remaining = tagNumber;
        groups.add(0, remaining & SEVEN_BIT_MASK);
        remaining >>= SEVEN_BITS;
        while (remaining > 0) {
            groups.add(0, (remaining & SEVEN_BIT_MASK) | CONTINUATION_BIT);
            remaining >>= SEVEN_BITS;
        }

        byte[] out = new byte[1 + groups.size()];
        out[0] = (byte) (firstByteClass | HIGH_TAG_MARKER);
        for (int i = 0; i < groups.size(); i++) {
            out[i + 1] = (byte) (int) groups.get(i);
        }
        return out;
    }

    /** Builds the length bytes (short form under 128, long form otherwise). */
    public byte[] encodeLength(int length) {
        if (length < SHORT_LENGTH_LIMIT) {
            return new byte[]{(byte) length};
        }

        List<Integer> lengthBytes = new ArrayList<>();
        int remaining = length;
        while (remaining > 0) {
            lengthBytes.add(0, remaining & BYTE_MASK);
            remaining >>= BITS_PER_BYTE;
        }

        byte[] out = new byte[1 + lengthBytes.size()];
        out[0] = (byte) (CONTINUATION_BIT | lengthBytes.size());
        for (int i = 0; i < lengthBytes.size(); i++) {
            out[i + 1] = (byte) (int) lengthBytes.get(i);
        }
        return out;
    }

    /** Assembles a full TLV: tag + length + value. */
    public byte[] buildTlv(int tagNumber, boolean constructed, byte[] value) {
        byte[] tag = encodeTag(tagNumber, constructed);
        byte[] length = encodeLength(value.length);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(tag.length + length.length + value.length);
        buffer.writeBytes(tag);
        buffer.writeBytes(length);
        buffer.writeBytes(value);
        return buffer.toByteArray();
    }

    /** Minimal two's-complement INTEGER value encoding. */
    public byte[] encodeInteger(long value) {
        List<Integer> bytes = new ArrayList<>();
        long remaining = value;
        do {
            bytes.add(0, (int) (remaining & BYTE_MASK));
            remaining >>= BITS_PER_BYTE;
        } while (remaining != 0 && remaining != -1);

        byte[] out = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            out[i] = (byte) (int) bytes.get(i);
        }
        return out;
    }

    /** Text value bytes (IA5String / UTF8String). */
    public byte[] encodeString(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Converts a hex string (the 'H dump values) into raw bytes. */
    public byte[] encodeHex(String hexValue) {
        String clean = hexValue.trim();
        int length = clean.length() / 2;
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}