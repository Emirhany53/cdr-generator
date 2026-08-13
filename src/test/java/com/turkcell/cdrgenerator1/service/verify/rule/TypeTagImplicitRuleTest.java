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

class TypeTagImplicitRuleTest {

    private final TypeTagImplicitRule rule = new TypeTagImplicitRule();
    private final TlvReader reader = new TlvReader();

    /** FDRInput's shape: [APPLICATION 1] over a two-member SEQUENCE. */
    private AsnField.AsnFieldBuilder nrFile() {
        return AsnField.builder()
                .fieldName("NrFile")
                .tagNumber(1).tagClass(BerTagClass.APPLICATION)
                .moduleNamesNoTaggingMode(true)
                .tagDeclaredOnType(true)
                .declaredTagging(AsnDeclaredTagging.NONE)
                .children(List.of(
                        AsnField.builder().fieldName("name").tagNumber(2).tagClass(BerTagClass.APPLICATION).build(),
                        AsnField.builder().fieldName("utcCode").tagNumber(3).tagClass(BerTagClass.APPLICATION).build()));
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
        VerificationContext context = new VerificationContext("Mod", data);
        rule.check(new NodeContext(node, field, false), context);
        return context;
    }

    /**
     * The shape EMM refused in round 9: {@code 61 .. 30 .. 42 ..} - the tag
     * wrapping a universal SEQUENCE instead of replacing it.
     */
    @Test
    void reportsTheWrapperEmmRefused() {
        VerificationContext context = check(nrFile().build(), "61 08 30 06 42 01 41 43 01 42");

        assertThat(context.findings()).hasSize(1);
        assertThat(context.findings().get(0).severity()).isEqualTo(FindingSeverity.ERROR);
        assertThat(context.findings().get(0).message())
                .contains("type-level")
                .contains("FDRInput");
    }

    /** The shape it accepted in round 10: the tag replaces the universal SEQUENCE. */
    @Test
    void acceptsTheShapeEmmTook() {
        assertThat(check(nrFile().build(), "61 06 42 01 41 43 01 42").findings()).isEmpty();
    }

    /**
     * A module that writes IMPLICIT TAGS keeps X.680's reading - 96 modules, six
     * of them EMM-accepted, and MMTel matched against a real capture besides.
     */
    @Test
    void staysOutOfAModuleThatNamesItsTaggingMode() {
        AsnField field = nrFile().moduleNamesNoTaggingMode(false).build();
        assertThat(check(field, "61 08 30 06 42 01 41 43 01 42").findings()).isEmpty();
    }

    /** A written keyword is honoured by EMM, so a declared EXPLICIT is out of scope. */
    @Test
    void staysOutOfASiteThatWritesItsOwnKeyword() {
        for (AsnDeclaredTagging declared : List.of(AsnDeclaredTagging.EXPLICIT, AsnDeclaredTagging.IMPLICIT)) {
            AsnField field = nrFile().declaredTagging(declared).build();
            assertThat(check(field, "61 08 30 06 42 01 41 43 01 42").findings())
                    .as("declared %s", declared)
                    .isEmpty();
        }
    }

    /** Field tags were measured on IMSCDRS and get their own invariant. */
    @Test
    void staysOutOfATagWrittenOnTheFieldRatherThanItsType() {
        AsnField field = nrFile().tagDeclaredOnType(false).build();
        assertThat(check(field, "61 08 30 06 42 01 41 43 01 42").findings()).isEmpty();
    }

    /**
     * A one-member body may legitimately hold a single universal container, and
     * the shape alone cannot tell that from a wrapper.
     */
    @Test
    void staysOutOfABodyTooSmallToBeUnambiguous() {
        AsnField field = nrFile()
                .children(List.of(AsnField.builder().fieldName("only").tagNumber(2).build()))
                .build();
        assertThat(check(field, "61 08 30 06 42 01 41 43 01 42").findings()).isEmpty();
    }

    /** Two children mean no wrapper is present, whatever they are. */
    @Test
    void ignoresABodyThatHoldsMoreThanOneChild() {
        assertThat(check(nrFile().build(), "61 04 30 00 31 00").findings()).isEmpty();
    }
}
