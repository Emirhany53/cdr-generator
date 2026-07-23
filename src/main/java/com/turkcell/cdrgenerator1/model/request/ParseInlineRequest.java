package com.turkcell.cdrgenerator1.model.request;

import lombok.Data;

import java.util.Map;

/**
 * /api/cdr/structures/parse-inline request body.
 * Yüklenen ya da yapıştırılan ham ASN.1 metnini parse etmek için kullanılır;
 * hiçbir dosya üretmez, sadece alan ağacını (AsnStructure) döner.
 */
@Data
public class ParseInlineRequest {

    /** Opsiyonel görünen ad. Boşsa parser kök tip adını kullanır. */
    private String structureName;

    /** Ham ASN.1 metni (zorunlu). */
    private String contents;

    /** Opsiyonel: CHOICE tipleri için hangi alternatifin seçileceği. */
    private Map<String, String> choiceSelections;


    public void setContent(String content) {
        this.contents = content;
    }
}