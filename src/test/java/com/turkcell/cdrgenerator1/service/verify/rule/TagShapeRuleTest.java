package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TagShapeRuleTest {

    private final TagShapeRule rule = new TagShapeRule();
    private final TlvReader reader = new TlvReader();

    private static byte[] hex(String text) {
        String clean = text.replaceAll("\\s+", "");
        byte[] data = new byte[clean.length() / 2];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return data;
    }

    @Test
    void acceptsMatchingContextTag() {
        byte[] data = hex("83 01 AA"); // CONTEXT 3, Primitive, len 1
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldName("field1").tagNumber(3).tagClass(BerTagClass.CONTEXT).build();
        VerificationContext context = new VerificationContext("testStruct", data);
        
        rule.check(new NodeContext(node, field, false), context);
        
        assertThat(context.findings()).isEmpty();
    }

    @Test
    void reportsTagNumberMismatch() {
        byte[] data = hex("85 01 AA"); // CONTEXT 5, Primitive, len 1
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldName("field1").tagNumber(3).build();
        VerificationContext context = new VerificationContext("testStruct", data);
        
        rule.check(new NodeContext(node, field, false), context);
        
        assertThat(context.findings()).hasSize(1);
        assertThat(context.findings().get(0).severity()).isEqualTo(FindingSeverity.ERROR);
        assertThat(context.findings().get(0).message()).contains("Expected tag CONTEXT [3] but found");
    }

    @Test
    void reportsTagClassMismatch() {
        byte[] data = hex("83 01 AA"); // CONTEXT 3
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldName("field1").tagNumber(3).tagClass(BerTagClass.APPLICATION).build();
        VerificationContext context = new VerificationContext("testStruct", data);
        
        rule.check(new NodeContext(node, field, false), context);
        
        assertThat(context.findings()).hasSize(1);
        assertThat(context.findings().get(0).severity()).isEqualTo(FindingSeverity.ERROR);
        assertThat(context.findings().get(0).message()).contains("Expected tag APPLICATION [3] but found");
    }

    @Test
    void defaultsToContextWhenTagClassIsNull() {
        byte[] data = hex("83 01 AA"); // CONTEXT 3
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldName("field1").tagNumber(3).tagClass(null).build();
        VerificationContext context = new VerificationContext("testStruct", data);
        
        rule.check(new NodeContext(node, field, false), context);
        
        assertThat(context.findings()).isEmpty();
    }

    @Test
    void reportsExplicitTagThatIsNotConstructed() {
        byte[] data = hex("84 01 AA"); // CONTEXT 4, primitive
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldName("field1").tagNumber(4).explicit(true).build();
        VerificationContext context = new VerificationContext("testStruct", data);
        
        rule.check(new NodeContext(node, field, false), context);
        
        assertThat(context.findings()).hasSize(1);
        assertThat(context.findings().get(0).severity()).isEqualTo(FindingSeverity.ERROR);
        assertThat(context.findings().get(0).message()).contains("EXPLICIT tag should be constructed");
    }

    @Test
    void acceptsExplicitTagThatIsConstructed() {
        byte[] data = hex("A4 03 80 01 AA"); // CONTEXT 4, constructed
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldName("field1").tagNumber(4).explicit(true).build();
        VerificationContext context = new VerificationContext("testStruct", data);
        
        rule.check(new NodeContext(node, field, false), context);
        
        assertThat(context.findings()).isEmpty();
    }

    @Test
    void skipsWhenFieldIsNull() {
        byte[] data = hex("83 01 AA");
        TlvNode node = reader.read(data, 0);
        VerificationContext context = new VerificationContext("testStruct", data);
        
        rule.check(new NodeContext(node, null, false), context);
        
        assertThat(context.findings()).isEmpty();
    }

    @Test
    void skipsCollectionWrapper() {
        byte[] data = hex("83 01 AA");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldName("field1").tagNumber(5).build(); 
        VerificationContext context = new VerificationContext("testStruct", data);
        
        rule.check(new NodeContext(node, field, true), context);
        
        assertThat(context.findings()).isEmpty();
    }

    @Test
    void skipsUntaggedField() {
        byte[] data = hex("83 01 AA");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder().fieldName("field1").tagNumber(null).build();
        VerificationContext context = new VerificationContext("testStruct", data);
        
        rule.check(new NodeContext(node, field, false), context);
        
        assertThat(context.findings()).isEmpty();
    }
}
