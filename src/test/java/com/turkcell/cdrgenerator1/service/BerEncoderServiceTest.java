package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.exception.BerEncodingException;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BerEncoderServiceTest {

    private static final int UNIVERSAL_SEQUENCE_TAG = 0x30;

    private final BerEncoderService encoder = new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

    private AsnField field(String name, String type, Integer tag) {
        return AsnField.builder().fieldName(name).fieldType(type)
                .tagNumber(tag).tagClass(BerTagClass.CONTEXT).build();
    }

    @Test
    void recordIsWrappedInUniversalSequence() {
        List<AsnField> fields = List.of(field("msisdn", "IA5String", 1));
        byte[] out = encoder.encodeRecord(fields, Map.of("msisdn", "\"90\""));

        assertEquals(UNIVERSAL_SEQUENCE_TAG, out[0] & 0xFF, "record must start with SEQUENCE 0x30");
        assertEquals(out.length - 2, out[1] & 0xFF, "sequence length must cover all content");
    }

    @Test
    void nullValuesAreOmitted() {
        List<AsnField> fields = List.of(
                field("a", "IA5String", 1),
                field("b", "IA5String", 2));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("a", "\"X\"");
        record.put("b", null);

        byte[] out = encoder.encodeRecord(fields, record);
        // 30 03 | 81 01 58 : only field 'a' is present
        assertArrayEquals(new byte[]{0x30, 0x03, (byte) 0x81, 0x01, 0x58}, out);
    }

    @Test
    void implicitContextTagReplacesUniversalTag() {
        byte[] out = encoder.encodeRecord(
                List.of(field("v", "INTEGER", 5)), Map.of("v", "'7'D"));
        assertArrayEquals(new byte[]{0x30, 0x03, (byte) 0x85, 0x01, 0x07}, out);
    }

    @Test
    void untaggedLeafGetsUniversalTag() {
        byte[] intOut = encoder.encodeRecord(
                List.of(field("v", "INTEGER", null)), Map.of("v", "'7'D"));
        assertArrayEquals(new byte[]{0x30, 0x03, 0x02, 0x01, 0x07}, intOut);

        byte[] strOut = encoder.encodeRecord(
                List.of(field("s", "IA5String", null)), Map.of("s", "\"A\""));
        assertArrayEquals(new byte[]{0x30, 0x03, 0x16, 0x01, 0x41}, strOut);
    }

    @Test
    void explicitTagWrapsUniversalTlvInConstructedContextTag() {
        AsnField explicitField = AsnField.builder()
                .fieldName("s").fieldType("IA5String")
                .tagNumber(2).tagClass(BerTagClass.CONTEXT).explicit(true).build();

        byte[] out = encoder.encodeRecord(List.of(explicitField), Map.of("s", "\"A\""));
        // 30 05 | A2 03 | 16 01 41
        assertArrayEquals(new byte[]{0x30, 0x05, (byte) 0xA2, 0x03, 0x16, 0x01, 0x41}, out);
    }

    @Test
    void applicationTagClassIsHonored() {
        AsnField appField = AsnField.builder()
                .fieldName("cmd").fieldType("OCTET STRING")
                .tagNumber(0).tagClass(BerTagClass.APPLICATION).build();

        // "5A" is the hex dump of the same single byte 0x5A ('Z') the test
        // used before OCTET STRING values were required to be valid hex.
        byte[] out = encoder.encodeRecord(List.of(appField), Map.of("cmd", "\"5A\""));
        assertEquals(0x40, out[2] & 0xFF, "APPLICATION class primitive tag 0 must be 0x40");
    }

    @Test
    void booleanTextEncodesCanonicalBytes() {
        byte[] outTrue = encoder.encodeRecord(
                List.of(field("f", "BOOLEAN", 4)), Map.of("f", "\"1\""));
        assertArrayEquals(new byte[]{0x30, 0x03, (byte) 0x84, 0x01, (byte) 0xFF}, outTrue);

        byte[] outFalse = encoder.encodeRecord(
                List.of(field("f", "BOOLEAN", 4)), Map.of("f", "\"false\""));
        assertArrayEquals(new byte[]{0x30, 0x03, (byte) 0x84, 0x01, 0x00}, outFalse);
    }

    @Test
    void repeatedLeafListEncodesEachElementAsItsOwnTlv() {
        AsnField repeated = AsnField.builder()
                .fieldName("attrs").fieldType("IA5String").repeated(true)
                .tagNumber(5).tagClass(BerTagClass.CONTEXT).build();

        byte[] out = encoder.encodeRecord(List.of(repeated),
                Map.of("attrs", List.of("\"A\"", "\"BC\"")));
        // 30 09 | A5 07 | 16 01 41 | 16 02 42 43
        assertArrayEquals(new byte[]{0x30, 0x09, (byte) 0xA5, 0x07,
                0x16, 0x01, 0x41, 0x16, 0x02, 0x42, 0x43}, out);
    }

    @Test
    void scalarValueOnRepeatedFieldIsEncodedAsSingleElementList() {
        // Regression: SEQUENCE OF <primitive> receiving a plain scalar used to
        // write raw value bytes into a constructed TLV, producing invalid BER.
        AsnField repeated = AsnField.builder()
                .fieldName("attrs").fieldType("IA5String").repeated(true)
                .tagNumber(5).tagClass(BerTagClass.CONTEXT).build();

        byte[] out = encoder.encodeRecord(List.of(repeated), Map.of("attrs", "\"A\""));
        // 30 05 | A5 03 | 16 01 41 : the scalar becomes one well-formed element
        assertArrayEquals(new byte[]{0x30, 0x05, (byte) 0xA5, 0x03, 0x16, 0x01, 0x41}, out);
    }

    @Test
    void untaggedRepeatedLeafFallsBackToUniversalSequence() {
        AsnField repeated = AsnField.builder()
                .fieldName("attrs").fieldType("IA5String").repeated(true).build();

        byte[] out = encoder.encodeRecord(List.of(repeated), Map.of("attrs", List.of("\"A\"")));
        // 30 05 | 30 03 | 16 01 41
        assertArrayEquals(new byte[]{0x30, 0x05, 0x30, 0x03, 0x16, 0x01, 0x41}, out);
    }

    @Test
    void hexLiteralEncodesRawBytes() {
        byte[] out = encoder.encodeRecord(
                List.of(field("ts", "OCTET STRING", 3)), Map.of("ts", "'DEAD'H"));
        assertArrayEquals(new byte[]{0x30, 0x04, (byte) 0x83, 0x02, (byte) 0xDE, (byte) 0xAD}, out);
    }

    @Test
    void bareHexForOctetStringEncodesRawBytes() {
        byte[] out = encoder.encodeRecord(
                List.of(field("ts", "OCTET STRING", 3)), Map.of("ts", "\"DEAD\""));
        assertArrayEquals(new byte[]{0x30, 0x04, (byte) 0x83, 0x02, (byte) 0xDE, (byte) 0xAD}, out);
    }

    @Test
    void constructedFieldConcatenatesChildTlvs() {
        AsnField parent = AsnField.builder()
                .fieldName("addr").fieldType("AddressInformation")
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)
                .children(List.of(field("ton", "INTEGER", 0), field("msisdn", "IA5String", 3)))
                .build();

        Map<String, Object> record = Map.of("addr",
                Map.of("ton", "'1'D", "msisdn", "\"90\""));
        byte[] out = encoder.encodeRecord(List.of(parent), record);

        // 30 09 | A0 07 | 80 01 01 | 83 02 39 30
        assertEquals(0xA0, out[2] & 0xFF, "nested field must be constructed context tag 0");
        assertEquals(0x07, out[3] & 0xFF);
    }

    @Test
    void repeatedConstructedElementsEachGetOwnSequenceTlv() {
        AsnField repeated = AsnField.builder()
                .fieldName("items").fieldType("Inner").repeated(true)
                .tagNumber(6).tagClass(BerTagClass.CONTEXT)
                .children(List.of(field("a", "INTEGER", 0)))
                .build();

        List<Map<String, Object>> elements = List.of(
                Map.of("a", "'1'D"), Map.of("a", "'2'D"));
        byte[] out = encoder.encodeRecord(List.of(repeated), Map.of("items", elements));

        // 30 0C | A6 0A | 30 03 80 01 01 | 30 03 80 01 02
        assertArrayEquals(new byte[]{
                0x30, 0x0C, (byte) 0xA6, 0x0A,
                0x30, 0x03, (byte) 0x80, 0x01, 0x01,
                0x30, 0x03, (byte) 0x80, 0x01, 0x02}, out);
    }

    @Test
    void multipleRecordsConcatenateAsSelfDelimitingTlvs() {
        List<AsnField> fields = List.of(field("v", "INTEGER", 1));
        byte[] first = encoder.encodeRecord(fields, Map.of("v", "'1'D"));
        byte[] second = encoder.encodeRecord(fields, Map.of("v", "'200'D"));

        // Both records start with 0x30 and declare their own length, so a
        // decoder can walk the concatenated file record by record.
        assertEquals(0x30, first[0] & 0xFF);
        assertEquals(0x30, second[0] & 0xFF);
        assertEquals(first.length - 2, first[1] & 0xFF);
        assertEquals(second.length - 2, second[1] & 0xFF);
    }

    @Test
    void invalidIntegerTextThrowsBerEncodingException() {
        assertThrows(BerEncodingException.class, () -> encoder.encodeRecord(
                List.of(field("v", "INTEGER", 1)), Map.of("v", "\"abc\"")));
    }

    @Test
    void invalidBooleanTextThrowsBerEncodingException() {
        assertThrows(BerEncodingException.class, () -> encoder.encodeRecord(
                List.of(field("f", "BOOLEAN", 1)), Map.of("f", "\"maybe\"")));
    }

    @Test
    void malformedHexForOctetStringThrowsInsteadOfSilentlyMisencoding() {
        // Regression: an OCTET STRING's text is always a hex dump by this
        // codebase's convention. A value that isn't valid hex (here, an odd
        // number of characters) used to fall through to encodeString and get
        // written as raw UTF-8 bytes with no error at all - quietly wrong BER
        // instead of a caught mistake.
        BerEncodingException ex = assertThrows(BerEncodingException.class,
                () -> encoder.encodeRecord(
                        List.of(field("ts", "OCTET STRING", 3)), Map.of("ts", "\"ABC\"")));
        assertTrue(ex.getMessage().contains("ABC"));
    }

    @Test
    void emptyOctetStringTextEncodesAsZeroLengthContent() {
        byte[] out = encoder.encodeRecord(
                List.of(field("ts", "OCTET STRING", 3)), Map.of("ts", "\"\""));
        assertArrayEquals(new byte[]{0x30, 0x02, (byte) 0x83, 0x00}, out);
    }

    /**
     * X.690 8.6.2: a BIT STRING's first contents octet is the number of unused
     * bits in the final octet. BIT STRING used to fall through to the text
     * types, so the value went out as raw characters with no such octet - a
     * value no decoder can read back. IMPLICIT tagging does not rescue it,
     * because a context tag replaces the TAG, not the CONTENTS rule.
     */
    @Test
    void aBitStringCarriesTheLeadingUnusedBitCount() {
        byte[] out = encoder.encodeRecord(
                List.of(field("flags", "BIT STRING", 3)), Map.of("flags", "\"DEAD\""));
        // 30 05 | 83 03 | 00 DE AD  - the 0x00 is the unused-bit count
        assertArrayEquals(new byte[]{
                0x30, 0x05, (byte) 0x83, 0x03, 0x00, (byte) 0xDE, (byte) 0xAD}, out);
    }

    /** An untagged BIT STRING must carry universal tag 3, not OCTET STRING's 4. */
    @Test
    void anUntaggedBitStringUsesUniversalTagThree() {
        byte[] out = encoder.encodeRecord(
                List.of(field("flags", "BIT STRING", null)), Map.of("flags", "\"FF\""));
        assertArrayEquals(new byte[]{0x30, 0x04, 0x03, 0x02, 0x00, (byte) 0xFF}, out);
    }

    /**
     * The restricted character-string types all collapsed to OCTET STRING (4).
     * That is only visible where a universal tag is actually emitted - here an
     * untagged field.
     */
    @Test
    void restrictedCharacterStringsKeepTheirOwnUniversalTag() {
        assertEquals(25, encoder.encodeRecord(
                List.of(field("s", "GraphicString", null)), Map.of("s", "\"A\""))[2] & 0xFF);
        assertEquals(19, encoder.encodeRecord(
                List.of(field("s", "PrintableString", null)), Map.of("s", "\"A\""))[2] & 0xFF);
        assertEquals(18, encoder.encodeRecord(
                List.of(field("s", "NumericString", null)), Map.of("s", "\"1\""))[2] & 0xFF);
        assertEquals(26, encoder.encodeRecord(
                List.of(field("s", "VisibleString", null)), Map.of("s", "\"A\""))[2] & 0xFF);
        // GeneralizedTime must not be swallowed by the GeneralString prefix.
        assertEquals(24, encoder.encodeRecord(
                List.of(field("s", "GeneralizedTime", null)), Map.of("s", "\"20260101\""))[2] & 0xFF);
        assertEquals(27, encoder.encodeRecord(
                List.of(field("s", "GeneralString", null)), Map.of("s", "\"A\""))[2] & 0xFF);
    }

    /**
     * X.690 8.19: the first two arcs are packed into one subidentifier
     * (40*arc1 + arc2) and every later arc is base-128 with a continuation bit.
     * The dotted text is NOT its own encoding - while OBJECT IDENTIFIER fell
     * through to the text types those characters went out verbatim under OCTET
     * STRING's tag 4, so both the tag and the contents were wrong. All 6 such
     * fields in the schema are untagged, so both errors were visible.
     *
     * <p>Values below are the standard published encodings.</p>
     */
    @Test
    void objectIdentifierIsEncodedAsPackedArcs() {
        // 1.2.840.113549 (RSA) -> 2A 86 48 86 F7 0D, under universal tag 6.
        byte[] out = encoder.encodeRecord(
                List.of(field("identifier", "OBJECT IDENTIFIER", null)),
                Map.of("identifier", "\"1.2.840.113549\""));
        assertArrayEquals(new byte[]{
                0x30, 0x08, 0x06, 0x06,
                0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D}, out);
    }

    @Test
    void objectIdentifierPacksTheLeadingArcPair() {
        // 2.5.4.3 -> 55 04 03: 2*40+5 = 85 = 0x55.
        byte[] out = encoder.encodeRecord(
                List.of(field("identifier", "OBJECT IDENTIFIER", null)),
                Map.of("identifier", "\"2.5.4.3\""));
        assertArrayEquals(new byte[]{0x30, 0x05, 0x06, 0x03, 0x55, 0x04, 0x03}, out);
    }

    @Test
    void malformedObjectIdentifierThrows() {
        // Leading arc must be 0..2, and arc2 < 40 unless arc1 is 2.
        for (String bad : new String[] {"3.1.1", "0.40.1", "7", "a.b"}) {
            assertThrows(BerEncodingException.class,
                    () -> encoder.encodeRecord(
                            List.of(field("identifier", "OBJECT IDENTIFIER", null)),
                            Map.of("identifier", "\"" + bad + "\"")),
                    "should have rejected " + bad);
        }
    }

    /** X.690 8.5.2: REAL zero carries NO contents octets at all. */
    @Test
    void realZeroHasNoContentsOctets() {
        byte[] out = encoder.encodeRecord(
                List.of(field("v", "REAL", null)), Map.of("v", "\"0\""));
        assertArrayEquals(new byte[]{0x30, 0x02, 0x09, 0x00}, out);
    }

    /** A non-zero REAL leads with the octet selecting the decimal (NR3) form. */
    @Test
    void nonZeroRealUsesTheDecimalForm() {
        byte[] out = encoder.encodeRecord(
                List.of(field("v", "REAL", null)), Map.of("v", "\"1.5\""));
        assertEquals(0x09, out[2] & 0xFF, "universal tag REAL");
        assertEquals(0x03, out[4] & 0xFF, "first contents octet selects ISO 6093 NR3");
    }

    /**
     * RealUnit in GPRS-Charging-Extensions is a SEQUENCE of two INTEGERs, not an
     * ASN.1 REAL. A prefix match would misclassify it and destroy 21 fields.
     */
    @Test
    void aTypeMerelyNamedLikeRealIsNotTreatedAsReal() {
        assertEquals(BerPrimitiveType.STRING, BerPrimitiveType.fromTypeExpression("RealUnit"));
        assertEquals(BerPrimitiveType.REAL, BerPrimitiveType.fromTypeExpression("REAL"));
        assertEquals(BerPrimitiveType.REAL, BerPrimitiveType.fromTypeExpression("REAL (1..5)"));
    }

    /**
     * Two fields may share a name under different tags (IMSTCELLCDRS declares
     * eventTypeContentLength at both [7] and [41]). The encoder used to look
     * both up by name and so wrote ONE value under BOTH tags; it now uses the
     * same per-field keys CdrRecordBuilder wrote them under.
     */
    @Test
    void twoFieldsSharingANameEncodeTheirOwnValues() {
        List<AsnField> fields = List.of(
                field("eventTypeContentLength", "IA5String", 7),
                field("eventTypeContentLength", "IA5String", 41));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("eventTypeContentLength", "\"A\"");
        record.put("eventTypeContentLength#1", "\"B\"");

        byte[] out = encoder.encodeRecord(fields, record);

        // 30 06 | 87 01 41 ('A') | 9F 29 01 42 ('B' under tag 41)
        assertArrayEquals(new byte[]{
                0x30, 0x07,
                (byte) 0x87, 0x01, 0x41,
                (byte) 0x9F, 0x29, 0x01, 0x42}, out);
    }

    @Test
    void constructedFieldWithScalarValueThrows() {
        AsnField parent = AsnField.builder()
                .fieldName("addr").fieldType("AddressInformation")
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)
                .children(List.of(field("ton", "INTEGER", 0)))
                .build();

        BerEncodingException ex = assertThrows(BerEncodingException.class,
                () -> encoder.encodeRecord(List.of(parent), Map.of("addr", "\"oops\"")));
        assertTrue(ex.getMessage().contains("addr"));
    }
}
