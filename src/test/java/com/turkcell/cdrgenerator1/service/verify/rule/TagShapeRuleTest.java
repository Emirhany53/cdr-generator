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

    /**
     * Regresyon: bir alan BIRDEN COK ic ice TLV uretir ve {@code [n]} etiketini
     * yalnizca EN DIStaki tasir. EXPLICIT bir alan {@code [1] { IA5String }}
     * yazar; icerideki evrensel TLV ayni alanla tarif edilir ama etiketi
     * U-[22]'dir. Kural bunu yine de {@code [1]} bekleyerek karsilastirinca
     * DOGRU kodlanmis her EXPLICIT alan iki hata birden veriyordu:
     * "Expected tag CONTEXT [1] but found U-[22]" ve
     * "EXPLICIT tag should be constructed".
     *
     * <p>Uydurma degil: {@code X DEFINITIONS ::=} yazan (yani varsayilani
     * EXPLICIT olan) TKMSC, Telegraph, TelcoDB, SS7, TeslaVoice ve yuzlerce
     * modulun HER alani AllModulesRoundTripTest'te bu sekilde patladi.</p>
     */
    @Test
    void doesNotJudgeTheInnerLayerOfAnExplicitTagAgainstTheOuterTag() {
        // 'A1 0A 16 08 ...' icindeki ic dugum: dogru kodlanmis IA5String.
        byte[] data = hex("16 08 41 42 43 44 45 46 47 48");
        TlvNode node = reader.read(data, 0);
        AsnField field = AsnField.builder()
                .fieldName("cDRid").tagNumber(1).explicit(true).fieldType("IA5STRING").build();
        VerificationContext context = new VerificationContext("TKMSC", data);

        rule.check(new NodeContext(node, field, false, false), context);

        assertThat(context.findings()).isEmpty();
    }

    /**
     * Ayni sebep, ikinci sekil: bir {@code SEQUENCE OF} koleksiyonunun ELEMANI
     * kendi evrensel SEQUENCE etiketini tasir - koleksiyonun {@code [n]}
     * etiketi hepsinin etrafinda BIR KEZ yazilir. Elemani {@code [n]} ile
     * karsilastirmak her koleksiyonun her elemanini yanlis etiketli ilan
     * ediyordu (ornek: {@code productFeeDedicatedAccounts.[0]}).
     */
    @Test
    void doesNotJudgeACollectionElementAgainstTheCollectionTag() {
        byte[] data = hex("30 03 80 01 AA"); // SEQUENCE { [0] AA } - bir eleman
        TlvNode node = reader.read(data, 0);
        AsnField collection = AsnField.builder()
                .fieldName("productFeeDedicatedAccounts").tagNumber(5).repeated(true).build();
        VerificationContext context = new VerificationContext("TurkcellCDRCCNCS5", data);

        rule.check(new NodeContext(node, collection, false, false), context);

        assertThat(context.findings()).isEmpty();
    }

    // --- X.690: universal tags that can only ever be primitive ---

    /** Runs the rule with no field at all: this check reads the bytes only. */
    private VerificationContext checkBytesAlone(byte[] data) {
        VerificationContext context = new VerificationContext("GSN50", data);
        rule.check(new NodeContext(reader.read(data, 0), null, false), context);
        return context;
    }

    /**
     * The GSN50 shape at byte level. {@code 26 0A 04 08 ..} is an OBJECT
     * IDENTIFIER carrying the constructed bit with an OCTET STRING TLV inside,
     * which is what {@code identifier [UNIVERSAL 6] OCTET STRING} produced while
     * the module's EXPLICIT default was allowed to wrap it. X.690 8.19.1 says an
     * object identifier value "shall be primitive", so no conforming decoder can
     * read those bytes - and the tag alone is enough to know it, which is why
     * this check needs no schema.
     */
    @Test
    void reportsAConstructedObjectIdentifier() {
        VerificationContext context = checkBytesAlone(hex("26 0A 04 08 8B 83 A3 DD 9F 1F 55 62"));

        assertThat(context.findings()).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.severity()).isEqualTo(FindingSeverity.ERROR);
                    assertThat(finding.message()).contains("X.690 requires U-[6]");
                });
    }

    /** The same value encoded as X.690 8.19.1 requires draws nothing. */
    @Test
    void acceptsAPrimitiveObjectIdentifier() {
        assertThat(checkBytesAlone(hex("06 08 8B 83 A3 DD 9F 1F 55 62")).findings()).isEmpty();
    }

    /**
     * The guard must not spread to the types BER lets a sender choose for: X.690
     * 8.7.1 allows a constructed OCTET STRING and 8.21.3 a constructed character
     * string. Reporting those would call correct files broken, which is why the
     * check keys on a fixed list rather than on "universal and constructed".
     */
    @Test
    void leavesConstructedOctetStringAndCharacterStringAlone() {
        assertThat(checkBytesAlone(hex("24 06 04 02 AA BB 04 00")).findings())
                .as("a segmented OCTET STRING is legal BER").isEmpty();
        assertThat(checkBytesAlone(hex("3A 04 1A 02 CC DD")).findings())
                .as("a segmented VisibleString is legal BER").isEmpty();
    }

    /** BOOLEAN, INTEGER, NULL, REAL, ENUMERATED and RELATIVE-OID share the rule. */
    @Test
    void coversEveryTypeX690PinsToPrimitive() {
        assertThat(checkBytesAlone(hex("21 03 01 01 FF")).findings())
                .as("constructed BOOLEAN").hasSize(1);
        assertThat(checkBytesAlone(hex("22 03 02 01 05")).findings())
                .as("constructed INTEGER").hasSize(1);
        assertThat(checkBytesAlone(hex("2A 03 0A 01 01")).findings())
                .as("constructed ENUMERATED").hasSize(1);
    }
}
