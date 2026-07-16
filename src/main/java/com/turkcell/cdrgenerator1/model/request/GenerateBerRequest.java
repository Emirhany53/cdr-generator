package com.turkcell.cdrgenerator1.model.request;

import lombok.Data;

import java.util.Map;

/**
 * /api/cdr/generate-ber request (produces a .ber, incl. structures NOT in the JSON).
 * Either give a registered {@code structureName} (named mode) or the raw ASN.1
 * {@code contents} text directly (inline mode).
 */
@Data
public class GenerateBerRequest {
    private String structureName;            // structure name from the JSON (named mode)
    private String contents;                 // raw ASN.1 text (inline mode)
    private Map<String, String> fieldValues; // optional manual values; keys may be a bare
                                             // field name or a dotted/indexed path
    private Map<String, String> choiceSelections; // optional CHOICE branch per CHOICE type name
    private Integer recordCount;             // optional record count

    /**
     * Convenience alias so a request body using the JSON key {@code "content"}
     * (singular) still populates {@code contents}. Jackson binds the "content"
     * property to this setter.
     */
    public void setContent(String content) {
        this.contents = content;
    }
}
