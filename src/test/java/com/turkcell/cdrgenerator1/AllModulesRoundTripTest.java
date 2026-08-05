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
 * <p>Two kinds of finding are tolerated, and both are properties of the
 * VENDORED SCHEMA that no change to this project could remove. Everything else
 * is a hard failure.</p>
 *
 * <ol>
 *   <li><b>A duplicate UNIVERSAL tag</b>, tolerated for any module. It means the
 *   body declares several UNTAGGED members of the same type - {@code ALLOPTIONAL
 *   ::= SEQUENCE { reportId IA5String OPTIONAL, reportVersion IA5String
 *   OPTIONAL, ... }} - so every one of them is written with the same universal
 *   tag 22. X.680 25.6 does make that ambiguous, and the rule is right to say
 *   so, but the ambiguity is declared in the .asn1 text: the encoder has no
 *   other tag it could legally write. Around 120 of the 808 modules are shaped
 *   this way, most of them DB lookup tables.</li>
 *
 *   <li><b>Every finding of the modules in
 *   {@link #SCHEMA_LEVEL_DUPLICATE_TAG_MODULES}</b>, which declare the same
 *   CONTEXT tag twice in one body - {@code BDCevapsiz} gives {@code cellID} tag
 *   {@code [10]} while its {@code recordType} CHOICE already uses {@code [10]}
 *   for {@code mSOriginating}. That collision also makes the walker's own
 *   field-to-node matching unreliable inside those bodies, so their downstream
 *   verdicts are exempted too rather than half-trusted.</li>
 * </ol>
 *
 * <p>This is deliberately allowlisted by REASON, not by a list of names: a
 * schema module added tomorrow with the same untagged-OPTIONAL shape is covered
 * without anyone editing this file, while a module that starts failing for any
 * OTHER reason still fails. That is the regression this test exists to catch -
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

    /**
     * How {@code TlvNode.tagLabel()} renders a UNIVERSAL tag. A duplicate-tag
     * finding naming one of those describes untagged same-typed members, which
     * the schema declares and the encoder cannot write any other way.
     */
    private static final String UNIVERSAL_TAG_MARKER = "U-[";

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
        Map<String, List<BerFinding>> unexpectedErrors = new TreeMap<>();
        int checked = 0;
        int knownDefectConfirmed = 0;
        int untaggedAmbiguity = 0;

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
                List<BerFinding> unexplained = result.errors().stream()
                        .filter(finding -> !isDeclaredSchemaAmbiguity(finding))
                        .toList();
                if (unexplained.isEmpty()) {
                    untaggedAmbiguity++;
                    continue;
                }
                unexpectedErrors.put(name, unexplained);
            } catch (Exception e) {
                crashed.put(name, e);
            }
        }

        System.out.printf("AllModulesRoundTripTest: %d modules checked, %d clean, "
                        + "%d with a declared duplicate CONTEXT tag, "
                        + "%d whose schema declares untagged same-typed members%n",
                checked, checked - knownDefectConfirmed - untaggedAmbiguity,
                knownDefectConfirmed, untaggedAmbiguity);

        assertTrue(crashed.isEmpty(),
                "generate+encode+verify threw for these modules: " + describeCrashes(crashed));
        assertTrue(unexpectedErrors.isEmpty(),
                "these modules produced an error the vendored schema does not explain - "
                        + "a real regression in the generator, the encoder or the verifier: "
                        + describeErrors(unexpectedErrors));
    }

    /**
     * True when the finding only restates something the .asn1 text itself
     * declares: one body holding several UNTAGGED members of the same type, so
     * they all necessarily carry the same universal tag. See the class javadoc.
     */
    private boolean isDeclaredSchemaAmbiguity(BerFinding finding) {
        return "duplicate-tag".equals(finding.ruleName())
                && finding.message().contains(UNIVERSAL_TAG_MARKER);
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
