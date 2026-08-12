package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrimitiveLengthRuleTest {

    private final PrimitiveLengthRule rule = new PrimitiveLengthRule();
    private final TlvReader reader = new TlvReader();

    private static byte[] hex(String text) {
        String clean = text.replaceAll("\\s+", "");
        byte[] data = new byte[clean.length() / 2];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return data;
    }

    private VerificationContext check(byte[] data) {
        TlvNode node = reader.read(data, 0);
        VerificationContext context = new VerificationContext("testStruct", data);
        rule.check(new NodeContext(node, null, false), context);
        return context;
    }

    /**
     * The exact shape EMM refused in round 2. {@code dynamicAddressFlag [11]
     * EXPLICIT DynamicAddressFlag} went out as {@code AB 03 01 01 FF}; read as
     * an IMPLICIT BOOLEAN that is {@code 01 03 ..}, a BOOLEAN claiming three
     * contents octets. Before this rule nothing on the byte side objected.
     */
    @Test
    void reportsTheBooleanLengthEmmRefused() {
        VerificationContext context = check(hex("01 03 01 01 FF"));

        assertThat(context.findings()).hasSize(1);
        assertThat(context.findings().get(0).severity()).isEqualTo(FindingSeverity.ERROR);
        assertThat(context.findings().get(0).message())
                .contains("X.690 fixes U-[1] contents to 1 octet(s), but this value is 3");
    }

    @Test
    void acceptsTheBooleanLengthEmmAskedFor() {
        assertThat(check(hex("01 01 FF")).findings()).isEmpty();
        assertThat(check(hex("01 01 00")).findings()).isEmpty();
    }

    @Test
    void reportsNullCarryingContents() {
        VerificationContext context = check(hex("05 01 00"));

        assertThat(context.findings()).hasSize(1);
        assertThat(context.findings().get(0).severity()).isEqualTo(FindingSeverity.ERROR);
        assertThat(context.findings().get(0).message())
                .contains("X.690 fixes U-[5] contents to 0 octet(s), but this value is 1");
    }

    @Test
    void acceptsEmptyNull() {
        assertThat(check(hex("05 00")).findings()).isEmpty();
    }

    /**
     * The rule judges the tag alone, so it must not touch a type whose length
     * X.690 leaves open - otherwise it would convict correct files.
     */
    @Test
    void ignoresTypesWithNoFixedLength() {
        assertThat(check(hex("02 03 00 DF 2F")).findings()).isEmpty();   // INTEGER
        assertThat(check(hex("0A 01 00")).findings()).isEmpty();         // ENUMERATED
        assertThat(check(hex("16 03 41 42 43")).findings()).isEmpty();   // IA5String
        assertThat(check(hex("04 02 AA BB")).findings()).isEmpty();      // OCTET STRING
    }

    /**
     * A context tag numbered 1 is not a BOOLEAN. Only the UNIVERSAL class says
     * anything about the type, so anything else must pass untouched - most of
     * this data set's fields are {@code [1]} something.
     */
    @Test
    void ignoresNonUniversalTags() {
        assertThat(check(hex("81 03 01 01 FF")).findings()).isEmpty();   // CONTEXT [1]
        assertThat(check(hex("41 03 01 01 FF")).findings()).isEmpty();   // APPLICATION [1]
    }

    /**
     * A constructed node carrying one of these tags is already an ERROR from
     * {@code TagShapeRule}; reporting its length too would double-count the same
     * defect.
     */
    @Test
    void leavesConstructedNodesToTagShapeRule() {
        assertThat(check(hex("21 03 01 01 FF")).findings()).isEmpty();   // constructed BOOLEAN
    }
}
