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
import com.turkcell.cdrgenerator1.service.CdrFileWriterService;
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
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes one sample record per ASN.1 family to {@code target/validation/}, for
 * decoding in ASN1VE and - where the family is supported there - for sending to
 * EMM.
 *
 * <p>These are the app's own bytes: the same parser, generator, encoder and
 * shipped field rules the API uses, with the AI provider left out so the sample
 * is reproducible without a network call. The self-check verdict travels with
 * each file in {@code manifest.tsv}, so a disagreement between this project and
 * ASN1VE is visible as a disagreement, not as a mystery.</p>
 *
 * <p>The families are chosen to cover one of each structural shape the
 * architecture audit found, not to be a sample of what Turkcell generates most:
 * a CHOICE root ({@code MMTelChargingDataTypes}, {@code LTE-R10}), a root type
 * that tags itself ({@code Poc}, {@code NRTRDETadigidImsiLookup},
 * {@code Audit_Record_Collection_St}), APPLICATION tags inherited through a
 * type ({@code TAP-0309}, {@code TAP0311}, {@code FDRInput}), a declaration
 * wrapped after its name ({@code CME20R7TurkCellber}), and a module whose
 * header omits IMPLICIT TAGS so every tagged field carries an EXPLICIT wrapper
 * ({@code CDRDatamartPEPSIivr}, {@code BroadSoft2Tesla}) - the one assumption
 * in the encoder that no capture has confirmed yet.</p>
 */
class ValidationSampleTest {

    private static final Path OUTPUT_DIR = Path.of("target", "validation");

    /**
     * A module to sample, and optionally the type inside it to encode as the
     * record. A null {@code rootType} leaves the choice to the parser's own
     * heuristic, which is what every family wants until a consuming flow says
     * otherwise.
     *
     * @param fileName distinguishes two samples of the same module, so an
     *                 alternative reading can be sent to EMM alongside the
     *                 default one rather than instead of it.
     */
    private record Sample(String module, String rootType, String fileName) {
        Sample(String module) {
            this(module, null, module);
        }
    }

    private static final List<Sample> FAMILIES = List.of(
            new Sample("MMTelChargingDataTypes"),
            new Sample("IMSChargingDataTypes"),
            new Sample("IMSCDRS"),
            // EMM rejected the default reading: its CSCFColl flow decodes
            // IMSCDRS.TokensCSCF, not the Cdrs CHOICE the heuristic picks. The
            // two extra samples are the two ways to give it one - the CHOICE
            // with tokenCSCF selected (A1 wrapper) and the bare SEQUENCE.
            new Sample("IMSCDRS", "TokensCSCF", "IMSCDRS-TokensCSCF"),
            new Sample("LTE-R10"),
            new Sample("GGSNTurkcellCdrR7"),
            new Sample("TAP-0309"),
            new Sample("TAP0311"),
            new Sample("NRTRDEINFLOWV0201"),
            new Sample("NRTRDETadigidImsiLookup"),
            new Sample("FDRInput"),
            new Sample("Poc"),
            new Sample("Audit_Record_Collection_St"),
            new Sample("CME20R7TurkCellber"),
            new Sample("CDRDatamartPEPSIivr"),
            new Sample("BroadSoft2Tesla"),
            // Round 8. Not a new structural shape - all four are IMPLICIT-header
            // modules carrying no type-level tag at all, which is exactly why
            // they can go out while round 7 is still unanswered: whatever EMM
            // says about [APPLICATION n], these bytes do not change. What they
            // buy is lineage coverage. CGSN40ber is packet-domain but not the
            // module LTE-R10/GGSN proved, so it is the first test of whether a
            // family verdict generalises. TurkcellCDRCCNCS5 and CHAD are the
            // CCN/OCC lineage, 17 modules no evidence has ever touched.
            // SMSCBerCdr is a second sample of the remaining group.
            new Sample("CGSN40ber"),
            new Sample("TurkcellCDRCCNCS5"),
            new Sample("CHAD"),
            new Sample("SMSCBerCdr"),
            // The widest single gap in what a real decoder has answered for.
            // Every EMM verdict on a rich structure - CHOICE, SEQUENCE OF, SET,
            // nesting - came from a module that writes IMPLICIT TAGS. The 712
            // modules whose header names no mode are represented by IMSCDRS
            // alone, which is 42 flat leaves with no CHOICE, no SEQUENCE OF and
            // no SET. This module is the same header with all of it: 269
            // leaves, 9 CHOICE, 17 SEQUENCE OF, 7 SET, and four fields carrying
            // a written EXPLICIT keyword, which is the one site 5906e76 leaves
            // alone and nothing has confirmed.
            new Sample("CHFChargingDataTypes16"));

    /** CHOICE alternative to select per sample, keyed by output file name. */
    private static final Map<String, Map<String, String>> CHOICE_SELECTIONS = Map.of(
            "IMSCDRS", Map.of("Cdrs", "tokenCSCF"));

    private static StructureParserService parser;
    private static CdrRecordBuilder builder;
    private static BerEncoderService encoder;
    private static BerVerifier verifier;
    private static CdrFileWriterService writer;

    @BeforeAll
    static void wireTheRealPipelineWithoutTheAiProvider() throws Exception {
        CdrConfigProperties config = new CdrConfigProperties();
        config.setDataStructurePath("src/main/resources/datastructure.json");
        config.setDefaultRecordCount(1);
        config.setMaxRecordCount(100);

        parser = new StructureParserService(
                new CdrStructureReaderService(config, new ObjectMapper()),
                new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
        parser.init();

        AiConfigProperties ai = new AiConfigProperties();
        ai.setFieldRules(loadShippedRules());

        AsnSizeExtractor sizes = new AsnSizeExtractor();
        BcdTimestampFactory timestamps = new BcdTimestampFactory();
        builder = new CdrRecordBuilder(parser, timestamps, config, List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(new FieldValueGenerator(
                        new TbcdCodec(), ai, sizes, timestamps))));
        encoder = new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(new AsnSizeExtractor()));
        writer = new CdrFileWriterService();

