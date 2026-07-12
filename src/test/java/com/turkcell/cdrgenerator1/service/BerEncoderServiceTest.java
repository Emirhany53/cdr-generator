package com.turkcell.cdrgenerator1.service;

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

    private final BerEncoderService encoder = new BerEncoderService(new TlvWriter());

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

        byte[] out = encoder.encodeRecord(List.of(appField), Map.of("cmd", "\"Z\""));
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
