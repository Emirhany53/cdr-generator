package com.turkcell.cdrgenerator1.infrastructure.ai.gemini;

import com.turkcell.cdrgenerator1.ai.util.AsnSizeExtractor;
import com.turkcell.cdrgenerator1.model.AsnField;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GeminiResponseSchemaFactory {

    private static final String TYPE_KEY = "type";
    private static final String TYPE_ARRAY = "ARRAY";
    private static final String TYPE_OBJECT = "OBJECT";
    private static final String TYPE_STRING = "STRING";
    private static final String ITEMS_KEY = "items";
    private static final String PROPERTIES_KEY = "properties";
    private static final String REQUIRED_KEY = "required";
    private static final String DESCRIPTION_KEY = "description";
    private static final String MAX_LENGTH_HINT = "Azami %d karakter.";

    private final AsnSizeExtractor asnSizeExtractor;

    public Map<String, Object> create(List<AsnField> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();

        fields.forEach(field -> {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put(TYPE_KEY, TYPE_STRING);
            asnSizeExtractor.extractMaxLength(field.getFieldType()).ifPresent(maxLength ->
                    property.put(DESCRIPTION_KEY, MAX_LENGTH_HINT.formatted(maxLength)));
            properties.put(field.getFieldName(), property);
        });

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put(TYPE_KEY, TYPE_OBJECT);
        itemSchema.put(PROPERTIES_KEY, properties);
        itemSchema.put(REQUIRED_KEY, fields.stream().map(AsnField::getFieldName).toList());

        Map<String, Object> rootSchema = new LinkedHashMap<>();
        rootSchema.put(TYPE_KEY, TYPE_ARRAY);
        rootSchema.put(ITEMS_KEY, itemSchema);
        return rootSchema;
    }
}