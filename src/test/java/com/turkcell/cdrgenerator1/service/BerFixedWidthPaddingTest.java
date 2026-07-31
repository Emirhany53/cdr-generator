package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * X.680 49.4: a single-value size constraint is EXACT. The TAP/FCMS family
 * declares nearly every field that way and marks the justification with
 * {@code CODE}:
 *
 * <pre>
 * MOCDR ::= SEQUENCE {
 *     duration [3] IA5STRING (SIZE(10) CODE("LEFT")) OPTIONAL,
 *     imei     [19] IA5STRING (SIZE(17) CODE("LEFT")) OPTIONAL,
 *     ...
 * }
 * </pre>
 *
 * <p>The encoder wrote whatever the value happened to be, so a generated
 * FCMSTAPIN file carried {@code duration = "120"} in a 10-character field and
 * {@code imei} at 15 characters in a 17-character one - the same 36 fields short
 * in every one of its 5 records. 2933 {@code CODE("LEFT")} fields across 84 of
 * the 808 modules use this convention.</p>
 *
 * <p>Padding lives in the encoder rather than in the generator so that it also
 * covers user-supplied and AI-supplied values, not just the random fallback.</p>
 */
class BerFixedWidthPaddingTest {

    private static final int UNIVERSAL_SEQUENCE_TAG = 0x30;
    private static final int IA5_STRING_TAG = 0x16;

    private final BerEncoderService encoder =
            new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

    private AsnField field(String name, int tag, String type) {
        return AsnField.builder()
                .fieldName(name).fieldType(type)
                .tagNumber(tag).tagClass(BerTagClass.CONTEXT)
                .explicit(false)
                .build();
    }

    /** The exact defect: a 3-character value in a {@code SIZE(10)} field. */
    @Test
    void aShortValueIsPaddedOutToTheFixedSize() {
        byte[] out = encoder.encodeRecord(
                List.of(field("duration", 3, "IA5STRING (SIZE(10) CODE(\"LEFT\"))")),
                Map.of("duration", "\"120\""));

        assertArrayEquals(new byte[]{
                UNIVERSAL_SEQUENCE_TAG, 0x0C,
                (byte) 0x83, 0x0A,
                '1', '2', '0', ' ', ' ', ' ', ' ', ' ', ' ', ' '
        }, out);
    }

    /** {@code CODE("LEFT")} puts the value first and the filler behind it. */
    @Test
    void leftJustifiedPaddingGoesToTheRight() {
        byte[] out = encoder.encodeRecord(
                List.of(field("recordType", 5, "IA5STRING (SIZE(4) CODE(\"LEFT\"))")),
                Map.of("recordType", "\"AB\""));

        assertArrayEquals(new byte[]{
                UNIVERSAL_SEQUENCE_TAG, 0x06,
                (byte) 0x85, 0x04, 'A', 'B', ' ', ' '
        }, out);
    }

    /** {@code CODE("RIGHT")} reverses that - the filler leads. */
    @Test
    void rightJustifiedPaddingGoesToTheLeft() {
        byte[] out = encoder.encodeRecord(
                List.of(field("amount", 5, "IA5STRING (SIZE(4) CODE(\"RIGHT\"))")),
                Map.of("amount", "\"AB\""));

        assertArrayEquals(new byte[]{
                UNIVERSAL_SEQUENCE_TAG, 0x06,
                (byte) 0x85, 0x04, ' ', ' ', 'A', 'B'
        }, out);
    }

    /** Absent CODE still means a fixed size, and left is the schema's overwhelming default. */
    @Test
    void aFixedSizeWithoutACodeMarkerIsLeftJustified() {
        byte[] out = encoder.encodeRecord(
                List.of(field("code", 5, "IA5String (SIZE(3))")),
                Map.of("code", "\"X\""));

        assertArrayEquals(new byte[]{
                UNIVERSAL_SEQUENCE_TAG, 0x05,
                (byte) 0x85, 0x03, 'X', ' ', ' '
        }, out);
    }

