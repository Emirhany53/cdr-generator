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
     * True when the root type is a SET rather than a SEQUENCE. The record
     * wrapper then carries universal tag 17 instead of 16 (X.690 8.11).
     * Meaningless when {@link #choiceRoot} is true, since a CHOICE root is
     * encoded as its selected alternative and gets no wrapper of its own.
     */
    private boolean setRoot;

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

    /**
     * Set when the root TYPE tags itself - {@code Row ::= [0] IMPLICIT SEQUENCE},
     * {@code TransferBatch ::= [APPLICATION 1] SEQUENCE}. The record is then that
     * tag's TLV rather than a universal SEQUENCE/SET, so the encoder and the
     * self-check both write and read the record through this one field, whose
     * children are {@link #fields}.
     *
     * <p>Null for every module whose root type carries no tag of its own, which
     * is where it leaves the encoding untouched.</p>
     */
    private AsnField rootTagCarrier;

    /**
     * True when the root type is itself a repeated collection -
     * {@code SnapshotData ::= SEQUENCE OF SnapshotRecord} - because a caller
     * or an EMM binding (emm-record-bindings.yml) named the wrapper
     * explicitly. {@link #fields} still holds the ELEMENT type's own fields
     * unchanged; the encoder wraps the one generated element in an extra
     * outer SEQUENCE OF / SET OF TLV instead of writing it bare.
     *
     * <p>Never set by the root-selection heuristic itself: it already
     * resolves straight past a wrapper like this to the element type (see
     * {@code StructureParserService#selectRootTypeName}), so this is only
     * ever true for a name a caller asked for by hand. Meaningless when
     * {@link #choiceRoot} is true.</p>
     */
    private boolean repeatedRoot;

    /**
     * True when {@link #repeatedRoot} is a {@code SET OF} rather than a
     * {@code SEQUENCE OF} - selects universal tag 17 over 16 for the outer
     * wrapper, the same distinction {@link #setRoot} makes for a
     * non-repeated root.
     */
    private boolean repeatedRootIsSet;
}
