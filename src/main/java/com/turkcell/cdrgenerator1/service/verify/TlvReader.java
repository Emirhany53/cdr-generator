package com.turkcell.cdrgenerator1.service.verify;

import com.turkcell.cdrgenerator1.exception.BerDecodingException;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Low-level BER reader: the mirror of {@link com.turkcell.cdrgenerator1.service.TlvWriter}.
 *
 * <p>The writer turns a field into Tag-Length-Value octets; this turns those
 * octets back into a tree of {@link TlvNode}. Like the writer it holds no
 * ASN.1 model knowledge at all - it does not know what a field is called or
 * what type it has - so it can be unit-tested on raw byte arrays (SRP).
 * Matching the tree against a schema is the job of the verification rules.</p>
 *
 * <p>Until now the project could write BER but not read it, which is why every
 * check on a produced file had to be done by a separate Python script. This
 * class is what lets those checks move into the application.</p>
 */
@Component
public class TlvReader {

    /** Two most significant bits of the identifier octet: the tag class. */
    private static final int CLASS_MASK = 0xC0;
    /** Constructed bit (bit 6) of the identifier octet. */
    private static final int CONSTRUCTED_BIT = 0x20;
    /** Low five bits set means "tag number continues in the following octets". */
    private static final int HIGH_TAG_MARKER = 0x1F;
    /** Continuation bit for multi-octet tag numbers and long-form lengths. */
    private static final int CONTINUATION_BIT = 0x80;
    private static final int SEVEN_BIT_MASK = 0x7F;
    private static final int SEVEN_BITS = 7;
    private static final int BYTE_MASK = 0xFF;
    private static final int BITS_PER_BYTE = 8;
    /** Length octet 0x80 exactly: the indefinite form, terminated by an EOC pair. */
    private static final int INDEFINITE_LENGTH_MARKER = 0x80;
    /** End-of-contents marker: two zero octets. */
    private static final int EOC_LENGTH = 2;
    /**
     * Longest long-form length this reader accepts. Four octets already allow a
     * 2 GB value; anything longer cannot be held in an int and, in this data
     * set, only ever means the bytes are not really BER.
     */
    private static final int MAX_LENGTH_OCTETS = 4;
    /**
     * Tag numbers above this are refused. The largest the MMTel family declares
     * is [999]; a number in the millions means the identifier octets were
     * misread and the parse has already gone wrong.
     */
    private static final int MAX_TAG_NUMBER = 1 << 24;
    /** Guards against a stack overflow on deeply nested or malformed input. */
    private static final int MAX_DEPTH = 64;

    /**
     * Reads every top-level TLV in the buffer. A CDR file is a bare
     * concatenation of records with no envelope, so this yields one node per
     * record.
     */
    public List<TlvNode> readAll(byte[] data) {
        if (Objects.isNull(data) || data.length == 0) {
            return List.of();
        }
        List<TlvNode> nodes = new ArrayList<>();
        int position = 0;
        while (position < data.length) {
            TlvNode node = readTlv(data, position, data.length, 0);
            nodes.add(node);
            position = node.end();
        }
        return List.copyOf(nodes);
    }

    /** Reads exactly one TLV starting at {@code offset}. */
    public TlvNode read(byte[] data, int offset) {
        if (Objects.isNull(data)) {
            throw new BerDecodingException("No bytes to read");
        }
        return readTlv(data, offset, data.length, 0);
    }

    /** Copies out a node's content octets. */
    public byte[] valueBytes(byte[] data, TlvNode node) {
        return Arrays.copyOfRange(data, node.valueStart(), node.valueEnd());
    }

