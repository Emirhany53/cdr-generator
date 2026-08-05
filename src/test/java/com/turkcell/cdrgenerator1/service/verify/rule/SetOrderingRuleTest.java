package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.verify.BerFinding;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SetOrderingRuleTest {

    private final SetOrderingRule rule = new SetOrderingRule();
    private final TlvReader reader = new TlvReader();

    @Test
    void acceptsSetWithAscendingTags() {
        byte[] data = hex("3109" + "83010A" + "84010B" + "88010C"); // SET { [3] A, [4] B, [8] C }
        TlvNode node = reader.readAll(data).get(0);
        AsnField field = AsnField.builder().fieldName("mySet").set(true).build();
        NodeContext nodeContext = new NodeContext(node, field, false);
        VerificationContext context = new VerificationContext("mySet", data);

        rule.check(nodeContext, context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void reportsSetWithDescendingTags() {
        byte[] data = hex("3106" + "84010B" + "83010A"); // SET { [4] B, [3] A }
        TlvNode node = reader.readAll(data).get(0);
        AsnField field = AsnField.builder().fieldName("mySet").set(true).build();
        NodeContext nodeContext = new NodeContext(node, field, false);
        VerificationContext context = new VerificationContext("mySet", data);

        rule.check(nodeContext, context);

        assertEquals(1, context.findings().size());
        BerFinding finding = context.findings().get(0);
        assertEquals(FindingSeverity.ERROR, finding.severity());
        assertTrue(finding.message().contains("ascending"));
    }

    @Test
    void ignoresSequenceEvenIfChildrenAreOutOfOrder() {
        byte[] data = hex("3006" + "84010B" + "83010A"); // SEQUENCE { [4] B, [3] A }
        TlvNode node = reader.readAll(data).get(0);
        AsnField field = AsnField.builder().fieldName("mySeq").set(false).build();
        NodeContext nodeContext = new NodeContext(node, field, false);
        VerificationContext context = new VerificationContext("mySeq", data);

        rule.check(nodeContext, context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void ignoresCollectionWrapper() {
        byte[] data = hex("3106" + "84010B" + "83010A"); 
        TlvNode node = reader.readAll(data).get(0);
        AsnField field = AsnField.builder().fieldName("mySetOf").set(true).build();
        NodeContext nodeContext = new NodeContext(node, field, true); // collectionWrapper=true
        VerificationContext context = new VerificationContext("mySetOf", data);

        rule.check(nodeContext, context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void ignoresPrimitiveNode() {
        byte[] data = hex("020101");
        TlvNode node = reader.readAll(data).get(0);
        AsnField field = AsnField.builder().fieldName("myInt").set(true).build();
        NodeContext nodeContext = new NodeContext(node, field, false);
        VerificationContext context = new VerificationContext("myInt", data);

        rule.check(nodeContext, context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void acceptsSetWithTagsFromDifferentClassesInOrder() {
        byte[] data = hex("3106" + "02010A" + "83010B"); // UNIVERSAL [2], CONTEXT [3]
        TlvNode node = reader.readAll(data).get(0);
        AsnField field = AsnField.builder().fieldName("mySet").set(true).build();
        NodeContext nodeContext = new NodeContext(node, field, false);
        VerificationContext context = new VerificationContext("mySet", data);

        rule.check(nodeContext, context);

        assertTrue(context.findings().isEmpty());
    }

    @Test
    void reportsSetWithDifferentClassesOutOfOrder() {
        byte[] data = hex("3106" + "83010B" + "02010A"); // CONTEXT [3], UNIVERSAL [2]
        TlvNode node = reader.readAll(data).get(0);
        AsnField field = AsnField.builder().fieldName("mySet").set(true).build();
        NodeContext nodeContext = new NodeContext(node, field, false);
        VerificationContext context = new VerificationContext("mySet", data);

        rule.check(nodeContext, context);

        assertEquals(1, context.findings().size());
        BerFinding finding = context.findings().get(0);
        assertEquals(FindingSeverity.ERROR, finding.severity());
        assertTrue(finding.message().contains("ascending"));
    }

    @Test
    void skipsWhenFieldIsNull() {
        byte[] data = hex("3106" + "84010B" + "83010A"); 
        TlvNode node = reader.readAll(data).get(0);
        NodeContext nodeContext = new NodeContext(node, null, false);
        VerificationContext context = new VerificationContext("mySet", data);

        rule.check(nodeContext, context);

        assertTrue(context.findings().isEmpty());
    }

    private static byte[] hex(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
