package com.turkcell.cdrgenerator1.generator.source;

import lombok.Value;
import lombok.With;

import java.util.List;
import java.util.Map;

@Value
public class ValueSourceContext {

    String structureName;

    int recordIndex;

    Map<String, String> userProvidedValues;

    List<Map<String, String>> aiGeneratedRecords;

    /** Agacta su an bulunulan alan yolu. Ornek: "addr.msisdn". */
    @With
    String currentPath;

    public ValueSourceContext(String structureName, int recordIndex,
                              Map<String, String> userProvidedValues,
                              List<Map<String, String>> aiGeneratedRecords) {
        this(structureName, recordIndex, userProvidedValues, aiGeneratedRecords, null);
    }

    public ValueSourceContext(String structureName, int recordIndex,
                              Map<String, String> userProvidedValues,
                              List<Map<String, String>> aiGeneratedRecords,
                              String currentPath) {
        this.structureName = structureName;
        this.recordIndex = recordIndex;
        this.userProvidedValues = userProvidedValues;
        this.aiGeneratedRecords = aiGeneratedRecords;
        this.currentPath = currentPath;
    }
}