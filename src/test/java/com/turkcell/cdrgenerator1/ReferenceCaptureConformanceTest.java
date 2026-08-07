package com.turkcell.cdrgenerator1;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.config.AiConfigProperties;
import com.turkcell.cdrgenerator1.config.CdrConfigProperties;
import com.turkcell.cdrgenerator1.generator.BcdTimestampFactory;
import com.turkcell.cdrgenerator1.generator.CdrRecordBuilder;
import com.turkcell.cdrgenerator1.generator.FieldValueGenerator;
import com.turkcell.cdrgenerator1.generator.TbcdCodec;
import com.turkcell.cdrgenerator1.generator.source.RandomValueSource;
import com.turkcell.cdrgenerator1.generator.source.UserProvidedValueSource;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.model.BerTagClass;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.BerEncoderService;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import com.turkcell.cdrgenerator1.service.FixedWidthTextFormatter;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import com.turkcell.cdrgenerator1.service.TlvWriter;
import com.turkcell.cdrgenerator1.service.verify.TlvNode;
import com.turkcell.cdrgenerator1.service.verify.TlvReader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks this project's MMTel encoding against a real, EMM-accepted capture.
 *
 * <p>Every other test in the suite compares the encoder to an expectation this
 * project wrote down. That cannot catch a wrong reading of the schema, because
 * both sides of the comparison come from the same reading - which is exactly
 * how {@code [6] EXPLICIT SEQUENCE OF GSNAddress} shipped to EMM twice with a
 * universal SEQUENCE layer the real wire format does not have, while the
 * self-check reported zero findings. This test is the missing side: the
 * expectation comes from bytes a production network wrote and EMM accepted.</p>
 *
 * <h2>What is compared</h2>
 *
 * <p>Not the values - the capture holds real subscriber data (MSISDN, IMSI,
 * SIP URIs) and nothing derived from its content is read, asserted on, or
 * printed. Only tag and length octets are touched.</p>
 *
 * <p>Not the field set either: which CHOICE alternative is chosen and how many
 * OPTIONAL fields get populated legitimately differ between a generated sample
 * and a live record, and asserting on those would produce a test that fails
 * for reasons nobody cares about.</p>
 *
 * <p>What is compared is the layer structure, reduced to
 * {@link FieldShape} - and specifically the count of universal constructed
 * layers between a field's context tag and the first non-universal tag under
 * it. That number is what an EXPLICIT wrapper adds and what IMPLICIT tagging
 * removes, so it is precisely the quantity the EMM rejections were about:</p>
 *
 * <pre>
 *   A6 { 80 04 .. }            -&gt; 0 universal layers   (what the capture has)
 *   A6 { 30 { 80 04 .. } }     -&gt; 1 universal layer    (what EMM refused)
 *   A21 { 30 { [0] .. } }      -&gt; 1                    (collection, element is a SEQUENCE)
 *   A21 { 30 { 30 { [0] } } }  -&gt; 2                    (the same collection, wrapped)
 * </pre>
 *
 * <p>The measure is blind to the differences that do not matter -
 * {@code nodeAddress [4]} resolves to 0 layers whether the record carries
 * {@code iPAddress} or {@code domainName} - and sharp about the one that
 * does.</p>
 *
 * <h2>When it runs</h2>
 *
 * <p>The capture cannot live in the repository, so the test locates it by
 * {@code -Dcdr.referenceCapture=...}, then {@code $CDR_REFERENCE_CAPTURE},
 * then any {@code *CDR_MMTEL*.ber} in the user's Downloads folder, and skips
 * itself when there is none. A skipped run is not a passing run: this check
 * only protects the machine that has the file.</p>
 */
class ReferenceCaptureConformanceTest {

    private static final String CAPTURE_PROPERTY = "cdr.referenceCapture";
    private static final String CAPTURE_ENV = "CDR_REFERENCE_CAPTURE";
    private static final String CAPTURE_GLOB = "*CDR_MMTEL*.ber";
    private static final String MODULE = "MMTelChargingDataTypes";

    /** Universal tags that can appear as a wrapper the schema's EXPLICIT adds. */
    private static final Set<Integer> WRAPPER_TAGS = Set.of(16, 17);

    private static TlvReader tlvReader;

    /**
     * A field's layer structure, with everything content-dependent stripped out.
     *
     * @param primitive        the field's own TLV carries no children at all
     * @param universalLayers  universal constructed layers crossed before the
     *                         first non-universal tag - the EXPLICIT wrapper count
     * @param innermostClass   class of the tag the descent stopped at, so an
     *                         explicit wrapper around a primitive
     *                         ({@code A1 { 16 .. }}) stays distinguishable from
     *                         a field whose members are context-tagged
     */
    private record FieldShape(boolean primitive, int universalLayers, BerTagClass innermostClass) {
        @Override
        public String toString() {
            return primitive
                    ? "primitive"
                    : universalLayers + " universal layer(s) then " + innermostClass;
        }
    }

    @BeforeAll
    static void wireTheReader() {
        tlvReader = new TlvReader();
    }

