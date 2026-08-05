package com.turkcell.cdrgenerator1.service.verify;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.SelfCheckProperties;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.service.verify.rule.DuplicateTagRule;
import com.turkcell.cdrgenerator1.service.verify.rule.IntegerRangeRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BerVerifierTest {

    private final SelfCheckProperties properties = new SelfCheckProperties();
    private final BerVerifier verifier =
            new BerVerifier(new TlvReader(), properties, List.of(new DuplicateTagRule()));

    private static byte[] hex(String text) {
        String clean = text.replace(" ", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static AsnField leaf(String name, int tag) {
        return AsnField.builder().fieldName(name).tagNumber(tag).build();
    }

    private BerVerificationResult verify(List<AsnField> fields, byte[] data) {
        return verifier.verify("Test", fields, false, false, data);
    }

    // --- the happy path ---

    @Test
    void acceptsARecordThatMatchesItsFieldTree() {
        // SEQUENCE { [0] 53, [2] "ABC" }
        byte[] data = hex("30 08 80 01 53 82 03 41 42 43");

        BerVerificationResult result =
                verify(List.of(leaf("recordType", 0), leaf("sipMethod", 2)), data);

        assertTrue(result.isClean(), result.findings().toString());
        assertEquals(1, result.recordCount());
    }

    @Test
    void countsEveryTopLevelRecord() {
        byte[] data = hex("30 03 80 01 01 30 03 80 01 02");

        assertEquals(2, verify(List.of(leaf("recordType", 0)), data).recordCount());
    }

    // --- what the rules catch through the walk ---

    @Test
    void reportsADuplicateTagInsideTheRecordBody() {
        // SEQUENCE { [0], [0] } - the shape EMM rejects
        byte[] data = hex("30 06 80 01 01 80 01 02");

        BerVerificationResult result =
                verify(List.of(leaf("recordType", 0), leaf("other", 1)), data);

        assertTrue(result.hasErrors());
        assertEquals(1, result.errors().size());
        assertEquals("duplicate-tag", result.errors().get(0).ruleName());
    }

    /**
     * enhancedPhoneFeatures1 [509]: an IMPLICIT collection whose elements are
     * all SETs, so tag 17 repeats and that is correct. The walker has to mark
     * the container so the duplicate-tag rule steps aside for it, and NOT mark
     * the elements, whose own members must still differ.
     */
    @Test
    void acceptsRepeatedElementTagsInACollectionButNotInsideAnElement() {
        AsnField collection = AsnField.builder()
                .fieldName("enhancedPhoneFeatures1").tagNumber(509)
                .repeated(true).set(true)
                .children(List.of(leaf("imsi", 3)))
                .build();

        byte[] clean = hex("30 0E BF 83 7D 0A 31 03 83 01 AA 31 03 83 01 BB");
        assertTrue(verify(List.of(collection), clean).isClean());

        // Same collection, but one element now carries [3] twice.
        byte[] dirty = hex("30 0A BF 83 7D 06 31 04 83 00 83 00");
        assertTrue(verify(List.of(collection), dirty).hasErrors());
    }

    /**
     * A body can hold more than one untagged field, and an OPTIONAL untagged
     * field earlier in the list can be omitted. Matching an untagged sibling by
     * "first one still unclaimed" - the way the walker used to - then attributes
     * a LATER field's bytes to the omitted earlier one whenever the two are
     * different types. 288 of the 808 real modules have a body shaped exactly
     * like this (two or more untagged siblings), so it was not a corner case.
     *
     * <p>Demonstrated through {@link IntegerRangeRule} because {@link
     * com.turkcell.cdrgenerator1.service.verify.rule.TagShapeRule} only checks
     * TAGGED fields and stays silent on an untagged mismatch - the old bug
     * produced no finding at all, which is worse than a loud one. Wiring
     * IntegerRangeRule in makes the wrong attribution visible: bytes that are
     * really an untagged OCTET STRING, misread as the OMITTED untagged INTEGER
     * field that precedes it, decode as a negative number outside its declared
     * (0..999) and are wrongly flagged.</p>
     */
    @Test
    void matchesAnUntaggedFieldByItsOwnTypeNotByArrivalOrder() {
        BerVerifier verifierWithRangeCheck = new BerVerifier(new TlvReader(), properties,
                List.of(new DuplicateTagRule(), new IntegerRangeRule(new AsnSizeExtractor())));

        AsnField omitted = AsnField.builder()
                .fieldName("fractionValue").fieldType("INTEGER (0..999)").build();
        AsnField present = AsnField.builder()
                .fieldName("payload").fieldType("OCTET STRING").build();
        // Only 'present's bytes exist: UNIVERSAL [4] OCTET STRING, content AB CD.
        // 'omitted' carries no value in this record at all. AB CD read as a
        // signed INTEGER is -21555 - well outside (0..999), so a wrong match
        // onto 'omitted' is loud, not silent.
        byte[] data = hex("30 04 04 02 AB CD");

        BerVerificationResult result = verifierWithRangeCheck.verify(
                "Test", List.of(omitted, present), false, false, data);

        assertTrue(result.isClean(), result.findings().toString());
    }

    // --- what the walker itself catches ---

    /**
     * The P1 probe was built by hand to encode impu IMPLICITLY, dropping the
     * EXPLICIT wrapper the schema calls for. The walker notices the missing
     * layer, which is what makes it useful on files it did not produce.
     */
    @Test
    void reportsAnExplicitTagThatWrapsNothing() {
        AsnField impu = AsnField.builder()
                .fieldName("impu").tagNumber(4).choice(true).explicit(true)
                .children(List.of(leaf("sIP-URI", 0)))
                .build();
        // [4] primitive: no wrapper, no alternative tag inside.
        byte[] data = hex("30 04 84 02 AA BB");

        BerVerificationResult result = verify(List.of(impu), data);

        assertTrue(result.hasErrors());
        assertEquals("walker", result.errors().get(0).ruleName());
        assertTrue(result.errors().get(0).message().contains("exactly one TLV"));
    }

    @Test
    void warnsAboutANodeNoFieldClaims() {
        byte[] data = hex("30 06 80 01 01 89 01 02");

        BerVerificationResult result = verify(List.of(leaf("recordType", 0)), data);

        assertEquals(0, result.errors().size());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).message().contains("No field of this body carries tag"));
    }

    /**
     * A resolved CHOICE holds only the alternative the GENERATOR picked. The
     * EMM-accepted reference carries nodeAddress as domainName [1] where the
     * tree holds iPAddress [0]; treating that as an error made the walker
     * reject 50 out of 50 records EMM had accepted. It must say "not verified",
     * not "wrong".
     */
    @Test
    void doesNotFailARecordCarryingADifferentChoiceAlternative() {
        AsnField nodeAddress = AsnField.builder()
                .fieldName("nodeAddress").tagNumber(4).choice(true).explicit(true)
                .children(List.of(AsnField.builder()
                        .fieldName("iPAddress").tagNumber(0).explicit(true)
                        .children(List.of(leaf("iPBinaryAddress", 0)))
                        .build()))
                .build();
        // [4] { [1] "AVMAVAS1" } - domainName, not iPAddress
        byte[] data = hex("30 0C A4 0A 81 08 41 56 4D 41 56 41 53 31");

        BerVerificationResult result = verify(List.of(nodeAddress), data);

        assertEquals(0, result.errors().size());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).message().contains("not verified"));
    }

    // --- configuration ---

    @Test
    void doesNothingWhenSwitchedOff() {
        properties.setMode(SelfCheckProperties.Mode.OFF);

        BerVerificationResult result =
                verify(List.of(leaf("recordType", 0)), hex("30 06 80 01 01 80 01 02"));

        assertTrue(result.isClean());
        assertEquals(0, result.recordCount());
    }

    @Test
    void skipsARuleTurnedOffInConfiguration() {
        properties.setRules(java.util.Map.of("duplicate-tag", Boolean.FALSE));

        BerVerificationResult result =
                verify(List.of(leaf("recordType", 0), leaf("other", 1)), hex("30 06 80 01 01 80 01 02"));

        assertTrue(result.errors().isEmpty());
    }

    @Test
    void reportsBytesThatAreNotBerAtAll() {
        BerVerificationResult result = verify(List.of(leaf("recordType", 0)), hex("83 7F 01"));

        assertTrue(result.hasErrors());
        assertTrue(result.errors().get(0).message().contains("not readable as BER"));
    }
}
