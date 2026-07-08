package com.turkcell.cdrgenerator1.generator;

import com.turkcell.cdrgenerator1.model.AsnField;
import com.turkcell.cdrgenerator1.model.AsnStructure;
import com.turkcell.cdrgenerator1.service.StructureParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CdrRecordBuilder {

    private final StructureParserService structureParserService;
    private final FieldValueGenerator fieldValueGenerator;

    public Map<String, String> buildRecord(String structureName, Map<String, String> userValues) {
        log.debug("Building record for structure: {}", structureName);

        AsnStructure structure = structureParserService.getStructureByName(structureName);

        if (structure == null) {
            log.error("Structure not found: {}", structureName);
            throw new IllegalArgumentException("Structure not found: " + structureName);
        }

        Map<String, String> recordMap = new LinkedHashMap<>();

        for (AsnField field : structure.getFields()) {
            String fieldName = field.getFieldName();

            if (userValues != null && userValues.containsKey(fieldName)) {
                recordMap.put(fieldName, userValues.get(fieldName));
            } else {
                String generatedValue = fieldValueGenerator.generateValue(fieldName, field.getFieldType());
                recordMap.put(fieldName, generatedValue);
            }
        }

        return recordMap;
    }
}