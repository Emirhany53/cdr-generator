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
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.parser.AsnFieldTreeResolver;
import com.turkcell.cdrgenerator1.parser.AsnTypeRegistryBuilder;
import com.turkcell.cdrgenerator1.service.AsnLiteralFormatter;
import com.turkcell.cdrgenerator1.service.BerPrimitiveType;
import com.turkcell.cdrgenerator1.service.CdrFileWriterService;
import com.turkcell.cdrgenerator1.service.CdrStructureReaderService;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code .txt} counterpart of {@link AllModulesRoundTripTest}.
 *
 * <p>Half of what this application promises is Token-Separated ASCII, and until
 * now nothing checked it across the data set: the round-trip test encodes BER,
 * the sample test wrote only {@code .ber}, and {@code CdrFileWriterServiceTest}
 * covers the writer on hand-built maps. So a module whose ASCII output broke
 * would have gone unnoticed unless somebody opened the file.</p>
 *
 * <h2>What is asserted, and why only this</h2>
 *
 * <p>The writer's contract is stated in its own class comment: records may carry
 * different numbers of {@code SEQUENCE OF} elements, so it takes the UNION of
 * every record's leaf paths as the column set and leaves a cell empty where a
 * record has no value - "boylece satirlar hizali kalir ve tuketici taraf sutun
 * kaymasi yasamaz". That gives three checkable invariants: one line per record,
 * every line the same width, and that width not zero.</p>
 *
 * <p>A fourth follows from the format itself. Nothing escapes the {@code |}
 * separator, so a value containing one silently splits into two columns. With
 * several records that shows up as a width mismatch, but if every record carried
 * it the file would be uniformly wrong - so the values are checked directly.</p>
 *
 * <p>Column count is deliberately NOT recomputed from the field tree here.
 * Flattening a record into paths is exactly what the writer does; a test that
 * re-implemented it would agree with the code by construction and catch
 * nothing.</p>
 */
class AsciiOutputConformanceTest {

    private static final int RECORDS_PER_MODULE = 3;
    private static final String SEPARATOR = "|";

    private static StructureParserService parser;
    private static CdrRecordBuilder builder;
    private static CdrFileWriterService writer;
    private static AsnSizeExtractor sizes;

    @BeforeAll
    static void wireTheRealPipelineWithoutTheAiProvider() {
        CdrConfigProperties config = new CdrConfigProperties();
        config.setDataStructurePath("src/main/resources/datastructure.json");
        config.setDefaultRecordCount(1);
        config.setMaxRecordCount(100);

        parser = new StructureParserService(
                new CdrStructureReaderService(config, new ObjectMapper()),
                new AsnTypeRegistryBuilder(), new AsnFieldTreeResolver());
        parser.init();

        sizes = new AsnSizeExtractor();
        BcdTimestampFactory timestamps = new BcdTimestampFactory();
        builder = new CdrRecordBuilder(parser, timestamps, config, List.of(
                new UserProvidedValueSource(),
                new RandomValueSource(new FieldValueGenerator(
                        new TbcdCodec(), new AiConfigProperties(), sizes, timestamps))));
        writer = new CdrFileWriterService();
    }

