package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.generator.source.RandomValueSource;
import com.turkcell.cdrgenerator1.generator.source.UserProvidedValueSource;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code skip-implicit-choice-fields} drops a {CHOICE, IMPLICIT, OPTIONAL}
 * field from generation because EMM mis-reads it and answers "Duplicate Tag" -
 * a defect observed in the MMTel family and verified against its reference
 * capture.
 *
 * <p>The rule used to be purely structural, so it fired in every module that
 * happened to declare that shape: 9 modules outside the family lost 49 fields to
 * a defect nobody had seen there. In TAP-0309 the casualty was
 * {@code callEventDetails} - the only part of a TAP file that carries call
 * records - so the generator produced a batch header and a trailer around
 * nothing at all.</p>
 */
class ImplicitChoiceSkipScopeTest {

    /** The InvolvedParty CHOICE is the family fingerprint the resolver keys on. */
    private static final String FAMILY_FINGERPRINT = """
            InvolvedParty ::= CHOICE {
                sip-uri [0] IA5String,
                tel-uri [1] IA5String
            }
            """;

    private static final String RECORD = """
            Record ::= SEQUENCE {
                recordType [0] IMPLICIT INTEGER,
                party      [1] IMPLICIT PartyAddress OPTIONAL
            }
            PartyAddress ::= CHOICE {
                addressData [0] IA5String,
                shortCode   [1] IA5String
            }
            """;

    private final StructureParserService parser = new StructureParserService(
            null, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());

    private Map<String, Object> generate(String contents, boolean skipEnabled) {
        CdrConfigProperties config = new CdrConfigProperties();
        config.setDefaultRecordCount(1);
        config.setMaxRecordCount(100);
        config.setSkipImplicitChoiceFields(skipEnabled);

        AsnSizeExtractor sizes = new AsnSizeExtractor();
        BcdTimestampFactory timestamps = new BcdTimestampFactory();
        CdrRecordBuilder builder = new CdrRecordBuilder(parser, timestamps, config, List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(new FieldValueGenerator(
                        new TbcdCodec(), new AiConfigProperties(), sizes, timestamps))));

        AsnStructure structure = parser.parseFromContents("Mod", contents);
        return builder.buildRecordFromFields(structure.getFields(), Map.of());
    }

    @Test
    void theFieldIsSkippedInTheFamilyWhereTheDefectWasObserved() {
        Map<String, Object> record = generate("""
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                """ + RECORD + FAMILY_FINGERPRINT + """
                END
                """, true);

        assertFalse(record.containsKey("party"),
                "an IMPLICIT OPTIONAL CHOICE must still be skipped in the MMTel family");
        assertTrue(record.containsKey("recordType"), "everything else must still be generated");
    }

    @Test
    void theSameFieldIsGeneratedOutsideThatFamily() {
        Map<String, Object> record = generate("""
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                """ + RECORD + """
                END
                """, true);

        assertTrue(record.containsKey("party"),
                "outside the family there is no evidence for the workaround, so the field belongs in the record");
    }

    @Test
    void turningTheFlagOffBringsTheFieldBackEvenInTheFamily() {
        Map<String, Object> record = generate("""
                Mod DEFINITIONS IMPLICIT TAGS ::= BEGIN
                """ + RECORD + FAMILY_FINGERPRINT + """
                END
                """, false);

        assertTrue(record.containsKey("party"), "the workaround must stay switchable, not become permanent");
    }
}
