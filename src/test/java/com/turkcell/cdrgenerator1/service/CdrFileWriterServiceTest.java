package com.turkcell.cdrgenerator1.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdrFileWriterServiceTest {

    private final CdrFileWriterService writer = new CdrFileWriterService();

    private Map<String, Object> record(Object... keyValues) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            record.put((String) keyValues[i], keyValues[i + 1]);
        }
        return record;
    }

    @Test
    void leafValuesAreStrippedAndPipeSeparated() throws IOException {
        Path file = writer.writeCdrFile("Sample",
                List.of(record("msisdn", "\"905321234567\"", "duration", "'42'D")));

        String content = Files.readString(file).stripTrailing();
        assertEquals("905321234567|42", content, "fields must be pipe-separated and stripped");
    }

    @Test
    void eachRecordIsWrittenOnItsOwnLine() throws IOException {
        Path file = writer.writeCdrFile("Sample", List.of(
                record("a", "\"1\"", "b", "\"2\""),
                record("a", "\"3\"", "b", "\"4\"")));

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertEquals("1|2", lines.get(0));
        assertEquals("3|4", lines.get(1));
    }

    @Test
    void nestedFieldsAreFlattenedWithDottedPaths() throws IOException {
        Map<String, Object> nested = record(
                "id", "\"7\"",
                "addr", record("ton", "'1'D", "number", "\"905\""));

        Path file = writer.writeCdrFile("Nested", List.of(nested));
        String content = Files.readString(file).stripTrailing();
        // Columns flatten depth-first: id | addr.ton | addr.number
        assertEquals("7|1|905", content);
    }

    @Test
    void repeatedPrimitiveElementsAreIndexed() throws IOException {
        Map<String, Object> withList = record(
                "id", "\"7\"",
                "attrs", List.of("\"A\"", "\"B\""));

        Path file = writer.writeCdrFile("Repeated", List.of(withList));
        String content = Files.readString(file).stripTrailing();
        assertEquals("7|A|B", content);
    }

    @Test
    void columnUnionCoversRecordsWithDifferingRepeatCounts() throws IOException {
        // First record has one repeated element, second has two: the column
        // union must include attrs[1] and leave it empty for the first row.
        Map<String, Object> shortRecord = record("attrs", List.of("\"A\""));
        Map<String, Object> longRecord = record("attrs", List.of("\"A\"", "\"B\""));

        Path file = writer.writeCdrFile("Uneven", List.of(shortRecord, longRecord));
        List<String> lines = Files.readAllLines(file);
        assertEquals("A|", lines.get(0), "missing repeated element must be an empty column, not a shift");
        assertEquals("A|B", lines.get(1));
    }

    @Test
    void fileNameCarriesStructureNameAndTxtSuffix() throws IOException {
        Path file = writer.writeCdrFile("MyStructure", List.of(record("a", "\"1\"")));
        String fileName = file.getFileName().toString();
        assertTrue(fileName.startsWith("MyStructure_"), "file name should start with the structure name");
        assertTrue(fileName.endsWith(".txt"), "file must have the .txt suffix");
    }
}