    @Test
    void everyModuleWritesAWellFormedAsciiFile() throws Exception {
        List<String> notWritten = new ArrayList<>();
        List<String> raggedWidth = new ArrayList<>();
        List<String> wrongLineCount = new ArrayList<>();
        List<String> separatorInValue = new ArrayList<>();
        int written = 0;
        int columnTotal = 0;

        for (Map.Entry<String, AsnStructure> entry
                : new TreeMap<>(parser.getAllParsedStructures()).entrySet()) {
            String module = entry.getKey();
            AsnStructure structure = entry.getValue();
            if (structure.getFields() == null || structure.getFields().isEmpty()) {
                continue;
            }

            List<Map<String, Object>> records = new ArrayList<>(RECORDS_PER_MODULE);
            Path file;
            try {
                for (int i = 0; i < RECORDS_PER_MODULE; i++) {
                    records.add(builder.buildRecordFromFields(structure.getFields(), Map.of()));
                }
                file = writer.writeCdrFile(module, records);
            } catch (Exception failure) {
                notWritten.add(module + ": " + failure.getClass().getSimpleName()
                        + " - " + failure.getMessage());
                continue;
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
            Files.deleteIfExists(file);
            written++;

            if (lines.size() != RECORDS_PER_MODULE) {
                wrongLineCount.add(module + ": " + lines.size() + " line(s) for "
                        + RECORDS_PER_MODULE + " record(s)");
                continue;
            }

            List<Integer> widths = lines.stream().map(AsciiOutputConformanceTest::fieldCount).toList();
            if (widths.stream().distinct().count() != 1) {
                raggedWidth.add(module + ": widths " + widths);
            } else {
                columnTotal += widths.get(0);
            }

            records.stream()
                    .flatMap(record -> flattenValues(record).stream())
                    .filter(value -> value.contains(SEPARATOR))
                    .findFirst()
                    .ifPresent(value -> separatorInValue.add(
                            module + ": a value contains the column separator"));
        }

        System.out.printf("ASCII: %d module(s) wrote a file, %.1f columns on average%n",
                written, written == 0 ? 0 : (double) columnTotal / written);

        assertEquals(List.of(), notWritten, "these modules could not produce a .txt at all");
        assertEquals(List.of(), wrongLineCount, "the writer promises one line per record");
        assertEquals(List.of(), raggedWidth,
                "every line must carry the same number of columns - the writer pads the union of "
                        + "all records' leaf paths precisely so a consumer never sees a column shift");
        assertEquals(List.of(), separatorInValue,
                "nothing escapes '|', so a value carrying one silently becomes two columns");
        assertTrue(written > 700, "expected the vast majority of modules to write a file, got " + written);
    }

    /**
     * A value must not be LONGER than the width its type fixes.
     *
     * <p>Only the upper bound is asserted, and deliberately so. {@code CODE("LEFT")}
     * / {@code CODE("RIGHT")} with a {@code SIZE(n)} is the schemas' fixed-width
     * annotation, and {@code FixedWidthTextFormatter} pads a value out to {@code n}
     * - but it is wired into {@code BerEncoderService} only, never into
     * {@code CdrFileWriterService}. So ASCII output is not padded, and asserting
     * that it were would be asserting something the product does not claim: a
     * Token-Separated file carries its own delimiters, and the column boundary
     * comes from {@code |} rather than from the width.</p>
     *
     * <p>Exceeding the declared width is a different matter - that is a value the
     * schema does not permit, in either format - so that is what is checked. The
     * check runs on the record map rather than on the written line, because a
     * value is what it is before the writer ever sees it.</p>
     */
    @Test
    void noValueExceedsTheWidthItsTypeFixes() {
        List<String> tooLong = new ArrayList<>();
        int checked = 0;
        int fixedWidthLeaves = 0;

        for (Map.Entry<String, AsnStructure> entry
                : new TreeMap<>(parser.getAllParsedStructures()).entrySet()) {
            AsnStructure structure = entry.getValue();
            if (structure.getFields() == null || structure.getFields().isEmpty()) {
                continue;
            }
            Map<String, Object> record = builder.buildRecordFromFields(structure.getFields(), Map.of());
            List<String> problems = new ArrayList<>();
            int[] counters = new int[2];
            checkWidths(structure.getFields(), record, entry.getKey(), problems, counters);
            checked += counters[0];
            fixedWidthLeaves += counters[1];
            tooLong.addAll(problems);
        }

        System.out.printf("ASCII: %d leaf value(s) inspected, %d carry a fixed width%n",
                checked, fixedWidthLeaves);

        assertTrue(fixedWidthLeaves > 100,
                "the check is only meaningful if fixed-width leaves are actually reached, got "
                        + fixedWidthLeaves);
        assertEquals(List.of(), tooLong, "a value is longer than the width its type fixes");
    }

    /** Walks field tree and record together, measuring leaves that fix a width. */
    private void checkWidths(List<AsnField> fields, Map<String, Object> record, String path,
                             List<String> problems, int[] counters) {
        for (AsnField field : fields) {
            Object value = record.get(field.getFieldName());
            if (Objects.isNull(value)) {
                continue;
            }
            String here = path + "." + field.getFieldName();

            if (field.getChildren() != null && !field.getChildren().isEmpty()) {
                for (Map<String, Object> child : asRecords(value)) {
                    checkWidths(field.getChildren(), child, here, problems, counters);
                }
                continue;
            }

            String type = field.getFieldType();
            // SIZE counts CHARACTERS only for a character string. On an INTEGER
            // it bounds the byte width, and on an OCTET STRING it counts bytes
            // while the text is a hex dump - two characters each. This is the
            // same condition BerEncoderService pads under: case STRING only.
            if (Objects.isNull(type)
                    || BerPrimitiveType.fromTypeExpression(type) != BerPrimitiveType.STRING) {
                continue;
            }
            for (String raw : asValues(value)) {
                counters[0]++;
                // The record map still holds the ASN.1 literal - "ABC" with its
                // quotes - and the writer calls strip() before the value ever
                // reaches a line. Measuring the raw form counts two characters
                // that are never written.
                String text = AsnLiteralFormatter.strip(raw);
                sizes.extractFixedLength(type).ifPresent(width -> {
                    counters[1]++;
                    if (text.length() > width) {
                        problems.add(here + ": SIZE(" + width + ") but the value is "
                                + text.length() + " characters (" + type + ")");
                    }
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asRecords(Object value) {
        if (value instanceof Map<?, ?> map) {
            return List.of((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Map<String, Object>> records = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    records.add((Map<String, Object>) map);
                }
            }
            return records;
        }
        return List.of();
    }

    private List<String> asValues(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of(String.valueOf(value));
    }

    /** Leaf values of a record tree, flattened the way the writer flattens it. */
    private static List<String> flattenValues(Object node) {
        List<String> values = new ArrayList<>();
        if (node instanceof Map<?, ?> map) {
            map.values().forEach(value -> values.addAll(flattenValues(value)));
        } else if (node instanceof List<?> list) {
            list.forEach(element -> values.addAll(flattenValues(element)));
        } else if (Objects.nonNull(node)) {
            values.add(String.valueOf(node));
        }
        return values;
    }

    /** Columns on one line: separators plus one, so an empty trailing cell counts. */
    private static int fieldCount(String line) {
        int count = 1;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '|') {
                count++;
            }
        }
        return count;
    }
}
