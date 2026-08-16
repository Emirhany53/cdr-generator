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
    /**
     * Fixed, not {@link System#lineSeparator()}. The record separator is part of
     * the format the consumer parses, not a property of the machine that wrote
     * the file: {@code Multicloud} - the only externally confirmed Token-Separated
     * output this project has - was accepted with {@code \n}. Writing through
     * {@code Files.write(path, lines, charset)} would have emitted CRLF on a
     * Windows host and shipped a different format from the same source.
     */
    private static final String RECORD_SEPARATOR = "\n";
    private static final String PATH_SEPARATOR = ".";
    private static final String INDEX_OPEN = "[";
    private static final String INDEX_CLOSE = "]";
    private static final String EMPTY_PATH = "";
    private static final String EMPTY_VALUE = "";
    private static final String FILE_NAME_PREFIX_SUFFIX = "_";
    /** Token-Separated text; {@code .dat} is the binary BER copy's name now. */
    private static final String FILE_NAME_EXTENSION = ".txt";
    private static final char MIN_PRINTABLE_ASCII = 0x20;
    private static final char MAX_PRINTABLE_ASCII = 0x7E;

    /**
     * Renders the records and the column map they produced, without touching disk.
     *
     * <p>This is what {@code /generate} serves. Rendering in memory is not an
     * optimisation - {@code writeCdrFile} handed back a
     * {@link Files#createTempFile} that nothing ever deleted, so every download
     * left a file behind in the system temp directory for the life of the host.</p>
     */
    public TextGenerationResult render(List<Map<String, Object>> records) {
        List<Map<String, String>> flattenedRecords = records.stream()
                .map(this::flattenRecord)
                .toList();

        List<String> columns = List.copyOf(collectColumns(flattenedRecords));

        StringBuilder text = new StringBuilder();
        for (Map<String, String> record : flattenedRecords) {
            text.append(toLine(record, columns)).append(RECORD_SEPARATOR);
        }
        return new TextGenerationResult(columns, text.toString());
    }

    /**
     * The same rendering, left in a temporary file.
     *
     * <p>Kept for callers that genuinely want a file on disk - the validation
     * sample writer copies it into {@code target/validation}. The endpoint does
     * not use this path, so no download creates a temporary file any more. A
     * caller of this method owns the file it gets back.</p>
     */
    public Path writeCdrFile(String structureName, List<Map<String, Object>> records) throws IOException {
        TextGenerationResult rendered = render(records);

        Path filePath = Files.createTempFile(
                structureName + FILE_NAME_PREFIX_SUFFIX, FILE_NAME_EXTENSION);
        Files.write(filePath, rendered.text().getBytes(StandardCharsets.US_ASCII));

        log.info("{} kayit ASCII olarak yazildi: {}", records.size(), filePath);
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
        target.put(path, checked(path, AsnLiteralFormatter.strip(node)));
    }

    /**
     * Refuses a value the format cannot carry, naming the column it came from.
     *
     * <p>Three characters break a Token-Separated line and there is no escaping
     * convention to fall back on: {@code |} silently turns one column into two,
     * a line break turns one record into two, and a byte US-ASCII cannot hold
     * used to surface as {@code UnmappableCharacterException: Input length = 1}
     * from deep inside the writer - true, but it named neither the field nor the
     * character, and 48 modules declare {@code UTF8String} whose values come
     * from the user or from the AI provider.</p>
     *
     * <p>Refusing rather than escaping is deliberate. An escape convention would
     * change the bytes a consumer reads, and {@code Multicloud} - the only
     * external confirmation this format has - carried none. Corrupting the file
     * silently is the one outcome worth ruling out; naming the offender lets the
     * caller fix the value.</p>
     */
    private String checked(String column, String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '|') {
                throw new IllegalArgumentException(rejection(column, value,
                        "alan ayraci '|' iceriyor; Token-Separated bicimde kacis yoktur, "
                                + "bu deger tek bir kolonu ikiye bolerdi"));
            }
            if (character == '\n' || character == '\r') {
                throw new IllegalArgumentException(rejection(column, value,
                        "satir sonu karakteri iceriyor; her kayit tek satirdir, "
                                + "bu deger kaydi ikiye bolerdi"));
            }
            if (character < MIN_PRINTABLE_ASCII || character > MAX_PRINTABLE_ASCII) {
                throw new IllegalArgumentException(rejection(column, value,
                        "US-ASCII disinda karakter iceriyor ('" + character + "', U+"
                                + String.format("%04X", (int) character)
                                + "); .txt ciktisi US-ASCII yazilir"));
            }
        }
        return value;
    }

    private String rejection(String column, String value, String reason) {
        return "'" + column + "' alani .txt ciktisina yazilamaz: " + reason
                + ". Deger: \"" + value + "\"";
    }

    /** Tum kayitlarin sutunlarini gorulme sirasini koruyarak birlestirir. */
    private Set<String> collectColumns(List<Map<String, String>> flattenedRecords) {
        Set<String> columns = new LinkedHashSet<>();
        flattenedRecords.forEach(record -> columns.addAll(record.keySet()));
        return columns;
    }

    private String toLine(Map<String, String> record, List<String> columns) {
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
}