    /**
     * A RANGE fixes nothing, so the value keeps its natural length. Without this
     * distinction the 807 ranged character-string fields in the schema would all
     * be blown up to their maximum.
     */
    @Test
    void aRangedSizeConstraintIsNotPadded() {
        byte[] out = encoder.encodeRecord(
                List.of(field("name", 5, "IA5String (SIZE(1..20))")),
                Map.of("name", "\"AB\""));

        assertArrayEquals(new byte[]{
                UNIVERSAL_SEQUENCE_TAG, 0x04,
                (byte) 0x85, 0x02, 'A', 'B'
        }, out);
    }

    /** A value that already fills the field is emitted untouched. */
    @Test
    void aValueThatAlreadyFillsTheFieldIsUnchanged() {
        byte[] out = encoder.encodeRecord(
                List.of(field("recordType", 5, "IA5STRING (SIZE(2) CODE(\"LEFT\"))")),
                Map.of("recordType", "\"AB\""));

        assertArrayEquals(new byte[]{
                UNIVERSAL_SEQUENCE_TAG, 0x04,
                (byte) 0x85, 0x02, 'A', 'B'
        }, out);
    }

    /**
     * An over-long value is left alone rather than silently truncated: cutting a
     * caller's data is worse than emitting it and letting validation report it.
     */
    @Test
    void anOverlongValueIsNotTruncated() {
        byte[] out = encoder.encodeRecord(
                List.of(field("recordType", 5, "IA5STRING (SIZE(2) CODE(\"LEFT\"))")),
                Map.of("recordType", "\"ABCD\""));

        assertArrayEquals(new byte[]{
                UNIVERSAL_SEQUENCE_TAG, 0x06,
                (byte) 0x85, 0x04, 'A', 'B', 'C', 'D'
        }, out);
    }

    /**
     * An OCTET STRING's SIZE counts BYTES while its text is a hex dump (two
     * characters per byte), so spaces would corrupt it. It must never be padded.
     */
    @Test
    void anOctetStringIsNeverPadded() {
        byte[] out = encoder.encodeRecord(
                List.of(field("newlineCharacter", 99, "OCTET STRING (SIZE(4) CODE(\"LEFT\"))")),
                Map.of("newlineCharacter", "\"0A0B\""));

        // 0A0B is bare hex: two content bytes, not four padded characters.
        assertArrayEquals(new byte[]{
                UNIVERSAL_SEQUENCE_TAG, 0x05,
                (byte) 0x9F, 0x63, 0x02, 0x0A, 0x0B
        }, out);
    }

    /** An INTEGER's SIZE bounds its VALUE range, so padding must not reach it. */
    @Test
    void anIntegerIsNeverPadded() {
        byte[] out = encoder.encodeRecord(
                List.of(field("subscriberType", 6, "INTEGER (SIZE(3) CODE(\"DEC\"))")),
                Map.of("subscriberType", "'1'D"));

        assertArrayEquals(new byte[]{
                UNIVERSAL_SEQUENCE_TAG, 0x03,
                (byte) 0x86, 0x01, 0x01
        }, out);
    }

    /** An EXPLICIT tag keeps its inner universal IA5String TLV, padded inside. */
    @Test
    void anExplicitlyTaggedFixedWidthFieldIsPaddedInsideTheWrapper() {
        AsnField explicitField = AsnField.builder()
                .fieldName("entity").fieldType("IA5STRING (SIZE(4) CODE(\"LEFT\"))")
                .tagNumber(1).tagClass(BerTagClass.CONTEXT)
                .explicit(true)
                .build();

        byte[] out = encoder.encodeRecord(List.of(explicitField), Map.of("entity", "\"AB\""));

        assertArrayEquals(new byte[]{
                UNIVERSAL_SEQUENCE_TAG, 0x08,
                (byte) 0xA1, 0x06,
                IA5_STRING_TAG, 0x04, 'A', 'B', ' ', ' '
        }, out);
    }
}
