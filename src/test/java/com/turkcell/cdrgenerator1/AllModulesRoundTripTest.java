package com.turkcell.cdrgenerator1;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.config.SelfCheckProperties;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.generator.FieldValueGenerator;
import com.turkcell.cdrgenerator1.generator.TbcdCodec;
import com.turkcell.cdrgenerator1.generator.source.RandomValueSource;
import com.turkcell.cdrgenerator1.generator.source.UserProvidedValueSource;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import com.turkcell.cdrgenerator1.service.FixedWidthTextFormatter;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import com.turkcell.cdrgenerator1.service.verify.BerFinding;
import com.turkcell.cdrgenerator1.service.verify.BerVerificationResult;
import com.turkcell.cdrgenerator1.service.verify.BerVerifier;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import com.turkcell.cdrgenerator1.service.verify.rule.DuplicateTagRule;
import com.turkcell.cdrgenerator1.service.verify.rule.IntegerRangeRule;
import com.turkcell.cdrgenerator1.service.verify.rule.NamedNumberRule;
import com.turkcell.cdrgenerator1.service.verify.rule.SetOrderingRule;
import com.turkcell.cdrgenerator1.service.verify.rule.TagShapeRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generates one record for every module in the real {@code datastructure.json}
 * (808 at last count), encodes it, and runs the self-check against it - the
 * regression net {@code docs/self-check-design.md} named as the reason to build
 * a verifier at all. Everything upstream of this test was checked by hand,
 * against a handful of hand-built vectors and two reference captures; this is
 * what runs the whole pipeline against every module the system actually has to
 * support.
 *
 * <p>The 16 modules in {@link #SCHEMA_LEVEL_DUPLICATE_TAG_MODULES} are not a
 * tolerance for this test's own imprecision. Their vendored ASN.1 text
 * genuinely declares the same context tag twice in one fixed (non-collection)
 * SEQUENCE body - a real defect in the schema, independent of anything this
 * project generates or encodes - confirmed by
 * {@code python3 tools/scanAllModules.py src/main/resources/datastructure.json}
 * reading the schema text directly, with no dependency on this project's own
 * resolver or encoder. Because {@code CdrRecordBuilder} fills every field,
 * OPTIONAL or not, both colliding tags are always present, so DuplicateTagRule
 * firing on these 16 is the CORRECT verdict, not a false positive to suppress.
 * Silently excluding them would hide a real defect instead of naming it.</p>
 *
 * <p>A module newly appearing here with an error is a regression: something
 * that used to produce clean BER no longer does, and the fix belongs in the
 * generator, the encoder, or the verifier, not in this list. Adding a name to
 * {@link #SCHEMA_LEVEL_DUPLICATE_TAG_MODULES} is a decision to make after
 * reading what changed, never a way to make a failure disappear.</p>
 */
class AllModulesRoundTripTest {

    /**
     * Confirmed by static analysis of the vendored schema text, independent of
     * this project's resolver: each of these 16 modules declares the same
     * context tag twice among the FIXED (non-repeated) members of one SEQUENCE
     * body. See the class javadoc for how this list was produced and why firing
     * on it is correct, not tolerated noise.
     */
    private static final Set<String> SCHEMA_LEVEL_DUPLICATE_TAG_MODULES = Set.of(
            "CDRDatamartTANGOmBalance",
            "CwinDataStr",
            "DWHClearedDedicatedISO",
            "FCMSCCNGTP",
            "FCMSVM",
            "FciGgsn",
            "MSCCAP2Test",
            "NotifyIsoCdr",
            "OTAGXS",
            "PSTNSMSMatching",
            "SDPAdjLikya",
            "SDPAdjLikyaDAC",
            "SMSCMatching",
            "SMSCMatching1",
            "VoiceSMS",
            "VoiceSmsInput");

    private static StructureParserService structureParserService;
    private static CdrRecordBuilder cdrRecordBuilder;
    private static BerEncoderService berEncoderService;
    private static BerVerifier berVerifier;

    @BeforeAll
    static void loadTheRealSchemaAndWireTheRealPipeline() {
        CdrConfigProperties cdrConfig = new CdrConfigProperties();
        // Matches src/main/resources/application.yml exactly - tests run with
        // the module root as the working directory, same as `mvn test` does.
        cdrConfig.setDataStructurePath("src/main/resources/datastructure.json");
        cdrConfig.setDefaultRecordCount(1);
        cdrConfig.setMaxRecordCount(100);

        CdrStructureReaderService readerService =
                new CdrStructureReaderService(cdrConfig, new ObjectMapper());

        structureParserService = new StructureParserService(
                readerService, new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
        structureParserService.init();

        AiConfigProperties aiProperties = new AiConfigProperties();
        AsnSizeExtractor sizeExtractor = new AsnSizeExtractor();
        BcdTimestampFactory bcdTimestampFactory = new BcdTimestampFactory();
        TbcdCodec tbcdCodec = new TbcdCodec();

        // No AiValueSource: userValues is always empty below, so every field
        // falls straight to RandomValueSource regardless of what else is in the
        // chain, and this keeps the test free of network calls entirely.
        cdrRecordBuilder = new CdrRecordBuilder(structureParserService, List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(
                        new FieldValueGenerator(tbcdCodec, aiProperties, sizeExtractor, bcdTimestampFactory))));

        berEncoderService = new BerEncoderService(
                new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));

        SelfCheckProperties selfCheckProperties = new SelfCheckProperties();
        selfCheckProperties.setMode(SelfCheckProperties.Mode.WARN);
        berVerifier = new BerVerifier(new TlvReader(), selfCheckProperties, List.of(
                new DuplicateTagRule(),
                new SetOrderingRule(),
                new TagShapeRule(),
                new NamedNumberRule(),
                new IntegerRangeRule(sizeExtractor)));
    }

    @Test
    void everyResolvableModuleRoundTripsCleanOrOnlyWithTheKnownSchemaDefect() {
        Map<String, AsnStructure> structures = structureParserService.getAllParsedStructures();
        assertTrue(structures.size() > 700,
                "expected the real datastructure.json (808 modules); got " + structures.size()
                        + " - is the working directory the project root?");

        Map<String, Exception> crashed = new LinkedHashMap<>();
        Map<String, BerVerificationResult> unexpectedErrors = new TreeMap<>();
        int checked = 0;
        int knownDefectConfirmed = 0;

        for (Map.Entry<String, AsnStructure> entry : structures.entrySet()) {
            String name = entry.getKey();
            AsnStructure structure = entry.getValue();
            // Empty-field modules (helper type aliases, not CDR records - Array,
            // LteReturnTypes, SMSCLookupStructures and three fully-empty stubs)
            // have nothing to generate; BerGeneratorController refuses these at
            // the API layer for the same reason.
            if (structure.getFields() == null || structure.getFields().isEmpty()) {
                continue;
            }

            try {
                Map<String, Object> record =
                        cdrRecordBuilder.buildRecordFromFields(structure.getFields(), Map.of());
                byte[] bytes = berEncoderService.encodeRecord(structure, record);
                BerVerificationResult result = berVerifier.verify(structure, bytes);
                checked++;

                if (!result.hasErrors()) {
                    continue;
                }
                if (SCHEMA_LEVEL_DUPLICATE_TAG_MODULES.contains(name)
                        && onlyDuplicateTagErrors(result)) {
                    knownDefectConfirmed++;
                    continue;
                }
                unexpectedErrors.put(name, result);
            } catch (Exception e) {
                crashed.put(name, e);
            }
        }

        System.out.printf(
                "AllModulesRoundTripTest: %d modules checked, %d confirmed as the known schema defect%n",
                checked, knownDefectConfirmed);

        assertTrue(crashed.isEmpty(),
                "generate+encode+verify threw for these modules: " + describeCrashes(crashed));
        assertTrue(unexpectedErrors.isEmpty(),
                "these modules produced an error the 16-module allowlist does not explain "
                        + "(a real regression, or a module to add to the allowlist after reading why): "
                        + describeErrors(unexpectedErrors));
    }

    private boolean onlyDuplicateTagErrors(BerVerificationResult result) {
        return result.errors().stream().allMatch(f -> "duplicate-tag".equals(f.ruleName()));
    }

    private String describeCrashes(Map<String, Exception> crashed) {
        StringBuilder text = new StringBuilder();
        crashed.forEach((name, ex) -> text.append("\n  ").append(name).append(": ")
                .append(ex.getClass().getSimpleName()).append(" - ").append(ex.getMessage()));
        return text.toString();
    }

    private String describeErrors(Map<String, BerVerificationResult> unexpectedErrors) {
        StringBuilder text = new StringBuilder();
        unexpectedErrors.forEach((name, result) -> {
            text.append("\n  ").append(name).append(" (").append(result.errors().size()).append(" error(s)):");
            for (BerFinding finding : result.errors()) {
                text.append("\n    ").append(finding);
            }
        });
        return text.toString();
    }
}
