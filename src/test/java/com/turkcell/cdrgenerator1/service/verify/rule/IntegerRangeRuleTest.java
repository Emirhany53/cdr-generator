package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntegerRangeRuleTest {

    private IntegerRangeRule rule;
    private TlvReader reader;

    @BeforeEach
    void setUp() {
        rule = new IntegerRangeRule(new AsnSizeExtractor());
        reader = new TlvReader();
    }

    private static byte[] hex(String hex) {
        hex = hex.replaceAll("\\s+", "");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private VerificationContext createContext(byte[] data) {
        VerificationContext context = new VerificationContext("TestStruct", data);
        context.push("testField");
        return context;
    }

    @Test
    void acceptsValueWithinRange() {
        byte[] data = hex("80 02 01 F4");
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);
        AsnField field = AsnField.builder().fieldName("testField").fieldType("INTEGER (0..999)").build();

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, field, false), context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void reportsValueAboveMax() {
        byte[] data = hex("80 02 03 E8");
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);
        AsnField field = AsnField.builder().fieldName("testField").fieldType("INTEGER (0..999)").build();

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, field, false), context);

        assertEquals(1, context.findings().size());
        assertEquals(FindingSeverity.ERROR, context.findings().get(0).severity());
        assertTrue(context.findings().get(0).message().contains("outside INTEGER range"));
    }

    @Test
    void reportsValueBelowMin() {
        byte[] data = hex("80 01 FF"); // -1
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);
        AsnField field = AsnField.builder().fieldName("testField").fieldType("INTEGER (0..999)").build();

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, field, false), context);

        assertEquals(1, context.findings().size());
        assertTrue(context.findings().get(0).message().contains("Value -1 outside"));
    }

    @Test
    void acceptsBoundaryMin() {
        byte[] data = hex("80 01 00");
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);
        AsnField field = AsnField.builder().fieldName("testField").fieldType("INTEGER (0..999)").build();

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, field, false), context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void acceptsBoundaryMax() {
        byte[] data = hex("80 02 03 E7");
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);
        AsnField field = AsnField.builder().fieldName("testField").fieldType("INTEGER (0..999)").build();

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, field, false), context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void skipsFieldWithoutIntegerRange() {
        byte[] data = hex("80 01 00");
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);
        AsnField field = AsnField.builder().fieldName("testField").fieldType("OCTET STRING (SIZE(1..10))").build();

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, field, false), context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void skipsConstructedNode() {
        byte[] data = hex("A0 03 81 01 00");
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);
        AsnField field = AsnField.builder().fieldName("testField").fieldType("INTEGER (0..999)").build();

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, field, false), context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void skipsNullField() {
        byte[] data = hex("80 01 00");
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, null, false), context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void skipsCollectionWrapper() {
        byte[] data = hex("80 01 00");
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);
        AsnField field = AsnField.builder().fieldName("testField").fieldType("INTEGER (0..999)").build();

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, field, true), context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void handlesNegativeRange() {
        byte[] data = hex("80 01 80"); // -128
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);
        AsnField field = AsnField.builder().fieldName("testField").fieldType("INTEGER (-128..127)").build();

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, field, false), context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void reportsLargeValueOutOfRange() {
        byte[] data = hex("80 05 3C A1 A2 6E 43");
        List<TlvNode> nodes = reader.readAll(data);
        TlvNode node = nodes.get(0);
        AsnField field = AsnField.builder().fieldName("testField").fieldType("INTEGER (0..999)").build();

        VerificationContext context = createContext(data);
        rule.check(new NodeContext(node, field, false), context);

        assertEquals(1, context.findings().size());
        assertTrue(context.findings().get(0).message().contains("outside INTEGER range"));
    }
}
