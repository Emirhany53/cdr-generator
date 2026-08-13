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
 * The shapes are {@code GGSNTurkcellCdrR7.sgsnAddress [6] EXPLICIT SEQUENCE OF
 * GSNAddress}, where {@code GSNAddress ::= IPAddress ::= CHOICE}. EMM refused
 * the file with "sgsnAddress.[0] not set" in round 1 and accepted it without the
 * universal SEQUENCE layer in rounds 2 and 3.
 */
class CollectionWrapperRuleTest {

    private final CollectionWrapperRule rule = new CollectionWrapperRule();
    private final TlvReader reader = new TlvReader();

    /** sgsnAddress: EXPLICIT declared, neutralised, repeated, CHOICE elements. */
    private AsnField.AsnFieldBuilder sgsnAddress() {
        return AsnField.builder()
                .fieldName("sgsnAddress").fieldType("GSNAddress")
                .tagNumber(6).tagClass(BerTagClass.CONTEXT)
                .declaredTagging(AsnDeclaredTagging.EXPLICIT)
                .explicit(false)
                .repeated(true)
                .choice(true);
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
        VerificationContext context = new VerificationContext("GGSNTurkcellCdrR7", data);
        rule.check(new NodeContext(node, field, true), context);
        return context;
    }

    /** {@code A6 { 30 { 80 .. } }} - the layer round 1 was refused for. */
    @Test
    void reportsTheUniversalSequenceLayerEmmRefused() {
        VerificationContext context = check(sgsnAddress().build(), "A6 06 30 04 80 02 0A 0B");

        assertThat(context.findings()).hasSize(1);
        assertThat(context.findings().get(0).severity()).isEqualTo(FindingSeverity.ERROR);
        assertThat(context.findings().get(0).message())
                .contains("holds the elements directly")
                .contains("GGSNTurkcellCdrR7.sgsnAddress");
    }

    /** {@code A6 { 80 .. }} - the elements straight under the tag, as accepted. */
    @Test
    void acceptsTheElementsCarriedDirectly() {
        assertThat(check(sgsnAddress().build(), "A6 04 80 02 0A 0B").findings()).isEmpty();
    }

    /** Two elements cannot be mistaken for a wrapper in either direction. */
    @Test
    void acceptsSeveralElementsCarriedDirectly() {
        assertThat(check(sgsnAddress().build(), "A6 08 80 02 0A 0B 80 02 0C 0D").findings()).isEmpty();
    }

    /** No written keyword means no neutralisation happened; invariant 2 owns that site. */
    @Test
    void staysOutOfASiteThatDeclaresNoKeyword() {
        AsnField field = sgsnAddress().declaredTagging(AsnDeclaredTagging.NONE).build();
        assertThat(check(field, "A6 06 30 04 80 02 0A 0B").findings()).isEmpty();
    }

    /** The family gate did not fire here, so the wrapper is the encoder's intent. */
    @Test
    void staysOutOfASiteWhoseExplicitSurvived() {
        AsnField field = sgsnAddress().explicit(true).build();
        assertThat(check(field, "A6 06 30 04 80 02 0A 0B").findings()).isEmpty();
    }

    /**
     * A scalar CHOICE keeps its wrapper - {@code servedPDPAddress [9]} resolved
     * through two levels in round 10 - and 269 sites in the family look like this.
     */
    @Test
    void leavesAScalarChoiceAlone() {
        AsnField field = sgsnAddress().repeated(false).build();
        assertThat(check(field, "A6 06 30 04 80 02 0A 0B").findings()).isEmpty();
    }

    /**
     * Where the elements ARE universal SEQUENCEs, a one-element collection and a
     * wrapper are the same bytes. 168 sites in the family are like this and none
     * of them can be judged by shape.
     */
    @Test
    void leavesACollectionOfSequencesAlone() {
        AsnField field = sgsnAddress()
                .choice(false)
                .fieldType("InterOperatorIdentifiers")
                .children(List.of(
                        AsnField.builder().fieldName("a").tagNumber(0).build(),
                        AsnField.builder().fieldName("b").tagNumber(1).build()))
                .build();
        assertThat(check(field, "A6 06 30 04 80 02 0A 0B").findings()).isEmpty();
    }

    /** A child that is not a universal SEQUENCE is some other defect, not this one. */
    @Test
    void ignoresAChildThatIsNotAUniversalSequence() {
        assertThat(check(sgsnAddress().build(), "A6 06 31 04 80 02 0A 0B").findings()).isEmpty();
    }
}
