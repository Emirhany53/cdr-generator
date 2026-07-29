package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression cover for the nested-CHOICE encoding bug that made
 * {@code MMTelChargingDataTypes} records unreadable.
 *
 * <p>ASN.1 under test (trimmed to the failing path):</p>
 * <pre>
 * MMTelRecord ::= SET { nodeAddress [4] EXPLICIT NodeAddress OPTIONAL }
 * NodeAddress ::= CHOICE { iPAddress [0] EXPLICIT IPAddress, domainName [1] GraphicString }
 * IPAddress   ::= CHOICE { iPBinaryAddress IPBinaryAddress, ... }
 * IPBinaryAddress ::= CHOICE { iPBinV4Address [0] OCTET STRING (SIZE(4)), ... }
 * </pre>
 *
 * <p>Before the fix the encoder emitted {@code A4 08 30 06 80 04 ...}: a
 * universal SEQUENCE (0x30) stood where the {@code [0] EXPLICIT} alternative
 * tag (0xA0) belongs. A decoder opens [4], looks for one of NodeAddress's
 * alternatives, finds a SEQUENCE instead and rejects the record.</p>
 */
class BerNestedChoiceEncodingTest {

    private static final int UNIVERSAL_SEQUENCE_TAG = 0x30;

    private final BerEncoderService encoder = new BerEncoderService(new TlvWriter());

    /** Builds the nodeAddress -> iPAddress -> iPBinaryAddress -> iPBinV4Address chain. */
    private AsnField nodeAddressField() {
        AsnField leaf = AsnField.builder()
                .fieldName("iPBinV4Address").fieldType("OCTET STRING")
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)
                .build();

        // IPBinaryAddress: a CHOICE whose alternative carries the [0] tag.
        AsnField binaryAddress = AsnField.builder()
                .fieldName("iPBinaryAddress").fieldType("IPBinaryAddress")
                .choice(true)
                .children(List.of(leaf))
                .build();

        // IPAddress: a CHOICE reached through an untagged alternative.
        AsnField ipAddress = AsnField.builder()
                .fieldName("iPAddress").fieldType("IPAddress")
                .choice(true)
                .tagNumber(0).tagClass(BerTagClass.CONTEXT).explicit(true)
                .children(List.of(binaryAddress))
                .build();

