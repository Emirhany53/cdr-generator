package com.turkcell.cdrgenerator1.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the {@code .txt} column layout depends on.
 *
 * <p>A Token-Separated file is read by position: the consumer counts pipes and
 * takes field N from column N. That only works if column N means the same thing
 * in every file the same structure produces. {@code CdrFileWriterService} does
 * not derive its columns from the schema - it takes the union of the keys the
 * RECORDS happen to carry, in the order they are first seen:</p>
 *
 * <pre>
 * Set&lt;String&gt; columns = collectColumns(flattenedRecords);
 * </pre>
 *
 * <p>So the layout is a property of the data, not of the structure, and these
 * tests pin down exactly how far that goes. They are written to describe what
 * the writer does today rather than what it ought to do: the {@code .txt} path
 * has one external confirmation ({@code Multicloud}) and nothing has told us
 * which of these behaviours EMM depends on. Naming them is the point - a
 * behaviour with a test around it can be discussed; one without is a surprise
 * waiting for a round.</p>
 */
class TextColumnStabilityTest {

    private final CdrFileWriterService writer = new CdrFileWriterService();

    private Map<String, Object> record(Object... keyValues) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            record.put((String) keyValues[i], keyValues[i + 1]);
        }
        return record;
    }

    private List<String> columnsOf(Path file) throws IOException {
        return List.of(Files.readAllLines(file).get(0).split("\\|", -1));
    }

    /**
     * The stable case, and the one most modules are in: every record carries the
     * same keys, so every file of that structure has the same columns.
     */
    @Test
    void recordsCarryingTheSameKeysProduceTheSameLayout() throws IOException {
        Path one = writer.writeCdrFile("Sample", List.of(record("a", "1", "b", "2", "c", "3")));
        Path many = writer.writeCdrFile("Sample", List.of(
                record("a", "1", "b", "2", "c", "3"),
                record("a", "4", "b", "5", "c", "6")));

        assertThat(columnsOf(one)).hasSize(3);
        assertThat(columnsOf(many)).hasSize(3);
    }

    /**
     * A key no record carries is not a column at all - the width shrinks rather
     * than the field arriving empty.
     *
     * <p>For a structure with OPTIONAL members (751 of 802) that means the file's
     * width depends on which OPTIONALs the generator filled, and a consumer
     * counting pipes gets a different answer per file.</p>
     */
    @Test
    void aKeyNoRecordCarriesIsNotAColumn() throws IOException {
        Path withB = writer.writeCdrFile("Sample", List.of(record("a", "1", "b", "2", "c", "3")));
        Path withoutB = writer.writeCdrFile("Sample", List.of(record("a", "1", "c", "3")));

        assertThat(columnsOf(withB)).hasSize(3);
        assertThat(columnsOf(withoutB))
                .as("the b column disappears entirely rather than arriving empty")
                .hasSize(2);
    }

    /**
     * A key only SOME records carry becomes a column, and the records without it
     * get an empty value - so within one file the rows do stay aligned.
     */
    @Test
    void aKeySomeRecordsCarryBecomesAnEmptyColumnForTheRest() throws IOException {
        Path file = writer.writeCdrFile("Sample", List.of(
                record("a", "1", "b", "2"),
                record("a", "3")));

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).containsExactly("1|2", "3|");
    }

    /**
     * The ordering consequence, and the sharpest of these: a key the FIRST record
     * lacks is appended after every column that record did carry, so it lands out
     * of declaration order.
     *
     * <p>Both files below describe the same three fields. Read by position they
     * disagree - column 2 is {@code c} in one and {@code b} in the other.</p>
     */
    @Test
    void aKeyMissingFromTheFirstRecordLandsAtTheEnd() throws IOException {
        Path bFirst = writer.writeCdrFile("Sample", List.of(
                record("a", "1", "b", "2", "c", "3"),
                record("a", "4", "b", "5", "c", "6")));
        Path bLate = writer.writeCdrFile("Sample", List.of(
                record("a", "1", "c", "3"),
                record("a", "4", "b", "5", "c", "6")));

        assertThat(Files.readAllLines(bFirst).get(1))
                .as("a|b|c")
                .isEqualTo("4|5|6");
        assertThat(Files.readAllLines(bLate).get(1))
                .as("a|c|b - the same fields, a different order")
                .isEqualTo("4|6|5");
    }

    /**
     * A collection contributes one column per ELEMENT, named by index, so the
     * width follows how many elements the generator produced.
     *
     * <p>186 modules declare a {@code SEQUENCE OF}, and the generator's repeat
     * count is not fixed - which is why {@code MMTelChargingDataTypes} writes 236
     * columns from 203 leaves.</p>
     */
    @Test
    void aCollectionContributesOneColumnPerElement() throws IOException {
        Path twoElements = writer.writeCdrFile("Sample",
                List.of(record("list", List.of("x", "y"))));
        Path threeElements = writer.writeCdrFile("Sample",
                List.of(record("list", List.of("x", "y", "z"))));

        assertThat(columnsOf(twoElements)).hasSize(2);
        assertThat(columnsOf(threeElements)).hasSize(3);
    }

    /** Nested bodies flatten onto a dotted path, one column per leaf. */
    @Test
    void nestedBodiesFlattenOntoADottedPath() throws IOException {
        Path file = writer.writeCdrFile("Sample",
                List.of(record("outer", record("inner", "1", "other", "2"), "flat", "3")));

        assertThat(Files.readString(file).stripTrailing()).isEqualTo("1|2|3");
    }
}
