package com.turkcell.cdrgenerator1.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsnField {
    private String fieldName;
    private String fieldType;
    private boolean optional;
    private boolean repeated;

    /**
     * Context-class tag number from the [n] annotation in the ASN.1 line.
     * Required by the BER encoder to build the TLV tag byte(s).
     * Null when the field line carried no tag.
     */
    private Integer tagNumber;

    /**
     * True when the field is EXPLICIT-tagged, which requires an extra
     * outer TLV wrapper around the encoded value in BER.
     */
    private boolean explicit;

    private List<AsnField> children;
}