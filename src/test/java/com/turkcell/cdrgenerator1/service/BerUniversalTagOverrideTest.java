package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * A type may re-tag a primitive into the UNIVERSAL class:
 * {@code GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String} encodes its
 * VALUE the way an IA5String does, but the tag on the wire must be 25
 * (GraphicString), not 22 (IA5String).
 *
 * <p>The resolver strips every leading tag while chasing the alias chain down
 * to a bare primitive name, so before {@code AsnField.universalTagOverride}
 * existed the override was silently lost and this encoder emitted 22. Comparing
 * our output against two EMM-accepted MMTel reference captures showed them
 * carrying 25 in exactly the five places this type is reached through a
 * {@code SEQUENCE OF} - {@code list-Of-SDP-Media-Components},
 * {@code list-Of-Early-SDP-Media-Components}, {@code listOfReasonHeader} and the
 * two nested {@code sDP-Media-Descriptions}/{@code sDP-Session-Description}
 * fields - which is what made a strict decoder reject the record.</p>
 *
 * <p>The override only matters where the encoder emits a universal tag of its
 * own: an untagged leaf, the inner tag of an EXPLICIT wrapper, and each element
 * of a {@code SEQUENCE OF <primitive>}. An IMPLICIT context tag replaces the
 * universal tag outright, so such fields must stay byte-identical - the last
 * test here pins that down.</p>
 */
class BerUniversalTagOverrideTest {

    private static final int GRAPHIC_STRING = 25;

    private final BerEncoderService encoder = new BerEncoderService(new TlvWriter());

    /** {@code sDP-Session-Description [4] SEQUENCE OF GraphicStringImp} */
    @Test
    void everyElementOfASequenceOfRetaggedPrimitiveCarriesTheOverriddenTag() {
        AsnField field = AsnField.builder()
                .fieldName("sDP-Session-Description").fieldType("IA5String")
                .repeated(true)
                .tagNumber(4).tagClass(BerTagClass.CONTEXT)
                .universalTagOverride(GRAPHIC_STRING)
                .build();

        byte[] out = encoder.encodeRecord(List.of(field),
                Map.of("sDP-Session-Description", List.of("ab", "cd")));

        // 30 0A          root SEQUENCE
        //    A4 08       [4] IMPLICIT, constructed (the SEQUENCE OF collection)
        //       19 02 61 62   <- universal 25 (GraphicString), was 22 before the fix
        //       19 02 63 64
        assertArrayEquals(new byte[]{
                0x30, 0x0A,
                (byte) 0xA4, 0x08,
                0x19, 0x02, 0x61, 0x62,
                0x19, 0x02, 0x63, 0x64
        }, out);
    }

    /** The inner tag of an EXPLICIT wrapper must honour the override too. */
    @Test
    void explicitWrapperInnerTagUsesTheOverride() {
        AsnField field = AsnField.builder()
                .fieldName("reasonHeader").fieldType("IA5String")
                .tagNumber(7).tagClass(BerTagClass.CONTEXT).explicit(true)
                .universalTagOverride(GRAPHIC_STRING)
                .build();

        byte[] out = encoder.encodeRecord(List.of(field), Map.of("reasonHeader", "ab"));

        // 30 06 / A7 04 [7] EXPLICIT / 19 02 61 62 <- inner universal tag 25
        assertArrayEquals(new byte[]{
                0x30, 0x06,
                (byte) 0xA7, 0x04,
                0x19, 0x02, 0x61, 0x62
        }, out);
    }

    /**
     * Without an override nothing changes: the tag still comes from the resolved
     * primitive type. This is the regression guard for the other 797 modules,
     * where no type uses the {@code [UNIVERSAL n]} form at all.
     */
    @Test
    void aFieldWithoutAnOverrideKeepsItsPrimitiveTypeTag() {
        AsnField field = AsnField.builder()
                .fieldName("plain").fieldType("IA5String")
                .repeated(true)
                .tagNumber(4).tagClass(BerTagClass.CONTEXT)
                .build();

        byte[] out = encoder.encodeRecord(List.of(field), Map.of("plain", List.of("ab")));

        // 16 (0x16) = universal 22, IA5String's own tag.
        assertArrayEquals(new byte[]{
                0x30, 0x06,
                (byte) 0xA4, 0x04,
                0x16, 0x02, 0x61, 0x62
        }, out);
    }

    /**
     * An IMPLICIT context tag replaces the universal tag outright, so a scalar
     * field like {@code sDP-Media-Name [0] GraphicStringImp} must encode exactly
     * as before - the override must NOT leak into the context tag.
     */
    @Test
    void animplicitContextTagIsUnaffectedByTheOverride() {
        AsnField withOverride = AsnField.builder()
                .fieldName("sDP-Media-Name").fieldType("IA5String")
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)
                .universalTagOverride(GRAPHIC_STRING)
                .build();

        byte[] out = encoder.encodeRecord(List.of(withOverride), Map.of("sDP-Media-Name", "ab"));

        // 80 02 61 62 - the context tag [0], not 25.
        assertArrayEquals(new byte[]{
                0x30, 0x04,
                (byte) 0x80, 0x02, 0x61, 0x62
        }, out);
    }
}