    private TlvNode readTlv(byte[] data, int offset, int limit, int depth) {
        if (depth > MAX_DEPTH) {
            throw new BerDecodingException("Nesting deeper than " + MAX_DEPTH + " at offset " + offset);
        }
        require(data, offset, 1, limit, "identifier octet");

        int identifier = data[offset] & BYTE_MASK;
        BerTagClass tagClass = tagClassOf(identifier & CLASS_MASK);
        boolean constructed = (identifier & CONSTRUCTED_BIT) != 0;

        int cursor = offset + 1;
        int tagNumber = identifier & HIGH_TAG_MARKER;
        if (tagNumber == HIGH_TAG_MARKER) {
            tagNumber = 0;
            int octet;
            do {
                require(data, cursor, 1, limit, "tag number continuation");
                octet = data[cursor] & BYTE_MASK;
                cursor++;
                tagNumber = (tagNumber << SEVEN_BITS) | (octet & SEVEN_BIT_MASK);
                if (tagNumber > MAX_TAG_NUMBER) {
                    throw new BerDecodingException(
                            "Tag number above " + MAX_TAG_NUMBER + " at offset " + offset);
                }
            } while ((octet & CONTINUATION_BIT) != 0);
        }

        require(data, cursor, 1, limit, "length octet");
        int lengthOctet = data[cursor] & BYTE_MASK;
        cursor++;

        boolean indefinite = lengthOctet == INDEFINITE_LENGTH_MARKER;
        int declaredLength = 0;
        if (!indefinite && (lengthOctet & CONTINUATION_BIT) != 0) {
            int lengthOctetCount = lengthOctet & SEVEN_BIT_MASK;
            if (lengthOctetCount > MAX_LENGTH_OCTETS) {
                throw new BerDecodingException(
                        "Length field of " + lengthOctetCount + " octets at offset " + offset
                                + " is longer than this reader supports");
            }
            require(data, cursor, lengthOctetCount, limit, "long-form length");
            for (int i = 0; i < lengthOctetCount; i++) {
                declaredLength = (declaredLength << BITS_PER_BYTE) | (data[cursor + i] & BYTE_MASK);
            }
            cursor += lengthOctetCount;
            if (declaredLength < 0) {
                throw new BerDecodingException("Length overflows at offset " + offset);
            }
        } else if (!indefinite) {
            declaredLength = lengthOctet;
        }

        int valueStart = cursor;
        List<TlvNode> children = new ArrayList<>();
        int valueEnd;
        int end;

        if (indefinite) {
            // X.690 8.1.3.2 a): only a constructed encoding may use the
            // indefinite form, because the EOC pair has to be distinguishable
            // from content and only a constructed value is a sequence of TLVs.
            if (!constructed) {
                throw new BerDecodingException(
                        "Primitive value with indefinite length at offset " + offset);
            }
            int position = valueStart;
            while (true) {
                require(data, position, EOC_LENGTH, limit, "end-of-contents marker");
                if (data[position] == 0 && data[position + 1] == 0) {
                    break;
                }
                TlvNode child = readTlv(data, position, limit, depth + 1);
                children.add(child);
                position = child.end();
            }
            valueEnd = position;
            end = position + EOC_LENGTH;
        } else {
            valueEnd = valueStart + declaredLength;
            if (valueEnd > limit) {
                throw new BerDecodingException("Value of " + declaredLength
                        + " octets at offset " + offset + " runs past the end of its container");
            }
            end = valueEnd;
            if (constructed) {
                int position = valueStart;
                while (position < valueEnd) {
                    TlvNode child = readTlv(data, position, valueEnd, depth + 1);
                    children.add(child);
                    position = child.end();
                }
            }
        }

        return new TlvNode(tagClass, tagNumber, constructed, offset,
                valueStart, valueEnd, end, indefinite, List.copyOf(children));
    }

    private void require(byte[] data, int offset, int count, int limit, String what) {
        if (offset < 0 || offset + count > limit || offset + count > data.length) {
            throw new BerDecodingException(
                    "Truncated " + what + ": need " + count + " octet(s) at offset " + offset);
        }
    }

    private BerTagClass tagClassOf(int classBits) {
        for (BerTagClass candidate : BerTagClass.values()) {
            if (candidate.getClassBits() == classBits) {
                return candidate;
            }
        }
        throw new BerDecodingException("Unknown tag class bits " + classBits);
    }
}