        return AsnField.builder()
                .fieldName("nodeAddress").fieldType("NodeAddress")
                .choice(true)
                .tagNumber(4).tagClass(BerTagClass.CONTEXT).explicit(true)
                .children(List.of(ipAddress))
                .build();
    }

    private Map<String, Object> nodeAddressValue(String hexIpv4) {
        return Map.of("nodeAddress",
                Map.of("iPAddress",
                        Map.of("iPBinaryAddress",
                                Map.of("iPBinV4Address", hexIpv4))));
    }

    @Test
    void explicitlyTaggedChoiceKeepsAlternativeTagInsteadOfSyntheticSequence() {
        byte[] out = encoder.encodeRecord(List.of(nodeAddressField()), nodeAddressValue("0ADB16C5"));

        // Record wrapper (SET/SEQUENCE root) + A4 08 A0 06 80 04 0A DB 16 C5
        byte[] expectedField = {
                (byte) 0xA4, 0x08,
                (byte) 0xA0, 0x06,
                (byte) 0x80, 0x04, 0x0A, (byte) 0xDB, 0x16, (byte) 0xC5
        };

        byte[] fieldBytes = new byte[expectedField.length];
        System.arraycopy(out, out.length - expectedField.length, fieldBytes, 0, expectedField.length);

        assertArrayEquals(expectedField, fieldBytes,
                "A tagged CHOICE must encode as A4 { A0 { 80 ... } }, never A4 { 30 { 80 ... } }");
    }

    @Test
    void wholeRecordMatchesTheSpecifiedEncoding() {
        byte[] out = encoder.encodeRecord(List.of(nodeAddressField()), nodeAddressValue("0ADB16C5"));

        // 30 0A          root SEQUENCE
        //    A4 08       nodeAddress [4] EXPLICIT
        //       A0 06    iPAddress [0] EXPLICIT  <- was 30 06 before the fix
        //          80 04 0A DB 16 C5   iPBinV4Address [0]
        assertArrayEquals(new byte[]{
                0x30, 0x0A,
                (byte) 0xA4, 0x08,
                (byte) 0xA0, 0x06,
                (byte) 0x80, 0x04, 0x0A, (byte) 0xDB, 0x16, (byte) 0xC5
        }, out);

        assertEquals(UNIVERSAL_SEQUENCE_TAG, out[0] & 0xFF, "only the root carries a universal SEQUENCE");
    }

    /** A CHOICE without any tag of its own contributes no wrapper at all. */
    @Test
    void untaggedChoiceEmitsAlternativeDirectly() {
        AsnField leaf = AsnField.builder()
                .fieldName("iPBinV4Address").fieldType("OCTET STRING")
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)
                .build();
        AsnField untaggedChoice = AsnField.builder()
                .fieldName("addr").fieldType("IPBinaryAddress")
                .choice(true)
                .children(List.of(leaf))
                .build();

        byte[] out = encoder.encodeRecord(List.of(untaggedChoice),
                Map.of("addr", Map.of("iPBinV4Address", "0ADB16C5")));

        // Root SEQUENCE 30 06, then the alternative straight through: 80 04 ...
        assertArrayEquals(new byte[]{
                0x30, 0x06,
                (byte) 0x80, 0x04, 0x0A, (byte) 0xDB, 0x16, (byte) 0xC5
        }, out);
    }

    /** Guards the fix from over-reaching: a real SEQUENCE still gets its wrapper. */
    @Test
    void explicitlyTaggedSequenceStillGetsUniversalSequenceWrapper() {
        AsnField inner = AsnField.builder()
                .fieldName("a").fieldType("INTEGER")
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)
                .build();
        AsnField sequence = AsnField.builder()
                .fieldName("wrapper").fieldType("Wrapper")
                .tagNumber(4).tagClass(BerTagClass.CONTEXT).explicit(true)
                .children(List.of(inner))
                .build();

        byte[] out = encoder.encodeRecord(List.of(sequence), Map.of("wrapper", Map.of("a", "'1'D")));

        // 30 07 A4 05 30 03 80 01 01 - the inner 0x30 is correct here.
        assertArrayEquals(new byte[]{
                0x30, 0x07,
                (byte) 0xA4, 0x05,
                0x30, 0x03,
                (byte) 0x80, 0x01, 0x01
        }, out);
    }

    // -----------------------------------------------------------------
    // SEQUENCE OF <CHOICE>: Charging-Function-Address.ccf ::= SEQUENCE OF
    // NodeAddress. Same synthetic-SEQUENCE bug, one bug per list element
    // instead of once for a scalar field - fixed in encodeRepeated().
    // -----------------------------------------------------------------

    /** ccf [0] SEQUENCE OF NodeAddress, IMPLICIT (module default), 2 elements. */
    private AsnField ccfField() {
        AsnField leaf = AsnField.builder()
                .fieldName("iPBinV4Address").fieldType("OCTET STRING")
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)
                .build();
        AsnField ipAddress = AsnField.builder()
                .fieldName("iPAddress").fieldType("IPAddress")
                .choice(true)
                .tagNumber(0).tagClass(BerTagClass.CONTEXT).explicit(true)
                .children(List.of(leaf))
                .build();

        return AsnField.builder()
                .fieldName("ccf").fieldType("NodeAddress")
                .choice(true)          // element type IS a CHOICE
                .repeated(true)        // ... but the FIELD is a collection
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)   // implicit, no .explicit(true)
                .children(List.of(ipAddress))
                .build();
    }

    @Test
    void sequenceOfChoiceKeepsEachAlternativeTagWithoutPerElementSequence() {
        List<Object> elements = List.of(
                Map.of("iPAddress", Map.of("iPBinV4Address", "0ADB16C5")),
                Map.of("iPAddress", Map.of("iPBinV4Address", "C0A80101"))
        );

        byte[] out = encoder.encodeRecord(List.of(ccfField()), Map.of("ccf", elements));

        // 30 12                root SEQUENCE
        //    A0 10              ccf [0] IMPLICIT (collection tag, kept as-is)
        //       A0 06              element 1: iPAddress [0] EXPLICIT - no 0x30 here
        //          80 04 0A DB 16 C5
        //       A0 06              element 2
        //          80 04 C0 A8 01 01
        assertArrayEquals(new byte[]{
                0x30, 0x12,
                (byte) 0xA0, 0x10,
                (byte) 0xA0, 0x06, (byte) 0x80, 0x04, 0x0A, (byte) 0xDB, 0x16, (byte) 0xC5,
                (byte) 0xA0, 0x06, (byte) 0x80, 0x04, (byte) 0xC0, (byte) 0xA8, 0x01, 0x01
        }, out);
    }

    /** The collection's own outer tag must still behave like a normal container. */
    @Test
    void sequenceOfChoiceOuterTagIsNotSuppressed() {
        byte[] out = encoder.encodeRecord(List.of(ccfField()),
                Map.of("ccf", List.of(Map.of("iPAddress", Map.of("iPBinV4Address", "0ADB16C5")))));

        // The outer A0 must be present - only the per-element 0x30 was the bug.
        assertEquals(0xA0, out[2] & 0xFF, "the collection's own [0] tag must not be dropped");
    }

    /** Sanity check: a genuine SEQUENCE OF <SEQUENCE> is unaffected by this fix. */
    @Test
    void sequenceOfSequenceStillWrapsEachElement() {
        AsnField inner = AsnField.builder()
                .fieldName("a").fieldType("INTEGER")
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)
                .build();
        AsnField sequenceOfSequence = AsnField.builder()
                .fieldName("items").fieldType("Item")
                .repeated(true)
                .tagNumber(1).tagClass(BerTagClass.CONTEXT)
                .children(List.of(inner))
                .build();

        byte[] out = encoder.encodeRecord(List.of(sequenceOfSequence),
                Map.of("items", List.of(Map.of("a", "'1'D"), Map.of("a", "'2'D"))));

        // A1 0A  items [0]                A1 -> tag 1, constructed
        //    30 03 80 01 01                  element 1, still SEQUENCE-wrapped
        //    30 03 80 01 02                  element 2, still SEQUENCE-wrapped
        assertArrayEquals(new byte[]{
                0x30, 0x0C,
                (byte) 0xA1, 0x0A,
                0x30, 0x03, (byte) 0x80, 0x01, 0x01,
                0x30, 0x03, (byte) 0x80, 0x01, 0x02
        }, out);
    }
}
