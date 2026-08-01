package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.exception.BerEncodingException;
import com.turkcell.cdrgenerator1.model.BerTagClass;
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

    /** Sign bit of a value byte; drives the two's-complement padding rules. */
    private static final int SIGN_BIT = 0x80;
    /** Two's-complement padding byte prepended to negative INTEGER values. */
    private static final int NEGATIVE_PAD_BYTE = 0xFF;
    /** BER BOOLEAN content byte for TRUE (any non-zero is valid; 0xFF is canonical). */
    private static final int BOOLEAN_TRUE_BYTE = 0xFF;
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
    /** Leading BIT STRING octet when the value occupies whole bytes (X.690 8.6.2). */
    private static final byte NO_UNUSED_BITS = 0;
    /** An OID needs at least the two arcs that share the first subidentifier. */
    private static final int MIN_OID_ARCS = 2;
    /** X.690 8.19.4: first subidentifier is {@code 40 * arc1 + arc2}. */
    private static final int FIRST_ARC_MULTIPLIER = 40;
    /** X.690 8.19.4: the leading arc of an OID is 0, 1 or 2. */
    private static final int MAX_FIRST_ARC = 2;
    /** X.690 8.5.7: first REAL contents octet selecting ISO 6093 NR3. */
    private static final byte NR3_DECIMAL_FORM = 0x03;
    /** NR3 always shows a fraction and an explicit, signed exponent. */
    private static final String NR3_FORMAT = "%.6E";

    /**
     * Builds the identifier (tag) bytes for a context-class field.
     *
     * @param tagNumber   the number from the [n] annotation
     * @param constructed true for SEQUENCE/SET or an EXPLICIT wrapper
     */
    public byte[] encodeTag(int tagNumber, boolean constructed) {
        return encodeTag(BerTagClass.CONTEXT, tagNumber, constructed);
    }

    /**
     * Builds the identifier (tag) bytes for a field of any tag class
     * (UNIVERSAL, APPLICATION, CONTEXT or PRIVATE).
     */
    public byte[] encodeTag(BerTagClass tagClass, int tagNumber, boolean constructed) {
        int firstByteClass = tagClass.getClassBits() | (constructed ? CONSTRUCTED_BIT : 0);

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

    /** Assembles a full TLV with the context tag class: tag + length + value. */
    public byte[] buildTlv(int tagNumber, boolean constructed, byte[] value) {
        return buildTlv(BerTagClass.CONTEXT, tagNumber, constructed, value);
    }

    /** Assembles a full TLV for the given tag class: tag + length + value. */
    public byte[] buildTlv(BerTagClass tagClass, int tagNumber, boolean constructed, byte[] value) {
        byte[] tag = encodeTag(tagClass, tagNumber, constructed);
        byte[] length = encodeLength(value.length);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(tag.length + length.length + value.length);
        buffer.writeBytes(tag);
        buffer.writeBytes(length);
        buffer.writeBytes(value);
        return buffer.toByteArray();
    }

    /**
     * Minimal two's-complement INTEGER value encoding.
     *
     * <p>BER decodes INTEGER content as two's complement, so the sign bit of
     * the first content byte must match the sign of the value: positive
     * values whose leading byte has the sign bit set get a 0x00 pad byte,
     * and negative values whose leading byte lacks it get a 0xFF pad byte
     * (e.g. 128 -&gt; 00 80, -129 -&gt; FF 7F).</p>
     */
    public byte[] encodeInteger(long value) {
        List<Integer> bytes = new ArrayList<>();
        long remaining = value;
        do {
            bytes.add(0, (int) (remaining & BYTE_MASK));
            remaining >>= BITS_PER_BYTE;
        } while (remaining != 0 && remaining != -1);

        boolean leadingSignBitSet = (bytes.get(0) & SIGN_BIT) != 0;
        if (value >= 0 && leadingSignBitSet) {
            bytes.add(0, 0);
        } else if (value < 0 && !leadingSignBitSet) {
            bytes.add(0, NEGATIVE_PAD_BYTE);
        }

        byte[] out = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            out[i] = (byte) (int) bytes.get(i);
        }
        return out;
    }

    /** BER BOOLEAN content: a single byte, 0x00 for FALSE and 0xFF for TRUE. */
    public byte[] encodeBoolean(boolean value) {
        return new byte[]{(byte) (value ? BOOLEAN_TRUE_BYTE : 0)};
    }

    /** Text value bytes (IA5String / UTF8String). */
    public byte[] encodeString(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * BER BIT STRING contents: a leading octet giving how many bits of the
     * final octet are unused, followed by the bit data (X.690 8.6.2).
     *
     * <p>The generator supplies whole bytes, so no bits are ever left over and
     * the leading octet is 0. Emitting the bit data alone - which is what
     * happened while BIT STRING was lumped in with the text types - produces a
     * value a decoder reads with the first data byte mistaken for the unused-bit
     * count.</p>
     */
    public byte[] encodeBitString(byte[] bitData) {
        byte[] out = new byte[bitData.length + 1];
        out[0] = NO_UNUSED_BITS;
        System.arraycopy(bitData, 0, out, 1, bitData.length);
        return out;
    }

    /**
     * BER OBJECT IDENTIFIER contents (X.690 8.19).
     *
     * <p>The first two arcs share one subidentifier, {@code 40 * arc1 + arc2};
     * every subidentifier after that is base-128, most significant group first,
     * with bit 8 set on all octets but the last. An OID written out as text
     * ("1.3.6.1.4.1.9") is NOT its own encoding - writing the characters, which
     * is what happened while OBJECT IDENTIFIER was lumped in with the text
     * types, produces something no decoder can read as an OID.</p>
     *
     * @throws BerEncodingException if the dotted form is not a valid OID
     */
    public byte[] encodeObjectIdentifier(String dottedOid) {
        String[] parts = dottedOid.trim().split("\\.");
        if (parts.length < MIN_OID_ARCS) {
            throw new BerEncodingException(
                    "OBJECT IDENTIFIER '" + dottedOid + "' needs at least two arcs");
        }
        long[] arcs = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                arcs[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new BerEncodingException(
                        "OBJECT IDENTIFIER '" + dottedOid + "' has a non-numeric arc '" + parts[i] + "'");
            }
            if (arcs[i] < 0) {
                throw new BerEncodingException(
                        "OBJECT IDENTIFIER '" + dottedOid + "' has a negative arc");
            }
        }
        // X.690 8.19.4: arc1 is 0..2, and when it is 0 or 1 arc2 cannot exceed 39,
        // because the two are packed into a single subidentifier.
        if (arcs[0] > MAX_FIRST_ARC || (arcs[0] < MAX_FIRST_ARC && arcs[1] >= FIRST_ARC_MULTIPLIER)) {
            throw new BerEncodingException(
                    "OBJECT IDENTIFIER '" + dottedOid + "' has an out-of-range leading arc pair");
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.writeBytes(encodeBase128(arcs[0] * FIRST_ARC_MULTIPLIER + arcs[1]));
        for (int i = 2; i < arcs.length; i++) {
            buffer.writeBytes(encodeBase128(arcs[i]));
        }
        return buffer.toByteArray();
    }

    /** One OID subidentifier: base-128 groups, continuation bit on all but the last. */
    private byte[] encodeBase128(long value) {
        List<Integer> groups = new ArrayList<>();
        groups.add(0, (int) (value & SEVEN_BIT_MASK));
        long remaining = value >> SEVEN_BITS;
        while (remaining > 0) {
            groups.add(0, (int) ((remaining & SEVEN_BIT_MASK) | CONTINUATION_BIT));
            remaining >>= SEVEN_BITS;
        }
        byte[] out = new byte[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            out[i] = (byte) (int) groups.get(i);
        }
        return out;
    }

    /**
     * BER REAL contents (X.690 8.5), decimal form.
     *
     * <p>Zero is a special case with NO contents octets at all (8.5.2).
     * Otherwise the first octet selects the representation - 0x03 is ISO 6093
     * NR3, the form that always carries an explicit exponent - and the decimal
     * text follows.</p>
     */
    public byte[] encodeReal(String decimalText) {
        double value;
        try {
            value = Double.parseDouble(decimalText.trim());
        } catch (NumberFormatException e) {
            throw new BerEncodingException("Value '" + decimalText + "' is not a valid REAL");
        }
        if (value == 0d) {
            return new byte[0];
        }
        byte[] text = String.format(java.util.Locale.ROOT, NR3_FORMAT, value)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[text.length + 1];
        out[0] = NR3_DECIMAL_FORM;
        System.arraycopy(text, 0, out, 1, text.length);
        return out;
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