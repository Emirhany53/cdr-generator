package com.turkcell.cdrgenerator1.service.verify;

import com.turkcell.cdrgenerator1.model.BerTagClass;

import java.util.List;

/**
 * One decoded TLV, described purely by offsets into the buffer it came from.
 *
 * <p>The node deliberately does NOT hold a copy of its bytes. A single CDR
 * record is walked by several rules in turn, and every one of them only needs
 * to know WHERE a value sits; copying each value once per node would allocate
 * the whole record again for no benefit. Use
 * {@link TlvReader#valueBytes(byte[], TlvNode)} when a rule actually needs the
 * content.</p>
 *
 * @param tagClass         UNIVERSAL / APPLICATION / CONTEXT / PRIVATE
 * @param tagNumber        the number carried by the identifier octets
 * @param constructed      true when bit 6 of the identifier is set
 * @param start            offset of the first identifier octet
 * @param valueStart       offset of the first content octet
 * @param valueEnd         offset just past the last content octet
 * @param end              offset just past the whole TLV; for an
 *                         indefinite-length node this is past the EOC pair,
 *                         so it differs from {@code valueEnd}
 * @param indefiniteLength true when the length was the 0x80 indefinite form
 * @param children         decoded children of a constructed node, empty for a
 *                         primitive one
 */
public record TlvNode(
        BerTagClass tagClass,
        int tagNumber,
        boolean constructed,
        int start,
        int valueStart,
        int valueEnd,
        int end,
        boolean indefiniteLength,
        List<TlvNode> children) {

    /** Number of content octets. */
    public int valueLength() {
        return valueEnd - valueStart;
    }

    /** Total octets occupied, identifier and length included. */
    public int totalLength() {
        return end - start;
    }

    public boolean isPrimitive() {
        return !constructed;
    }

    /** True when this node carries the given class and number. */
    public boolean hasTag(BerTagClass expectedClass, int expectedNumber) {
        return tagClass == expectedClass && tagNumber == expectedNumber;
    }

    /**
     * Short, human-readable identity used in finding messages - deliberately
     * shaped like the paths EMM prints in its rejection mails, so the two can
     * be compared without translation.
     */
    public String tagLabel() {
        return (tagClass == BerTagClass.CONTEXT ? "" : tagClass.name().charAt(0) + "-")
                + "[" + tagNumber + "]";
    }
}
