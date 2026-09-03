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
     * The module type to encode as the record, overriding the auto-selected
     * root. Needed when a module defines several top types and the consuming
     * system is bound to one of them - EMM's CSCFColl flow decodes
     * {@code IMSCDRS.TokensCSCF}, not the {@code Cdrs} CHOICE that wraps it.
     * Ignored when the module defines no such type.
     */
    private String rootType;

    /**
     * Which name the identical BER bytes are offered under: {@code ber} (the
     * default) or {@code dat}. The supervisor's flow wants both, byte for byte
     * the same, so this changes the Content-Disposition file name and NOTHING
     * else - the response body is the array the encoder produced either way.
     */
    private String extension;

    /**
     * Reference-driven generation. When true, an OPTIONAL field that
     * {@code fieldValues} never mentions - by full path, by index, or by bare
     * name - is not generated at all, so the record carries what the reference
     * carries instead of a random value for every optional field the schema
     * allows. Mandatory fields are unaffected.
     *
     * <p>Defaults to false, which is the behaviour every existing caller
     * already gets.</p>
     */
    private boolean referenceMode;

    /**
     * Convenience alias so a request body using the JSON key {@code "content"}
     * (singular) still populates {@code contents}. Jackson binds the "content"
     * property to this setter.
     */
    public void setContent(String content) {
        this.contents = content;
    }
}
