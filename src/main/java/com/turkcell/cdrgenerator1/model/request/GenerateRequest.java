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
     * Referans modu; {@code GenerateBerRequest#referenceMode} ile ayni anlam.
     * Bu alan eksikken metin ucu her iki cagriya da {@code false} geciyordu:
     * {@code path[0].sIP-URI} / {@code path[1].tEL-URI} seklinde tarif edilen
     * tekrarli bir CHOICE {@code .ber}'de iki instance, {@code .txt}'de hic
     * cikmiyordu. Varsayilan false - mevcut cagiranlarin davranisi degismez.
     */
    private boolean referenceMode;
}