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
public class AsnStructure {
    private String structureName;
    private List<AsnField> fields;

    /**
     * True when the module's root type is a CHOICE. In that case {@code fields}
     * holds exactly one element - the selected alternative, carrying its own
     * tag - and the record must NOT be wrapped in an artificial SEQUENCE.
     */
    private boolean choiceRoot;

    /**
     * When {@link #choiceRoot} is true, the ASN.1 type name of the root CHOICE
     * (e.g. "TokenCDR"). This is the key a caller must use in a
     * {@code choiceSelections} map to pick a different alternative. Null when
     * the root is not a CHOICE.
     */
    private String choiceTypeName;

    /**
     * When {@link #choiceRoot} is true, the full list of alternative field
     * names declared on the root CHOICE, in declaration order (not just the
     * currently selected one in {@link #fields}). Lets a UI offer a picker
     * before any alternative-specific fields are resolved. Null when the
     * root is not a CHOICE.
     */
    private List<String> choiceAlternatives;
}
