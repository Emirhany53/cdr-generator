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

        return buildFields(structure.getFields(), userValues);
    }

    /**
     * Builds a record directly from an already-resolved field list, without a
     * registry lookup. Used for structures parsed from inline request content.
     */
    public Map<String, Object> buildRecordFromFields(List<AsnField> fields, Map<String, String> userValues) {
        return buildFields(fields, userValues);
    }

    private Map<String, Object> buildFields(List<AsnField> fields, Map<String, String> userValues) {
        Map<String, Object> record = new LinkedHashMap<>();

        for (AsnField field : fields) {
            String fieldName = field.getFieldName();

            if (Objects.nonNull(field.getChildren()) && !field.getChildren().isEmpty()) {
                record.put(fieldName, field.isRepeated()
                        ? buildRepeatedGroup(field, userValues)
                        : buildFields(field.getChildren(), userValues));
            } else if (field.isRepeated()) {
                // SEQUENCE OF <primitive>: emit a real list so both the BER
                // encoder and the flattened ASCII writer see the elements.
                record.put(fieldName, buildRepeatedLeaf(field, userValues));
            } else {
                record.put(fieldName, resolveLeafValue(field, userValues));
            }
        }
        return record;
    }

    private List<Map<String, Object>> buildRepeatedGroup(AsnField field, Map<String, String> userValues) {
        int repeatCount = MIN_REPEAT_COUNT + random.nextInt(MAX_REPEAT_COUNT - MIN_REPEAT_COUNT + 1);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < repeatCount; i++) {
            items.add(buildFields(field.getChildren(), userValues));
        }
        return items;
    }

    /**
     * Builds the value list for a repeated primitive field. A user-supplied
     * value becomes a single-element list; otherwise 1-2 values are generated.
     */
    private List<String> buildRepeatedLeaf(AsnField field, Map<String, String> userValues) {
        if (Objects.nonNull(userValues) && userValues.containsKey(field.getFieldName())) {
            return List.of(resolveLeafValue(field, userValues));
        }
        int repeatCount = MIN_REPEAT_COUNT + random.nextInt(MAX_REPEAT_COUNT - MIN_REPEAT_COUNT + 1);
        List<String> values = new ArrayList<>(repeatCount);
        for (int i = 0; i < repeatCount; i++) {
            values.add(resolveLeafValue(field, null));
        }
        return values;
    }

    private String resolveLeafValue(AsnField field, Map<String, String> userValues) {
        String fieldName = field.getFieldName();
        String rawValue = (Objects.nonNull(userValues) && userValues.containsKey(fieldName))
                ? userValues.get(fieldName)
                : fieldValueGenerator.generateValue(fieldName, field.getFieldType());
        return formatAsnLiteral(rawValue, field.getFieldType());
    }

    private String formatAsnLiteral(String rawValue, String fieldType) {
        if (Objects.isNull(fieldType)) {
            return "\"" + rawValue + "\"";
        }
        String upperType = fieldType.toUpperCase();
        if (upperType.contains("INTEGER")) {
            return "'" + rawValue + "'D";
        }
        // BOOLEAN stays a quoted "1"/"0" so the BER encoder can emit the
        // canonical BOOLEAN content bytes instead of an INTEGER literal.
        return "\"" + rawValue + "\"";
    }
}