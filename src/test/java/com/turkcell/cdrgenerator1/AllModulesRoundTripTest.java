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
 * <p>Exactly one thing is tolerated: the modules of
 * {@link #SCHEMA_LEVEL_DUPLICATE_TAG_MODULES}, whose vendored .asn1 text
 * declares the same CONTEXT tag twice in one body. {@code BDCevapsiz} gives
 * {@code cellID} tag {@code [10]} while its untagged {@code recordType} CHOICE
 * already carries {@code [10]} for {@code mSOriginating}. That collision also
 * makes the walker's own field-to-node matching unreliable inside those bodies,
 * so their downstream verdicts are exempted rather than half-trusted.</p>
 *
 * <p>The other schema-level shape - a body declaring several UNTAGGED members of
 * one type, so they all carry the same universal tag - needs no allowlist here:
 * {@code DuplicateTagRule} already grades it WARNING, because the encoder has no
 * other tag it could legally write. That judgement belongs in the rule, where
 * {@code /generate-ber} sees it too, not in a test-only exception list.</p>
 *
 * <p>Anything else failing is the regression this test exists to catch -
 * something that used to produce clean BER no longer does - and the fix then
 * belongs in the generator, the encoder or the verifier, never here.</p>
 */
class AllModulesRoundTripTest {

    /**
     * Modules whose vendored ASN.1 text declares the same CONTEXT tag twice
     * among the FIXED (non-repeated) members of one body. Confirmed by reading
     * the schema text - {@code python3 tools/scanAllModules.py
     * src/main/resources/datastructure.json} for the first sixteen, and by this
     * test's own output for {@code BDCevapsiz}/{@code HTSCevapsiz}, whose
     * {@code cellID [10]} collides with the {@code [10]} their untagged
     * {@code recordType} CHOICE already carries.
     */
    private static final Set<String> SCHEMA_LEVEL_DUPLICATE_TAG_MODULES = Set.of(
            "BDCevapsiz",
            "CDRDatamartTANGOmBalance",
            "CwinDataStr",
            "DWHClearedDedicatedISO",
            "FCMSCCNGTP",
            "FCMSVM",
            "FciGgsn",
            "HTSCevapsiz",
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
        cdrRecordBuilder = new CdrRecordBuilder(structureParserService, bcdTimestampFactory, cdrConfig, List.of(
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
        Map<String, List<BerFinding>> unexpectedErrors = new TreeMap<>();
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
                if (SCHEMA_LEVEL_DUPLICATE_TAG_MODULES.contains(name)) {
                    knownDefectConfirmed++;
                    continue;
                }
                unexpectedErrors.put(name, result.errors());
            } catch (Exception e) {
                crashed.put(name, e);
            }
        }

        System.out.printf("AllModulesRoundTripTest: %d modules checked, %d free of errors, "
                        + "%d carrying the declared duplicate CONTEXT tag%n",
                checked, checked - knownDefectConfirmed, knownDefectConfirmed);

        assertTrue(crashed.isEmpty(),
                "generate+encode+verify threw for these modules: " + describeCrashes(crashed));
        assertTrue(unexpectedErrors.isEmpty(),
                "these modules produced an error the vendored schema does not explain - "
                        + "a real regression in the generator, the encoder or the verifier: "
                        + describeErrors(unexpectedErrors));
    }

    private String describeCrashes(Map<String, Exception> crashed) {
        StringBuilder text = new StringBuilder();
        crashed.forEach((name, ex) -> text.append("\n  ").append(name).append(": ")
                .append(ex.getClass().getSimpleName()).append(" - ").append(ex.getMessage()));
        return text.toString();
    }

    private String describeErrors(Map<String, List<BerFinding>> unexpectedErrors) {
        StringBuilder text = new StringBuilder();
        unexpectedErrors.forEach((name, findings) -> {
            text.append("\n  ").append(name).append(" (").append(findings.size()).append(" error(s)):");
            for (BerFinding finding : findings) {
                text.append("\n    ").append(finding);
            }
        });
        return text.toString();
    }
}
