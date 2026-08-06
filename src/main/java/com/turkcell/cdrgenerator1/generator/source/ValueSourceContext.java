package com.turkcell.cdrgenerator1.generator.source;

import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Value
public class ValueSourceContext {

    String structureName;

    int recordIndex;

    Map<String, String> userProvidedValues;

    List<Map<String, String>> aiGeneratedRecords;

    /**
     * Kaydin kronoloji capasi: kayittaki tum zaman damgalari bu tek andan
     * turetilir (serviceDeliveryStart <= End garantisi). Kayit basina bir kez
     * kurulur; null ise zaman damgalari bagimsiz rastgele uretilir (eski davranis).
     */
    LocalDateTime recordAnchor;

    /** Agacta su an bulunulan alan yolu. Ornek: "addr.msisdn". */
    String currentPath;

    public ValueSourceContext(String structureName, int recordIndex,
                              Map<String, String> userProvidedValues,
                              List<Map<String, String>> aiGeneratedRecords) {
        this(structureName, recordIndex, userProvidedValues, aiGeneratedRecords, null, null);
    }

    public ValueSourceContext(String structureName, int recordIndex,
                              Map<String, String> userProvidedValues,
                              List<Map<String, String>> aiGeneratedRecords,
                              LocalDateTime recordAnchor) {
        this(structureName, recordIndex, userProvidedValues, aiGeneratedRecords, recordAnchor, null);
    }

    public ValueSourceContext(String structureName, int recordIndex,
                              Map<String, String> userProvidedValues,
                              List<Map<String, String>> aiGeneratedRecords,
                              LocalDateTime recordAnchor,
                              String currentPath) {
        this.structureName = structureName;
        this.recordIndex = recordIndex;
        this.userProvidedValues = userProvidedValues;
        this.aiGeneratedRecords = aiGeneratedRecords;
        this.recordAnchor = recordAnchor;
        this.currentPath = currentPath;
    }

    /**
     * Yol disinda her seyi koruyarak yeni bir baglam dondurur. Elle yazildi:
     * recordAnchor'in de tasindigini garanti eder ve Lombok @With'in alan
     * sirasina bagimliligini ortadan kaldirir.
     */
    public ValueSourceContext withCurrentPath(String currentPath) {
        return new ValueSourceContext(structureName, recordIndex, userProvidedValues,
                aiGeneratedRecords, recordAnchor, currentPath);
    }
}
