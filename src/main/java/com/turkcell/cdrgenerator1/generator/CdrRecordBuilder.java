package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.exception.StructureNotFoundException;
import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Slf4j
public class CdrRecordBuilder {

    private static final int MIN_REPEAT_COUNT = 1;
    private static final int MAX_REPEAT_COUNT = 2;
    private static final String PATH_SEPARATOR = ".";
    private static final String INDEX_OPEN = "[";
    private static final String INDEX_CLOSE = "]";

    private final StructureParserService structureParserService;
    private final FieldValueGenerator fieldValueGenerator;
    private final Random random = new Random();

    public Map<String, Object> buildRecord(String structureName, Map<String, String> userValues) {
        log.debug("Building record for structure: {}", structureName);

        AsnStructure structure = structureParserService.getStructureByName(structureName);
        if (Objects.isNull(structure)) {
            log.error("Structure not found: {}", structureName);
            throw new StructureNotFoundException(structureName);
        }

        return buildFields(structure.getFields(), userValues, "");
    }

    /**
     * Builds a record honouring an explicit CHOICE selection. With an empty
     * selection this behaves exactly like {@link #buildRecord(String, Map)}.
     */
    public Map<String, Object> buildRecord(String structureName, Map<String, String> userValues,
                                           Map<String, String> choiceSelections) {
        if (Objects.isNull(choiceSelections) || choiceSelections.isEmpty()) {
            return buildRecord(structureName, userValues);
        }

        AsnStructure structure = structureParserService.getStructureByName(structureName, choiceSelections);
        if (Objects.isNull(structure)) {
            log.error("Structure not found: {}", structureName);
            throw new StructureNotFoundException(structureName);
        }

        return buildFields(structure.getFields(), userValues, "");
    }

    /**
     * Builds a record directly from an already-resolved field list, without a
     * registry lookup. Used for structures parsed from inline request content.
     */
    public Map<String, Object> buildRecordFromFields(List<AsnField> fields, Map<String, String> userValues) {
        return buildFields(fields, userValues, "");
    }

    private Map<String, Object> buildFields(List<AsnField> fields, Map<String, String> userValues,
                                            String pathPrefix) {
        Map<String, Object> record = new LinkedHashMap<>();

        for (AsnField field : fields) {
            String fieldName = field.getFieldName();
            String fieldPath = buildPath(pathPrefix, fieldName);

            if (Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty()) {
                record.put(fieldName, field.isRepeated()
                        ? buildRepeatedGroup(field, userValues, fieldPath)
                        : buildFields(field.getChildren(), userValues, fieldPath));
            } else if (field.isRepeated()) {
                // SEQUENCE OF <primitive>: emit a real list so both the BER
                // encoder and the flattened ASCII writer see the elements.
                record.put(fieldName, buildRepeatedLeaf(field, userValues, fieldPath));
            } else {
                record.put(fieldName, resolveLeafValue(field, userValues, fieldPath));
            }
        }
        return record;
    }

    private List<Map<String, Object>> buildRepeatedGroup(AsnField field, Map<String, String> userValues,
                                                         String fieldPath) {
        int repeatCount = randomRepeatCount();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < repeatCount; i++) {
            String elementPath = fieldPath + INDEX_OPEN + i + INDEX_CLOSE;
            items.add(buildFields(field.getChildren(), userValues, elementPath));
        }
        return items;
    }

    /**
     * Builds the value list for a repeated primitive field. A user-supplied
     * value (matched by full path or bare field name) becomes a single-element
     * list; otherwise 1-2 values are generated.
     */
    private List<String> buildRepeatedLeaf(AsnField field, Map<String, String> userValues, String fieldPath) {
        String userValue = lookupUserValue(userValues, fieldPath, field.getFieldName());
        if (Objects.nonNull(userValue)) {
            return List.of(formatAsnLiteral(userValue, field.getFieldType()));
        }
        int repeatCount = randomRepeatCount();
        List<String> values = new ArrayList<>(repeatCount);
        for (int i = 0; i < repeatCount; i++) {
            String generated = fieldValueGenerator.generateValue(field.getFieldName(), field.getFieldType());
            values.add(formatAsnLiteral(generated, field.getFieldType()));
        }
        return values;
    }

    private String resolveLeafValue(AsnField field, Map<String, String> userValues, String fieldPath) {
        String userValue = lookupUserValue(userValues, fieldPath, field.getFieldName());
        String rawValue = Objects.nonNull(userValue)
                ? userValue
                : fieldValueGenerator.generateValue(field.getFieldName(), field.getFieldType());
        return formatAsnLiteral(rawValue, field.getFieldType());
    }

    /**
     * Looks up a user-supplied value first by its full dotted/indexed path
     * (e.g. {@code addr.msisdn}) and then by the bare field name. The path form
     * lets callers disambiguate nested or duplicated leaf names; the bare name
     * stays supported for convenience and backward compatibility.
     */
    private String lookupUserValue(Map<String, String> userValues, String fieldPath, String fieldName) {
        if (Objects.isNull(userValues)) {
            return null;
        }
        if (userValues.containsKey(fieldPath)) {
            return userValues.get(fieldPath);
        }
        if (userValues.containsKey(fieldName)) {
            return userValues.get(fieldName);
        }
        return null;
    }

    private int randomRepeatCount() {
        return MIN_REPEAT_COUNT + random.nextInt(MAX_REPEAT_COUNT - MIN_REPEAT_COUNT + 1);
    }

    private String buildPath(String prefix, String fieldName) {
        return prefix.isEmpty() ? fieldName : prefix + PATH_SEPARATOR + fieldName;
    }

    private String formatAsnLiteral(String rawValue, String fieldType) {
        if (Objects.isNull(fieldType)) {
            return "\"" + rawValue + "\"";
        }
        String upperType = fieldType.toUpperCase(java.util.Locale.ENGLISH);
        if (upperType.contains("INTEGER")) {
            return "'" + rawValue + "'D";
        }
        return "\"" + rawValue + "\"";
    }
}
