package com.turkcell.cdrgenerator1.service;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Covers B1: a CHOICE root is encoded directly, without an artificial SEQUENCE wrapper. */
class BerChoiceRootEncodingTest {

    private static final int UNIVERSAL_SEQUENCE_TAG = 0x30;
    private final BerEncoderService encoder = new BerEncoderService(new TlvWriter());

    @Test
    void choiceRootEncodesAlternativeWithoutOuterSequence() {
        AsnField inner = AsnField.builder()
                .fieldName("b").fieldType("INTEGER").tagNumber(0).tagClass(BerTagClass.CONTEXT).build();
        AsnField alternative = AsnField.builder()
                .fieldName("cmdRecord").fieldType("CmdRecord")
                .tagNumber(0).tagClass(BerTagClass.APPLICATION)
                .children(List.of(inner)).build();

        AsnStructure structure = AsnStructure.builder()
                .structureName("Sms").choiceRoot(true).fields(List.of(alternative)).build();

        byte[] out = encoder.encodeRecord(structure, Map.of("cmdRecord", Map.of("b", "'1'D")));

        // Constructed APPLICATION 0 == 0x60, content = 80 01 01. No leading 0x30.
        assertNotEquals(UNIVERSAL_SEQUENCE_TAG, out[0] & 0xFF,
                "CHOICE root must not be wrapped in a universal SEQUENCE");
        assertArrayEquals(new byte[]{0x60, 0x03, (byte) 0x80, 0x01, 0x01}, out);
    }

    @Test
    void sequenceRootStillWrapsInUniversalSequence() {
        AsnField field = AsnField.builder()
                .fieldName("v").fieldType("INTEGER").tagNumber(1).tagClass(BerTagClass.CONTEXT).build();
        AsnStructure structure = AsnStructure.builder()
                .structureName("Rec").choiceRoot(false).fields(List.of(field)).build();

        byte[] out = encoder.encodeRecord(structure, Map.of("v", "'7'D"));
        assertEquals(UNIVERSAL_SEQUENCE_TAG, out[0] & 0xFF);
    }
}
