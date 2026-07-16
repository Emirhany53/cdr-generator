package com.turkcell.cdrgenerator1.model.request;

import lombok.Data;

import java.util.Map;

/**
 * /api/cdr/generate request (produces a .dat from a structure that EXISTS in the JSON).
 * Input = the structure name.
 */
@Data
public class GenerateRequest {
    private String structureName;            // structure name from datastructure.json
    private Map<String, String> fieldValues; // optional manual values; keys may be a bare
                                             // field name or a dotted/indexed path such as
                                             // "location.cellId" or "partials[0].volume"
    private Map<String, String> choiceSelections; // optional CHOICE branch per CHOICE type name
    private Integer recordCount;             // optional record count
}
