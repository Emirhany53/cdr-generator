package com.turkcell.cdrgenerator1.model.request;

import lombok.Data;

import java.util.Map;

/**
 * /api/cdr/generate istegi (JSON'da VAR olan bir yapidan .dat uretir).
 * Girdi = yapinin adi.
 */
@Data
public class GenerateRequest {
    private String structureName;          // datastructure.json'daki yapi adi
    private Map<String, String> fieldValues; // opsiyonel manuel degerler
    private Integer recordCount;           // opsiyonel kayit sayisi
}