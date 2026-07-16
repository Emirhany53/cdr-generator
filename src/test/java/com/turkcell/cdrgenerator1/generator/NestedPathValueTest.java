package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers B5: nested leaves can be targeted by dotted path, bare name still works. */
class NestedPathValueTest {

    private final CdrRecordBuilder builder = new CdrRecordBuilder(null, new FieldValueGenerator());

    private AsnField leaf(String name, String type) {
        return AsnField.builder().fieldName(name).fieldType(type)
                .tagNumber(1).tagClass(BerTagClass.CONTEXT).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dottedPathTargetsNestedLeaf() {
        AsnField parent = AsnField.builder()
                .fieldName("addr").fieldType("Address")
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)
                .children(List.of(leaf("msisdn", "IA5String")))
                .build();

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(parent), Map.of("addr.msisdn", "905321234567"));

        Map<String, Object> addr = (Map<String, Object>) record.get("addr");
        assertEquals("\"905321234567\"", addr.get("msisdn"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bareNameStillWorksAsFallback() {
        AsnField parent = AsnField.builder()
                .fieldName("addr").fieldType("Address")
                .tagNumber(0).tagClass(BerTagClass.CONTEXT)
                .children(List.of(leaf("msisdn", "IA5String")))
                .build();

        Map<String, Object> record = builder.buildRecordFromFields(
                List.of(parent), Map.of("msisdn", "555"));

        Map<String, Object> addr = (Map<String, Object>) record.get("addr");
        assertEquals("\"555\"", addr.get("msisdn"));
    }
}
