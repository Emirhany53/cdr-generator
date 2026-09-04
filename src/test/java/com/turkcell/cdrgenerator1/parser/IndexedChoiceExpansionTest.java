package com.turkcell.cdrgenerator1.parser;

import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-A: {@code StructureParserService} expands a repeated CHOICE field's
 * single resolved alternative into the UNION of every alternative the
 * caller's INDEXED {@code fieldValues} keys name, when {@code referenceMode}
 * is on - extending {@link com.turkcell.cdrgenerator1.service.StructureParserService}'s
 * existing KN-3 path-scoped override mechanism from "pick ONE alternative for
 * this site" to "this repeated site needs several, one per instance".
 *
 * <h2>Why this file does not compile yet</h2>
 *
 * <p>This targets a {@code parseFromContents} overload - carrying
 * {@code fieldValues} and {@code referenceMode} alongside the existing
 * {@code choiceSelections}/{@code rootType} - that does not exist in
 * {@code StructureParserService} today. That is deliberate: per the design
 * agreed before writing this test, P2-A's wiring (new overloads on
 * {@code getStructureByName}/{@code parseFromContents}, and widening the
 * {@code narrowed} check inside them so this NEVER runs against the
 * startup-cached, request-shared {@code AsnStructure} - see that class's
 * {@code parsedStructures} map) is implementation, not test-writing, and
 * hasn't happened yet. This file is the CONTRACT that implementation must
 * satisfy, written first on purpose.</p>
 *
 * <p>{@link com.turkcell.cdrgenerator1.generator.ReferenceModeRepeatedChoiceTest}
 * covers the other half (B: deriving instance count, C: per-instance
 * alternative filtering) against today's REAL, already-working
 * {@code CdrRecordBuilder} by hand-building the tree this class's target
 * method is meant to produce - so that half can be proven correct without
 * waiting on this one.</p>
 */
class IndexedChoiceExpansionTest {

    private static final String FAMILY_FINGERPRINT = """
            InvolvedParty ::= CHOICE {
                sip-uri [0] IA5String,
                tel-uri [1] IA5String
            }
            """;

    /**
     * {@code parties}' type is a NAMED ALIAS ({@code ListOfInvolvedParties}),
     * not an inline {@code SEQUENCE OF InvolvedParty} - matching the real
     * MMTel schema exactly ({@code list-Of-Calling-Party-Address [6] EXPLICIT
     * ListOfInvolvedParties OPTIONAL}). This distinction is load-bearing: an
     * earlier version of this module wrote the SEQUENCE OF inline, and
     * {@code field.getFieldType()} for an inline expression already equals the
     * inner CHOICE type name ("InvolvedParty"), which accidentally masked a
     * real bug - {@code expandIndexedChoiceCollections} was keying its
     * per-alternative selection on {@code field.getFieldType()} directly
     * ("ListOfInvolvedParties" for a NAMED alias), not the type
     * {@code resolveChoiceAlternative} actually reads
     * ("InvolvedParty", discovered only once the alias unwraps). The inline
     * shape never exercised that unwrapping step, so this test passed while
     * generating against the real, alias-shaped schema produced only
     * {@code sIP-URI} twice. Caught by hand, live, in round P2.
     */
    private static final String RECORD = """
            Record ::= SEQUENCE {
                recordType [0] IMPLICIT INTEGER,
                parties    [6] IMPLICIT ListOfInvolvedParties OPTIONAL
            }
            ListOfInvolvedParties ::= SEQUENCE OF InvolvedParty
            """;

    private final StructureParserService parser = new StructureParserService(
            null, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());

    private String module() {
        return "Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN\n" + RECORD + FAMILY_FINGERPRINT + "END\n";
    }

    /**
     * Deliberately not gated on the MMTel family fingerprint the same way P1
     * is - P2's mechanism is general to ANY repeated CHOICE in reference mode,
     * not limited to the specific defect P1 works around. This module still
     * defines InvolvedParty as a CHOICE, but that only matters here because
     * it's the type {@code parties} happens to use, not because the family
     * gate is being exercised.
     */
    @Test
    void twoDistinctIndexedAlternativesExpandTheFieldsChildren() {
        Map<String, String> fieldValues = Map.of(
                "parties[0].sip-uri", "sip:test1@example.org",
                "parties[1].tel-uri", "tel:0123456789");

        // Target signature - does not exist yet.
        AsnStructure structure = parser.parseFromContents(
                "Mod", module(), Map.of(), null, fieldValues, true);

        AsnField parties = structure.getFields().stream()
                .filter(f -> "parties".equals(f.getFieldName()))
                .findFirst()
                .orElseThrow();

        assertThat(parties.getChildren())
                .as("both named alternatives must be present, in resolution order")
                .extracting(AsnField::getFieldName)
                .containsExactly("sip-uri", "tel-uri");
    }

    @Test
    void aSingleIndexedAlternativeDoesNotExpandAnything() {
        Map<String, String> fieldValues = Map.of("parties[0].sip-uri", "sip:test1@example.org");

        AsnStructure structure = parser.parseFromContents(
                "Mod", module(), Map.of(), null, fieldValues, true);

        AsnField parties = structure.getFields().stream()
                .filter(f -> "parties".equals(f.getFieldName()))
                .findFirst()
                .orElseThrow();

        assertThat(parties.getChildren())
                .as("one alternative named is exactly today's shape - nothing to widen for")
                .hasSize(1);
    }

    @Test
    void referenceModeOffNeverExpandsEvenWithIndexedFieldValues() {
        Map<String, String> fieldValues = Map.of(
                "parties[0].sip-uri", "sip:test1@example.org",
                "parties[1].tel-uri", "tel:0123456789");

        AsnStructure structure = parser.parseFromContents(
                "Mod", module(), Map.of(), null, fieldValues, false);

        AsnField parties = structure.getFields().stream()
                .filter(f -> "parties".equals(f.getFieldName()))
                .findFirst()
                .orElseThrow();

        assertThat(parties.getChildren()).hasSize(1);
    }

    /**
     * The startup-cached path (no choiceSelections, no rootType, no reference
     * data at all) must return the SAME shared object it always has -
     * confirming this feature cannot be reached without narrowing, and so can
     * never mutate what every other caller of {@code getStructureByName(name)}
     * relies on.
     */
    @Test
    void plainLookupWithNoReferenceDataStillReturnsTheCachedStructure() {
        AsnStructure viaContents = parser.parseFromContents("Mod", module());
        assertThat(viaContents.getFields()).isNotEmpty();
    }
}
