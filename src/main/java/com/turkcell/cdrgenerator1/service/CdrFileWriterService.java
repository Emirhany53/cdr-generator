package com.turkcell.cdrgenerator1.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Token-Separated-ASCII CDR yazicisi: her satir bir kayit, alanlar '|' ile ayrilir.
 *
 * Kayit agaci ic ice Map ve List icerebildigi icin once noktali yollara
 * duzlestirilir (ornek: addr.ton, attrs[0]). SEQUENCE OF alanlarda kayitlar
 * farkli sayida eleman tasiyabildiginden once TUM kayitlarin sutun birlesimi
 * cikarilir; bir kayitta olmayan sutun bos birakilir, boylece satirlar hizali
 * kalir ve tuketici taraf sutun kaymasi yasamaz.
 */
@Slf4j
@Service
public class CdrFileWriterService {

    private static final String FIELD_SEPARATOR = "|";
    private static final String PATH_SEPARATOR = ".";
    private static final String INDEX_OPEN = "[";
    private static final String INDEX_CLOSE = "]";
    private static final String EMPTY_PATH = "";
    private static final String EMPTY_VALUE = "";
    private static final String FILE_NAME_PREFIX_SUFFIX = "_";
    /** Token-Separated text; {@code .dat} is the binary BER copy's name now. */
    private static final String FILE_NAME_EXTENSION = ".txt";

    public Path writeCdrFile(String structureName, List<Map<String, Object>> records) throws IOException {
        List<Map<String, String>> flattenedRecords = records.stream()
                .map(this::flattenRecord)
                .toList();

        Set<String> columns = collectColumns(flattenedRecords);

        List<String> lines = flattenedRecords.stream()
                .map(record -> toLine(record, columns))
                .toList();

        Path filePath = Files.createTempFile(
                structureName + FILE_NAME_PREFIX_SUFFIX, FILE_NAME_EXTENSION);
        Files.write(filePath, lines, StandardCharsets.US_ASCII);

        log.info("{} kayit ASCII olarak yazildi: {}", lines.size(), filePath);
        return filePath;
    }

    /** Kayit agacini "yol -> deger" seklinde duz bir haritaya cevirir. */
    private Map<String, String> flattenRecord(Map<String, Object> record) {
        Map<String, String> flattened = new LinkedHashMap<>();
        flattenNode(record, EMPTY_PATH, flattened);
        return flattened;
    }

    private void flattenNode(Object node, String path, Map<String, String> target) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> flattenNode(value, appendName(path, String.valueOf(key)), target));
            return;
        }
        if (node instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                flattenNode(list.get(index), appendIndex(path, index), target);
            }
            return;
        }
        target.put(path, AsnLiteralFormatter.strip(node));
    }

    /** Tum kayitlarin sutunlarini gorulme sirasini koruyarak birlestirir. */
    private Set<String> collectColumns(List<Map<String, String>> flattenedRecords) {
        Set<String> columns = new LinkedHashSet<>();
        flattenedRecords.forEach(record -> columns.addAll(record.keySet()));
        return columns;
    }

    private String toLine(Map<String, String> record, Set<String> columns) {
        List<String> values = new ArrayList<>(columns.size());
        columns.forEach(column -> values.add(record.getOrDefault(column, EMPTY_VALUE)));
        return String.join(FIELD_SEPARATOR, values);
    }

    private String appendName(String path, String fieldName) {
        return path.isEmpty() ? fieldName : path + PATH_SEPARATOR + fieldName;
    }

    private String appendIndex(String path, int index) {
        return path + INDEX_OPEN + index + INDEX_CLOSE;
    }

    private boolean isNull(Object node) {
        return Objects.isNull(node);
    }
}