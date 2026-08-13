package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnDeclaredTagging;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shapes here are IMSCDRS.TokensCSCF's, shortened. EMM refused
 * {@code A1 32 16 30 ..} five times and accepted {@code 81 30 ..} on the first
 * attempt, returning all 34 field values intact.
 */
class FieldTagImplicitRuleTest {

    private final FieldTagImplicitRule rule = new FieldTagImplicitRule();
    private final TlvReader reader = new TlvReader();

    /** A keyword-less [1] IA5String field in a keyword-less module. */
    private AsnField.AsnFieldBuilder tokenField() {
        return AsnField.builder()
                .fieldName("sessionId").fieldType("IA5String")
                .tagNumber(1).tagClass(BerTagClass.CONTEXT)
                .moduleNamesNoTaggingMode(true)
                .tagDeclaredOnType(false)
                .declaredTagging(AsnDeclaredTagging.NONE);
    }

    private static byte[] hex(String text) {
        String clean = text.replaceAll("\\s+", "");
        byte[] data = new byte[clean.length() / 2];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return data;
    }

    private VerificationContext check(AsnField field, String bytes) {
        byte[] data = hex(bytes);
        TlvNode node = reader.read(data, 0);
        VerificationContext context = new VerificationContext("IMSCDRS", data);
        rule.check(new NodeContext(node, field, false), context);
        return context;
    }

    // 1 - the refused shape

    /** {@code A1 05 16 03 ..} is IMSCDRS's refused framing: [1] wrapping an IA5String TLV. */
    @Test
    void reportsTheExplicitFieldWrapperEmmRefused() {
        VerificationContext context = check(tokenField().build(), "A1 05 16 03 41 42 43");

        assertThat(context.findings()).hasSize(1);
        assertThat(context.findings().get(0).severity()).isEqualTo(FindingSeverity.ERROR);
        assertThat(context.findings().get(0).message())
                .contains("names no tagging mode")
                .contains("IMSCDRS.TokensCSCF");
    }

    /** The same defect over a body rather than a leaf: [1] wrapping a universal SEQUENCE. */
    @Test
    void reportsTheWrapperOverABodyToo() {
        AsnField body = tokenField()
                .fieldType("Inner")
                .children(List.of(
                        AsnField.builder().fieldName("a").tagNumber(0).build(),
                        AsnField.builder().fieldName("b").tagNumber(1).build()))
                .build();

        assertThat(check(body, "A1 04 30 02 80 00").findings()).hasSize(1);
    }

    // 2 - the accepted shape

    /** {@code 81 03 ..} is what EMM took: the tag replaces IA5String's universal tag. */
    @Test
    void acceptsTheImplicitShapeEmmTook() {
        assertThat(check(tokenField().build(), "81 03 41 42 43").findings()).isEmpty();
    }

    // 3 - a written keyword is out of scope

    /** EMM honours a written keyword, so neither declaration may be convicted. */
    @Test
    void staysOutOfASiteThatWritesItsOwnKeyword() {
        for (AsnDeclaredTagging declared : List.of(AsnDeclaredTagging.EXPLICIT, AsnDeclaredTagging.IMPLICIT)) {
            AsnField field = tokenField().declaredTagging(declared).build();
            assertThat(check(field, "A1 05 16 03 41 42 43").findings())
                    .as("declared %s", declared)
                    .isEmpty();
        }
    }

    /** A module that names its mode keeps X.680's reading - MMTel depends on it. */
    @Test
    void staysOutOfAModuleThatNamesItsTaggingMode() {
        AsnField field = tokenField().moduleNamesNoTaggingMode(false).build();
        assertThat(check(field, "A1 05 16 03 41 42 43").findings()).isEmpty();
    }

    // 4 - type-level tags belong to the other invariant

    /** TypeTagImplicitRule owns those; two rules on one question would double-report. */
    @Test
    void leavesTypeLevelTagsToTheOtherInvariant() {
        AsnField field = tokenField().tagDeclaredOnType(true).build();
        assertThat(check(field, "A1 05 16 03 41 42 43").findings()).isEmpty();
    }

    // the remaining guards

    /** X.680 8.3 governs a tag on a CHOICE, and choiceTagImplicit carries the exception. */
    @Test
    void leavesAChoiceAlone() {
        AsnField field = tokenField().choice(true)
                .children(List.of(AsnField.builder().fieldName("alt").tagNumber(0).build()))
                .build();
        assertThat(check(field, "A1 05 16 03 41 42 43").findings()).isEmpty();
    }

    /** A one-element collection is indistinguishable from a wrapper by shape. */
    @Test
    void leavesARepeatedFieldAlone() {
        AsnField field = tokenField().repeated(true).build();
        assertThat(check(field, "A1 04 30 02 80 00").findings()).isEmpty();
    }

    /** A single-member body may legitimately hold one universal container. */
    @Test
    void staysOutOfABodyTooSmallToBeUnambiguous() {
        AsnField field = tokenField()
                .fieldType("Inner")
                .children(List.of(AsnField.builder().fieldName("only").tagNumber(0).build()))
                .build();
        assertThat(check(field, "A1 04 30 02 80 00").findings()).isEmpty();
    }

    /** A constructed node whose child is not the type's own universal tag is some other defect. */
    @Test
    void ignoresAChildThatIsNotTheTypesUniversalTag() {
        assertThat(check(tokenField().build(), "A1 05 04 03 41 42 43").findings()).isEmpty();
    }
}
