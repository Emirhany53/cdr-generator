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
}
