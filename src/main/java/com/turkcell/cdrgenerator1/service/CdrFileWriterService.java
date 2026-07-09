package com.turkcell.cdrgenerator1.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CdrFileWriterService {

    private static final String INDENT_UNIT = "    ";

    public Path writeCdrFile(String structureName, List<Map<String, Object>> records) throws IOException {
        log.info("Generating CDR file for structure: {} with {} records", structureName, records.size());

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> record : records) {
            sb.append(structureName).append('\n');
            sb.append("{\n");
            appendFields(record, 1, sb);
            sb.append("}\n");
        }

        Path tempFile = Files.createTempFile(structureName + "_", ".dat");
        Files.writeString(tempFile, sb.toString());

        log.info("CDR file successfully written to: {}", tempFile.toAbsolutePath());
        return tempFile;
    }

    @SuppressWarnings("unchecked")
    private void appendFields(Map<String, Object> record, int depth, StringBuilder sb) {
        String indent = INDENT_UNIT.repeat(depth);

        for (Map.Entry<String, Object> entry : record.entrySet()) {
            Object value = entry.getValue();

            if (value instanceof Map<?, ?> nested) {
                sb.append(indent).append(entry.getKey()).append('\n');
                sb.append(indent).append("{\n");
                appendFields((Map<String, Object>) nested, depth + 1, sb);
                sb.append(indent).append("}\n");
            } else if (value instanceof List<?> items) {
                sb.append(indent).append(entry.getKey()).append('\n');
                sb.append(indent).append("{\n");
                appendRepeatedItems((List<Map<String, Object>>) items, depth + 1, sb);
                sb.append(indent).append("}\n");
            } else {
                sb.append(indent).append(entry.getKey()).append(" : ").append(value).append('\n');
            }
        }
    }

    private void appendRepeatedItems(List<Map<String, Object>> items, int depth, StringBuilder sb) {
        String indent = INDENT_UNIT.repeat(depth);
        for (int i = 0; i < items.size(); i++) {
            sb.append(indent).append('[').append(i).append("]\n");
            sb.append(indent).append("{\n");
            appendFields(items.get(i), depth + 1, sb);
            sb.append(indent).append("}\n");
        }
    }
}