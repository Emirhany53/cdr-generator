package com.turkcell.cdrgenerator1.model.request;

import lombok.Data;

import java.util.Map;

/**
 * /api/cdr/generate-ber istegi (JSON'da OLMAYAN yapilar icin .ber uretir).
 * Girdi = yapinin adi DEGIL, ham ASN.1 contents metnidir.
 * Kullanici contents'i dogrudan istekte gonderir.
 */
@Data
public class GenerateBerRequest {
    private String structureName;           // JSON'daki yapi adi (named mod)
    private String contents;               // ham ASN.1 metni (inline mod)
    private Map<String, String> fieldValues; // opsiyonel manuel degerler
    private Integer recordCount;           // opsiyonel kayit sayisi
}