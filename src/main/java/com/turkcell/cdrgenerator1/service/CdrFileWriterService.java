package com.turkcell.cdrgenerator1.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Writes generated CDR records to a Token-Separated-ASCII (.txt) file.
 *
 * <p>Output format (per the project specification):
 * <ul>
 *   <li>Fields are separated by the pipe ('|') character.</li>
 *   <li>Each line is a single flattened CDR record.</li>
 * </ul>
 *
 * <p>Nested and repeated fields are flattened depth-first into a single row.
 * Header names use dotted paths (e.g. {@code location.cellId}) and repeated
 * items are indexed (e.g. {@code partials[0].volume}) so every column stays
 * traceable back to its ASN.1 field.</p>
 */
@Slf4j
@Service
public class CdrFileWriterService {

    private static final String FIELD_SEPARATOR = "|";
    private static final String PATH_SEPARATOR = ".";
    private static final String TXT_FILE_SUFFIX = ".txt";
    private static final String FILE_NAME_JOINER = "_";

    public Path writeCdrFile(String structureName, List<Map<String, Object>> records) throws IOException {
        log.info("Generating token-separated CDR file for structure: {} with {} record(s)",
                structureName, records.size());

        List<LinkedHashMap<String, String>> flatRecords = new ArrayList<>();
        for (Map<String, Object> record : records) {
            LinkedHashMap<String, String> flat = new LinkedHashMap<>();
            flatten("", record, flat);
            flatRecords.add(flat);
        }

        // Column set is the ordered union across all records: repeated groups
        // have a random element count per record, so one record may contain
        // e.g. partials[1].volume while another does not. Taking only the
        // first record's keys would silently drop or misalign such columns.
        List<String> columns = new ArrayList<>();
        Set<String> seenColumns = new HashSet<>();
        for (LinkedHashMap<String, String> flat : flatRecords) {
            for (String key : flat.keySet()) {
                if (seenColumns.add(key)) {
                    columns.add(key);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (LinkedHashMap<String, String> flat : flatRecords) {
            List<String> values = new ArrayList<>(columns.size());
            for (String column : columns) {
                values.add(flat.getOrDefault(column, ""));
            }
            sb.append(String.join(FIELD_SEPARATOR, values)).append('\n');
        }

        Path tempFile = Files.createTempFile(structureName + FILE_NAME_JOINER, TXT_FILE_SUFFIX);
        Files.writeString(tempFile, sb.toString());
        // Temp .txt files are one-shot downloads; make sure they do not pile up.
        tempFile.toFile().deleteOnExit();

        log.info("CDR file successfully written to: {}", tempFile.toAbsolutePath());
        return tempFile;
    }

    /**
     * Recursively flattens a record tree into ordered leaf columns.
     * Leaf values are stripped of ASN.1 literal decoration ("..." and '..'D)
     * so the token-separated output stays clean and machine-parseable.
     */
    @SuppressWarnings("unchecked")
    private void flatten(String prefix, Map<String, Object> node, LinkedHashMap<String, String> out) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + PATH_SEPARATOR + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map<?, ?> nested) {
                flatten(path, (Map<String, Object>) nested, out);
            } else if (value instanceof List<?> items) {
                for (int i = 0; i < items.size(); i++) {
                    Object item = items.get(i);
                    if (item instanceof Map<?, ?> nestedItem) {
                        flatten(path + "[" + i + "]", (Map<String, Object>) nestedItem, out);
                    } else {
                        // Repeated primitive (SEQUENCE OF OCTET STRING etc.)
                        out.put(path + "[" + i + "]", AsnLiteralFormatter.strip(item));
                    }
                }
            } else {
                out.put(path, AsnLiteralFormatter.strip(value));
            }
        }
    }
}