        SelfCheckProperties selfCheck = new SelfCheckProperties();
        selfCheck.setMode(SelfCheckProperties.Mode.WARN);
        verifier = new BerVerifier(new TlvReader(), selfCheck, List.of(
                new DuplicateTagRule(), new SetOrderingRule(), new TagShapeRule(),
                new NamedNumberRule(), new IntegerRangeRule(sizes)));
    }

    @Test
    void writeOneSamplePerFamily() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
        List<String> manifest = new ArrayList<>();
        manifest.add(String.join("\t", "module", "root", "rootShape", "taggingMode",
                "bytes", "sha256", "head", "errors", "warnings", "datColumns", "file"));

        for (Sample sample : FAMILIES) {
            String name = sample.fileName();
            AsnStructure structure = parser.getStructureByName(sample.module(),
                    CHOICE_SELECTIONS.get(name), sample.rootType());
            assertTrue(structure != null && structure.getFields() != null
                            && !structure.getFields().isEmpty(),
                    "sample family '" + name + "' must resolve to a record");

            Map<String, Object> record = builder.buildRecordFromFields(structure.getFields(), Map.of());
            byte[] bytes = encoder.encodeRecord(structure, record);
            BerVerificationResult result = verifier.verify(structure, bytes);

            Path file = OUTPUT_DIR.resolve(name + ".ber");
            Files.write(file, bytes);

            // The same record as ASCII, from the same writer /generate uses.
            // Half of what this application produces is .dat, and until now no
            // sample of it was written anywhere - so a break in that half was
            // only visible to somebody who went looking.
            Path datFile = OUTPUT_DIR.resolve(name + ".dat");
            Files.copy(writer.writeCdrFile(name, List.of(record)), datFile,
                    StandardCopyOption.REPLACE_EXISTING);
            int datColumns = Files.readAllLines(datFile, StandardCharsets.US_ASCII).stream()
                    .findFirst()
                    .map(line -> line.split("\\|", -1).length)
                    .orElse(0);

            String rootShape = structure.getRootTagCarrier() != null
                    ? "type-tagged (" + structure.getRootTagCarrier().getTagClass() + " "
                            + structure.getRootTagCarrier().getTagNumber() + ")"
                    : structure.isChoiceRoot() ? "CHOICE alternative"
                    : structure.isSetRoot() ? "universal SET" : "universal SEQUENCE";

            manifest.add(String.join("\t", name,
                    structure.isChoiceRoot() && structure.getChoiceTypeName() != null
                            ? structure.getChoiceTypeName()
                            : sample.rootType() != null ? sample.rootType() : structure.getStructureName(),
                    rootShape,
                    taggingMode(sample.module()),
                    String.valueOf(bytes.length),
                    sha256(bytes),
                    HexFormat.of().formatHex(bytes, 0, Math.min(8, bytes.length)),
                    String.valueOf(result.errors().size()),
                    String.valueOf(result.findings().size() - result.errors().size()),
                    String.valueOf(datColumns),
                    file.toString()));

            // Written in one go, replacing whatever the last run left. Appending
            // made every re-run stack another copy of the same findings on top
            // of the old ones, so the file said "4 errors" where the run had
            // found one and nobody could tell a new finding from an echo.
            Path findingsFile = OUTPUT_DIR.resolve(name + ".findings.txt");
            if (result.findings().isEmpty()) {
                Files.deleteIfExists(findingsFile);
            } else {
                Files.write(findingsFile,
                        result.findings().stream().map(BerFinding::toString).toList(),
                        StandardCharsets.UTF_8);
            }
        }

        Files.write(OUTPUT_DIR.resolve("manifest.tsv"), manifest, StandardCharsets.UTF_8);
        manifest.forEach(System.out::println);
        System.out.println("SAMPLES written to " + OUTPUT_DIR.toAbsolutePath());
    }

    /** The module header's tagging mode, which decides what a bare [n] means. */
    private static String taggingMode(String moduleName) {
        return new AsnTypeRegistryBuilder()
                .detectTaggingMode(parser.getRawContents(moduleName)).name();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
    }

    /** Reads the app.cdr.ai.field-rules node of the shipped application.yml. */
    @SuppressWarnings("unchecked")
    private static List<AiConfigProperties.FieldRule> loadShippedRules() throws Exception {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            root = new Yaml().load(in);
        }
        Map<String, Object> app = (Map<String, Object>) root.get("app");
        Map<String, Object> cdr = (Map<String, Object>) app.get("cdr");
        Map<String, Object> ai = (Map<String, Object>) cdr.get("ai");
        List<Map<String, Object>> raw = (List<Map<String, Object>>) ai.get("field-rules");

        List<AiConfigProperties.FieldRule> rules = new ArrayList<>(raw.size());
        for (Map<String, Object> entry : raw) {
            AiConfigProperties.FieldRule rule = new AiConfigProperties.FieldRule();
            rule.setName((String) entry.get("name"));
            rule.setMatch((List<String>) entry.get("match"));
            rule.setDescription((String) entry.get("description"));
            rule.setPattern((String) entry.get("pattern"));
            rule.setExamples((List<String>) entry.get("examples"));
            rule.setOctetStringContent((String) entry.get("octet-string-content"));
            rules.add(rule);
        }
        return rules;
    }
}
