package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * X.690 8.11: a SET value carries universal tag 17 (0x31), a SEQUENCE carries
 * 16 (0x30). The encoder used to emit SEQUENCE for both, so every
 * EXPLICIT-tagged SET - e.g. {@code recordExtensions [25] EXPLICIT
 * ManagementExtensions} where {@code ManagementExtensions ::= SET} - produced
 * {@code B9 { 30 ... }} where a strict decoder expects {@code B9 { 31 ... }}.
 *
 * <p>The distinction is only visible where the encoder emits a universal tag of
 * its own: the inner tag of an EXPLICIT wrapper, the per-element wrapper of a
 * {@code SEQUENCE OF <Set>}, and the record wrapper for a SET root. An IMPLICIT
 * tag replaces the universal tag outright and is unaffected.</p>
 */
class BerSetUniversalTagTest {

    private final BerEncoderService encoder = new BerEncoderService(new TlvWriter());

    private AsnField intLeaf(String name, int tag) {
        return AsnField.builder()
                .fieldName(name).fieldType("INTEGER")
                .tagNumber(tag).tagClass(BerTagClass.CONTEXT)
                .build();
    }

    @Test
    void explicitlyTaggedSetUsesUniversalSetTag() {
        AsnField setField = AsnField.builder()
                .fieldName("recordExtensions").fieldType("ManagementExtensions")
                .set(true)
                .tagNumber(4).tagClass(BerTagClass.CONTEXT).explicit(true)
                .children(List.of(intLeaf("a", 0)))
                .build();

        byte[] out = encoder.encodeRecord(List.of(setField), Map.of("recordExtensions", Map.of("a", "'1'D")));

        // 30 07  root
        //    A4 05  [4] EXPLICIT
        //       31 03  <- universal SET, was 30 before the fix
        //          80 01 01
        assertArrayEquals(new byte[]{
                0x30, 0x07,
                (byte) 0xA4, 0x05,
                0x31, 0x03,
                (byte) 0x80, 0x01, 0x01
        }, out);
    }

    @Test
    void sequenceOfSetWrapsEachElementInUniversalSetTag() {
        AsnField items = AsnField.builder()
                .fieldName("items").fieldType("SubscriptionID")
                .set(true)          // element type is a SET
                .repeated(true)     // ... the field itself is a SEQUENCE OF
                .tagNumber(1).tagClass(BerTagClass.CONTEXT)
                .children(List.of(intLeaf("a", 0)))
                .build();

        byte[] out = encoder.encodeRecord(List.of(items),
                Map.of("items", List.of(Map.of("a", "'1'D"), Map.of("a", "'2'D"))));

        // 30 0C  root
        //    A1 0A  items [1] - the collection tag stays as it is
        //       31 03 80 01 01   element 1, universal SET
        //       31 03 80 01 02   element 2, universal SET
        assertArrayEquals(new byte[]{
                0x30, 0x0C,
                (byte) 0xA1, 0x0A,
                0x31, 0x03, (byte) 0x80, 0x01, 0x01,
                0x31, 0x03, (byte) 0x80, 0x01, 0x02
        }, out);
    }

    /** Guards against over-reaching: a genuine SEQUENCE must keep tag 16. */
    @Test
    void explicitlyTaggedSequenceStillUsesUniversalSequenceTag() {
        AsnField seqField = AsnField.builder()
                .fieldName("wrapper").fieldType("Wrapper")
                .tagNumber(4).tagClass(BerTagClass.CONTEXT).explicit(true)
                .children(List.of(intLeaf("a", 0)))
                .build();

        byte[] out = encoder.encodeRecord(List.of(seqField), Map.of("wrapper", Map.of("a", "'1'D")));

        assertArrayEquals(new byte[]{
                0x30, 0x07,
                (byte) 0xA4, 0x05,
                0x30, 0x03,
                (byte) 0x80, 0x01, 0x01
        }, out);
    }

    /**
     * An IMPLICIT tag replaces the universal tag, so a SET field carrying one
     * must NOT gain an extra 0x31 - the context tag is all that is emitted.
     */
    @Test
    void implicitlyTaggedSetEmitsOnlyTheContextTag() {
        AsnField setField = AsnField.builder()
                .fieldName("s").fieldType("MySet")
                .set(true)
                .tagNumber(4).tagClass(BerTagClass.CONTEXT)   // no .explicit(true)
                .children(List.of(intLeaf("a", 0)))
                .build();

        byte[] out = encoder.encodeRecord(List.of(setField), Map.of("s", Map.of("a", "'1'D")));

        // 30 05 A4 03 80 01 01 - no inner 31, the [4] tag stands in for it.
        assertArrayEquals(new byte[]{
                0x30, 0x05,
                (byte) 0xA4, 0x03,
                (byte) 0x80, 0x01, 0x01
        }, out);
    }

    @Test
    void setRootIsWrappedInUniversalSetTag() {
        AsnStructure structure = AsnStructure.builder()
                .structureName("Rec")
                .setRoot(true)
                .fields(List.of(intLeaf("v", 0)))
                .build();

        byte[] out = encoder.encodeRecord(structure, Map.of("v", "'7'D"));

        assertArrayEquals(new byte[]{0x31, 0x03, (byte) 0x80, 0x01, 0x07}, out);
    }

    @Test
    void sequenceRootStillUsesUniversalSequenceTag() {
        AsnStructure structure = AsnStructure.builder()
                .structureName("Rec")
                .fields(List.of(intLeaf("v", 0)))
                .build();

        byte[] out = encoder.encodeRecord(structure, Map.of("v", "'7'D"));

        assertArrayEquals(new byte[]{0x30, 0x03, (byte) 0x80, 0x01, 0x07}, out);
    }
}