    @Test
    void ourMmtelEncodingCarriesTheSameLayersAsAnEmmAcceptedCapture() throws Exception {
        Path capture = locateCapture();
        Assumptions.assumeTrue(capture != null,
                "no EMM-accepted MMTel capture found - set -D" + CAPTURE_PROPERTY
                        + " to run this check");

        Map<Integer, Set<FieldShape>> reference = shapesAcrossCapture(capture);
        assertTrue(reference.size() > 10,
                "the capture should yield a usable field set, got " + reference.size());

        TlvNode ourRecord = tlvReader.read(generateOneRecord(), 0);
        Map<Integer, FieldShape> ours = topLevelShapes(ourRecord);

        // Only a field the capture encodes the SAME way in every record can act
        // as an expectation. A field that varies there describes a shape the
        // network itself does not fix, so it cannot convict us of anything.
        List<String> mismatches = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<Integer, FieldShape> entry : new TreeMap<>(ours).entrySet()) {
            Set<FieldShape> refShapes = reference.get(entry.getKey());
            if (refShapes == null || refShapes.size() != 1) {
                continue;
            }
            compared++;
            FieldShape expected = refShapes.iterator().next();
            if (!expected.equals(entry.getValue())) {
                mismatches.add("[" + entry.getKey() + "] capture: " + expected
                        + " | ours: " + entry.getValue());
            }
        }

        assertTrue(compared >= 8,
                "too few fields overlap with the capture to be a meaningful check: " + compared);
        assertEquals(List.of(), mismatches,
                "our MMTel encoding puts different EXPLICIT layering than the capture EMM accepted. "
                        + "A universal SEQUENCE/SET layer we add where the capture has none is the "
                        + "defect that made EMM refuse LTE-R10 and GGSNTurkcellCdrR7; see "
                        + "AsnFieldTreeResolver.effectiveExplicit(). Fields compared: " + compared);
    }

    /**
     * Reduces one node to its {@link FieldShape} by descending through first
     * children for as long as they are universal AND constructed - that is what
     * an EXPLICIT wrapper looks like from the outside.
     */
    private static FieldShape shapeOf(TlvNode node) {
        if (!node.constructed() || node.children().isEmpty()) {
            return new FieldShape(true, 0, node.tagClass());
        }
        int layers = 0;
        TlvNode current = node.children().get(0);
        while (current.tagClass() == BerTagClass.UNIVERSAL
                && current.constructed()
                && WRAPPER_TAGS.contains(current.tagNumber())
                && !current.children().isEmpty()) {
            layers++;
            current = current.children().get(0);
        }
        return new FieldShape(false, layers, current.tagClass());
    }

    /** Context-tagged members of a record, by tag number. */
    private static Map<Integer, FieldShape> topLevelShapes(TlvNode record) {
        Map<Integer, FieldShape> shapes = new LinkedHashMap<>();
        for (TlvNode child : record.children()) {
            if (child.tagClass() == BerTagClass.CONTEXT) {
                shapes.put(child.tagNumber(), shapeOf(child));
            }
        }
        return shapes;
    }

    /**
     * Walks every record in the capture, collecting the distinct shapes each
     * field is written with. Records are read one at a time - the file runs to
     * tens of megabytes and holding the whole tree would dwarf the check.
     */
    private static Map<Integer, Set<FieldShape>> shapesAcrossCapture(Path capture) throws IOException {
        byte[] data = Files.readAllBytes(capture);
        Map<Integer, Set<FieldShape>> shapes = new HashMap<>();
        int offset = 0;
        while (offset < data.length) {
            TlvNode record = tlvReader.read(data, offset);
            for (Map.Entry<Integer, FieldShape> entry : topLevelShapes(record).entrySet()) {
                shapes.computeIfAbsent(entry.getKey(), k -> new java.util.HashSet<>())
                        .add(entry.getValue());
            }
            offset = record.end();
        }
        return shapes;
    }

    /**
     * One MMTel record from the real pipeline, with
     * {@code skip-implicit-choice-fields} switched OFF so the comparison covers
     * {@code list-Of-Calling-Party-Address [6]} and
     * {@code list-Of-Called-Asserted-Identity [102]} too. The flag only decides
     * whether those fields are emitted at all, never how the others are
     * encoded, so turning it off here widens the check without changing what it
     * measures - and it puts the two fields the capture carries in every record
     * under the same scrutiny as the rest.
     */
    private static byte[] generateOneRecord() throws Exception {
        CdrConfigProperties config = new CdrConfigProperties();
        config.setDataStructurePath("src/main/resources/datastructure.json");
        config.setDefaultRecordCount(1);
        config.setMaxRecordCount(100);
        config.setSkipImplicitChoiceFields(false);

        StructureParserService parser = new StructureParserService(
                new CdrStructureReaderService(config, new ObjectMapper()),
                new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
        parser.init();

        AsnSizeExtractor sizes = new AsnSizeExtractor();
        BcdTimestampFactory timestamps = new BcdTimestampFactory();
        CdrRecordBuilder builder = new CdrRecordBuilder(parser, timestamps, config, List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(new FieldValueGenerator(
                        new TbcdCodec(), new AiConfigProperties(), sizes, timestamps))));

        AsnStructure structure = parser.getStructureByName(MODULE);
        Map<String, Object> record = builder.buildRecordFromFields(structure.getFields(), Map.of());
        return new BerEncoderService(new TlvWriter(), new FixedWidthTextFormatter(sizes))
                .encodeRecord(structure, record);
    }

    /** System property, then environment variable, then the Downloads folder. */
    private static Path locateCapture() throws IOException {
        String configured = System.getProperty(CAPTURE_PROPERTY, System.getenv(CAPTURE_ENV));
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            return Files.isReadable(path) ? path : null;
        }

        Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
        if (!Files.isDirectory(downloads)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(downloads)) {
            return entries.filter(Files::isReadable)
                    .filter(p -> p.getFileSystem().getPathMatcher("glob:" + CAPTURE_GLOB)
                            .matches(p.getFileName()))
                    .findFirst()
                    .orElse(null);
        }
    }
}
