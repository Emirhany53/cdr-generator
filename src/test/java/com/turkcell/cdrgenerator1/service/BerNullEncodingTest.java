package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * X.690 8.8: the contents octets of a NULL value are ABSENT - a NULL always
 * encodes with length 0. It is a pure presence marker; the tag alone carries
 * the meaning.
 *
 * <p>NULL had no {@link BerPrimitiveType} constant, so it fell through to
 * {@code STRING} and was encoded with whatever placeholder the generator had
 * produced for the field. In a real generated MMTel record that produced
 * {@code mediaInitiatorFlag [3] NULL} as {@code 83 01 30} (one content byte)
 * and {@code iMSEmergencyIndicator [52] NULL} as eight bytes of random text,
 * where an EMM-accepted reference capture has zero-length in the same places.
 * EMM rejected the record with:
 *
 * <pre>
 * Invalid length 284 of field "MMTelChargingDataTypes.MMTelServiceRecord
 *     .mMTelRecord.list-Of-SDP-Media-Components.[0]"
 * </pre>
 *
 * which is exactly the path of the first malformed NULL - element [0] of
 * list-Of-SDP-Media-Components contains {@code mediaInitiatorFlag [3]}.
 *
 * <p>2203 NULL fields across 47 of the 808 schema modules were affected, so
 * this was never MMTel-specific.</p>
 */
class BerNullEncodingTest {

    private final BerEncoderService encoder = new BerEncoderService(new TlvWriter());

    private AsnField nullField(String name, Integer tag, boolean explicit) {
        return AsnField.builder()
                .fieldName(name).fieldType("NULL")
                .tagNumber(tag).tagClass(tag == null ? null : BerTagClass.CONTEXT)
                .explicit(explicit)
                .build();
    }

    /**
     * The exact shape EMM rejected: an IMPLICIT context-tagged NULL must be
     * {@code 83 00}, matching the reference capture's zero-length [3].
     */
    @Test
    void implicitlyTaggedNullHasNoContentOctets() {
        byte[] out = encoder.encodeRecord(List.of(nullField("mediaInitiatorFlag", 3, false)),
                Map.of("mediaInitiatorFlag", "anything the generator produced"));

        assertArrayEquals(new byte[]{0x30, 0x02, (byte) 0x83, 0x00}, out);
    }

    /** A high tag number still encodes with a zero length (iMSEmergencyIndicator [52]). */
    @Test
    void highTaggedNullHasNoContentOctets() {
        byte[] out = encoder.encodeRecord(List.of(nullField("iMSEmergencyIndicator", 52, false)),
                Map.of("iMSEmergencyIndicator", "2Q9OBWEY"));

        // 9F 34 = context, primitive, high-tag form for 52; then length 00.
        assertArrayEquals(new byte[]{
                0x30, 0x03,
                (byte) 0x9F, 0x34, 0x00
        }, out);
    }

    /** An untagged NULL falls back to its own universal tag, 5. */
    @Test
    void untaggedNullUsesUniversalTagFiveAndZeroLength() {
        byte[] out = encoder.encodeRecord(List.of(nullField("flag", null, false)),
                Map.of("flag", "x"));

        assertArrayEquals(new byte[]{0x30, 0x02, 0x05, 0x00}, out);
    }

    /** An EXPLICIT wrapper around a NULL contains the bare universal NULL TLV. */
    @Test
    void explicitlyTaggedNullWrapsAZeroLengthUniversalNull() {
        byte[] out = encoder.encodeRecord(List.of(nullField("flag", 7, true)),
                Map.of("flag", "x"));

        // A7 02 { 05 00 }
        assertArrayEquals(new byte[]{
                0x30, 0x04,
                (byte) 0xA7, 0x02,
                0x05, 0x00
        }, out);
    }

    /**
     * A NULL field that was never populated stays absent entirely - the fix must
     * not turn an unset OPTIONAL into a present zero-length field.
     */
    @Test
    void anAbsentNullFieldIsStillOmitted() {
        byte[] out = encoder.encodeRecord(List.of(nullField("flag", 3, false)),
                new java.util.HashMap<>());

        assertArrayEquals(new byte[]{0x30, 0x00}, out);
    }

    /**
     * Guard against over-matching: a type whose NAME merely begins with "NULL"
     * is not the NULL type and must keep its content.
     */
    @Test
    void aTypeNameStartingWithNullIsNotTreatedAsTheNullType() {
        assertEquals(BerPrimitiveType.STRING,
                BerPrimitiveType.fromTypeExpression("NullableCount"));
        assertEquals(BerPrimitiveType.NULL, BerPrimitiveType.fromTypeExpression("NULL"));
    }
}
