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
        if (structure == null) {
            log.error("Structure not found: {}", structureName);
            throw new StructureNotFoundException(structureName);
        }

        return buildFields(structure.getFields(), userValues);
    }

    private Map<String, Object> buildFields(List<AsnField> fields, Map<String, String> userValues) {
        Map<String, Object> record = new LinkedHashMap<>();

        for (AsnField field : fields) {
            String fieldName = field.getFieldName();

            if (field.getChildren() != null && !field.getChildren().isEmpty()) {
                record.put(fieldName, field.isRepeated()
                        ? buildRepeatedGroup(field, userValues)
                        : buildFields(field.getChildren(), userValues));
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

    private String resolveLeafValue(AsnField field, Map<String, String> userValues) {
        String fieldName = field.getFieldName();
        String rawValue = (userValues != null && userValues.containsKey(fieldName))
                ? userValues.get(fieldName)
                : fieldValueGenerator.generateValue(fieldName, field.getFieldType());
        return formatAsnLiteral(rawValue, field.getFieldType());
    }

    private String formatAsnLiteral(String rawValue, String fieldType) {
        if (fieldType == null) {
            return "\"" + rawValue + "\"";
        }
        String upperType = fieldType.toUpperCase();
        if (upperType.contains("INTEGER") || upperType.contains("BOOLEAN")) {
            return "'" + rawValue + "'D";
        }
        return "\"" + rawValue + "\"";
    }
}