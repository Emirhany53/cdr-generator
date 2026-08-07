package com.turkcell.cdrgenerator1.service.verify.rule;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.verify.BerFinding;
import com.turkcell.cdrgenerator1.service.verify.FindingSeverity;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.VerificationContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateTagRuleTest {

    private final DuplicateTagRule rule = new DuplicateTagRule();
    private final TlvReader reader = new TlvReader();

    private static byte[] hex(String text) {
        String clean = text.replace(" ", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static AsnField field(String name, boolean repeated) {
        return AsnField.builder().fieldName(name).repeated(repeated).build();
    }

    private List<BerFinding> run(byte[] data, AsnField field, boolean collectionWrapper) {
        VerificationContext context = new VerificationContext("MMTelChargingDataTypes", data);
        rule.check(new NodeContext(reader.read(data, 0), field, collectionWrapper), context);
        return context.findings();
    }

    @Test
    void acceptsFixedBodyWithDistinctTags() {
        // SET { [3], [4], [8] }
        byte[] data = hex("31 09 83 01 AA 84 01 BB 88 01 CC");

        assertTrue(run(data, field("epf1Service", false), false).isEmpty());
    }

    /**
     * The shape EMM rejects. Epf1Service declares epf1-Role-of-Node at [0]; if
     * anything else in the same SET also lands on [0] the body stops being
     * decodable, and this is the message EMM sends back.
     */
    @Test
    void reportsTwoMembersSharingATag() {
        // SET { [0], [0] }
        byte[] data = hex("31 06 80 01 AA 80 01 BB");

        List<BerFinding> findings = run(data, field("epf1Service", false), false);

        assertEquals(1, findings.size());
        BerFinding finding = findings.get(0);
        assertEquals(FindingSeverity.ERROR, finding.severity());
        assertEquals("duplicate-tag", finding.ruleName());
        assertEquals("MMTelChargingDataTypes.[0]", finding.path());
        assertTrue(finding.message().contains("Duplicate Tag data found"));
        assertTrue(finding.message().contains("2 times"));
    }

    @Test
    void reportsEachCollidingTagOnce() {
        // SET { [0], [0], [0], [1], [1] } -> two findings, not five
        byte[] data = hex("31 0F 80 01 AA 80 01 BB 80 01 CC 81 01 DD 81 01 EE");

        List<BerFinding> findings = run(data, field("epf1Service", false), false);

        assertEquals(2, findings.size());
        assertTrue(findings.get(0).message().contains("3 times"));
        assertTrue(findings.get(1).message().contains("2 times"));
    }

    /**
     * enhancedPhoneFeatures1 [509] is a SEQUENCE OF Epf1Service, so every
     * element is a 31 and repeating that tag is exactly right. Flagging it
     * would make the rule useless on every collection in the schema.
     */
    @Test
    void acceptsRepeatedElementTagsInsideACollection() {
        // [509] { 31 {..}, 31 {..} }
        byte[] data = hex("BF 83 7D 0A 31 03 83 01 AA 31 03 83 01 BB");

        assertTrue(run(data, field("enhancedPhoneFeatures1", true), true).isEmpty());
    }

    /**
     * The same field, but now positioned on one ELEMENT rather than on the
     * container: here the members are fixed again and must differ.
     */
    @Test
    void reportsDuplicatesInsideACollectionElement() {
        byte[] data = hex("31 06 80 01 AA 80 01 BB");

        List<BerFinding> findings = run(data, field("enhancedPhoneFeatures1", true), false);

        assertEquals(1, findings.size());
        assertEquals(FindingSeverity.ERROR, findings.get(0).severity());
    }

    @Test
    void downgradesToWarningWhenTheNodeMatchedNoField() {
        byte[] data = hex("31 06 80 01 AA 80 01 BB");

        List<BerFinding> findings = run(data, null, false);

        assertEquals(1, findings.size());
        assertEquals(FindingSeverity.WARNING, findings.get(0).severity());
    }

    /**
     * A repeated UNIVERSAL tag is a different statement from a repeated context
     * tag. It says the body declares several UNTAGGED members of the same type -
     * {@code ALLOPTIONAL ::= SEQUENCE { reportId IA5String OPTIONAL,
     * reportVersion IA5String OPTIONAL, ... }} does it twelve times - so there
     * is no other tag the encoder could legally write. Worth reporting, not
     * worth refusing the file over: around 120 of the 808 modules are shaped
     * that way, and grading it ERROR would mean STRICT mode could no longer
     * generate a file for any of them.
     */
    @Test
    void gradesARepeatedUniversalTagAsAWarning() {
        // SEQUENCE { IA5String, IA5String } - two untagged members of one type.
        byte[] data = hex("30 08 16 02 41 42 16 02 43 44");

        List<BerFinding> findings = run(data, field("dbRecord", false), false);

        assertEquals(1, findings.size());
        assertEquals(FindingSeverity.WARNING, findings.get(0).severity());
        assertTrue(findings.get(0).message().contains("U-[22]"));
    }

    @Test
    void separatesTagsOfDifferentClasses() {
        // UNIVERSAL [2] and CONTEXT [2] are different tags, not a collision.
        byte[] data = hex("31 06 02 01 AA 82 01 BB");

        assertTrue(run(data, field("mixed", false), false).isEmpty());
    }

    @Test
    void ignoresPrimitiveAndSingleChildNodes() {
        assertTrue(run(hex("83 02 AA BB"), field("imsi", false), false).isEmpty());
        assertTrue(run(hex("A4 03 80 01 AA"), field("impu", false), false).isEmpty());
    }

    @Test
    void reportsTheOffsetOfTheFirstOccurrence() {
        byte[] data = hex("31 06 80 01 AA 80 01 BB");

        assertEquals(2, run(data, field("epf1Service", false), false).get(0).byteOffset());
    }

    // --- X.680 25.6: which repeats a SEQUENCE's own ordering already resolves ---

    private static AsnField member(String name, Integer tag, boolean optional) {
        return AsnField.builder().fieldName(name).tagNumber(tag).optional(optional).build();
    }

    private static AsnField sequenceBody(AsnField... members) {
        return AsnField.builder().fieldName("body").children(List.of(members)).build();
    }

    /**
     * The SDPAdjLikya shape: {@code dedicatedAccount1Action [17] OPTIONAL} and
     * {@code dedicatedAccount6Action [17] OPTIONAL} with nothing but OPTIONALs
     * between them. Every component here may be absent, so a decoder meeting a
     * {@code [17]} cannot tell which member it belongs to - the file is not
     * decodable and the severity must stay ERROR.
     */
    @Test
    void aRepeatBetweenTwoOmissibleMembersStaysAnError() {
        AsnField body = sequenceBody(
                member("dedicatedAccount1Action", 17, true),
                member("dedicatedAccount1Amount", 16, true),
                member("dedicatedAccount6Action", 17, true));
        // SEQUENCE { [17], [16], [17] }
        byte[] data = hex("30 09 91 01 AA 90 01 BB 91 01 CC");

        List<BerFinding> findings = run(data, body, false);

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(FindingSeverity.ERROR, findings.get(0).severity(),
                "nothing mandatory separates the two [17]s, so position cannot resolve them");
    }

    /**
     * The HTSCevapsiz / BDCevapsiz shape: a MANDATORY anonymous
     * {@code recordType CHOICE { mSOriginating [10] IMPLICIT IA5String, ... }}
     * first, then {@code cellID [10] OPTIONAL} further down. A decoder must
     * consume the mandatory member positionally, so the first {@code [10]} can
     * only be recordType and the second can only be cellID. X.680 25.6 asks
     * nothing of tags a mandatory component separates, so this is reported but
     * must not block generation.
     */
    @Test
    void aRepeatAMandatoryMemberSeparatesIsOnlyAWarning() {
        AsnField recordType = AsnField.builder()
                .fieldName("recordType").choice(true)
                .children(List.of(member("mSOriginating", 10, false)))
                .build();
        AsnField body = sequenceBody(
                recordType,
                member("aNumber", 2, true),
                member("cellID", 10, true));
        // SEQUENCE { [10], [2], [10] }
        byte[] data = hex("30 09 8A 01 AA 82 01 BB 8A 01 CC");

        List<BerFinding> findings = run(data, body, false);

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(FindingSeverity.WARNING, findings.get(0).severity(),
                "the mandatory recordType is consumed first, so the repeat is decodable");
        assertTrue(findings.get(0).message().contains("X.680 25.6"),
                "the reason belongs in the message: " + findings.get(0).message());
    }

    /**
     * The same members, but with the first one omissible. Now nothing forces the
     * decoder's hand and the body is ambiguous again - this is what keeps the
     * relaxation narrow rather than a blanket downgrade of every repeat.
     */
    @Test
    void theSameShapeIsAnErrorOnceTheSeparatingMemberBecomesOptional() {
        AsnField body = sequenceBody(
                member("recordType", 10, true),
                member("aNumber", 2, true),
                member("cellID", 10, true));
        byte[] data = hex("30 09 8A 01 AA 82 01 BB 8A 01 CC");

        assertEquals(FindingSeverity.ERROR, run(data, body, false).get(0).severity());
    }

    /**
     * A SET carries no positional information at all - X.680 27.3 requires every
     * component tag to be distinct - so the mandatory-member reasoning must not
     * apply there however the members are marked.
     */
    @Test
    void aSetIsNeverResolvedByPosition() {
        AsnField body = AsnField.builder()
                .fieldName("body").set(true)
                .children(List.of(
                        member("first", 10, false),
                        member("second", 2, true),
                        member("third", 10, true)))
                .build();
        byte[] data = hex("31 09 8A 01 AA 82 01 BB 8A 01 CC");

        assertEquals(FindingSeverity.ERROR, run(data, body, false).get(0).severity());
    }
}
