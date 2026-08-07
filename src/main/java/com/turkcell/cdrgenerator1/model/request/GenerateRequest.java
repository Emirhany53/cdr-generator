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
}