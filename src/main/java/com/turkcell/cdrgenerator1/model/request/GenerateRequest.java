package com.turkcell.cdrgenerator1.model.request;

import lombok.Data;

import java.util.Map;

@Data
public class GenerateRequest {

    private String structureName;

    /** Kayitli olmayan bir semayi dogrudan gonderme yolu (inline mod). */
    private String contents;

    private Map<String, String> fieldValues;

    private Map<String, String> choiceSelections;

    /**
     * Opsiyonel: kaydin kokü sayilacak tip adi. Modul birden cok ust tip
     * tanimliyorsa ve tuketen sistem bunlardan birine bagliysa gerekir;
     * modulde boyle bir tip yoksa yok sayilir.
     */
    private String rootType;

    private Integer recordCount;

    /**
     * Reference-driven generation - the same flag and the same meaning as
     * {@code GenerateBerRequest#referenceMode}, which this endpoint was missing.
     * Both formats resolve the structure through
     * {@code StructureParserService.getStructureByName(..., fieldValues, referenceMode)}
     * and build records through
     * {@code CdrRecordBuilder.buildRecordFromFields(..., referenceMode)}; without
     * the field here the text endpoint silently passed {@code false} to both, so
     * a repeated CHOICE described as {@code path[0].sIP-URI} / {@code path[1].tEL-URI}
     * came out of {@code .ber} with two instances and out of {@code .txt} with
     * none. Defaults to false, which is what every existing caller already gets.
     */
    private boolean referenceMode;
}