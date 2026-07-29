package com.turkcell.cdrgenerator1.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AsnField {
    private String fieldName;
    private String fieldType;
    private boolean optional;
    private boolean repeated;

    /**
     * True when this field's type resolves to an ASN.1 CHOICE.
     *
     * <p>A CHOICE is "exactly one of these alternatives", so its encoding IS the
     * encoding of the selected alternative - it must never be wrapped in an
     * extra universal SEQUENCE. {@link #children} therefore holds a single
     * element: the selected alternative, carrying its own tag.</p>
     *
     * <p>The encoder cannot infer this from {@code children} alone, because a
     * SEQUENCE with one field looks identical. Without this flag a tagged
     * CHOICE is emitted as {@code A4 { 30 { ... } }} instead of
     * {@code A4 { A0 { ... } }}, and a decoder rejects the record: the
     * universal SEQUENCE matches none of the CHOICE's alternatives.</p>
     */
    private boolean choice;

    /**
     * True when this field's type (or, for a repeated field, its ELEMENT type)
     * resolves to an ASN.1 SET rather than a SEQUENCE.
     *
     * <p>A SET's universal tag is 17 (0x31), a SEQUENCE's is 16 (0x30). The
     * distinction only becomes visible where the encoder has to emit a
     * universal tag of its own: the inner tag of an EXPLICIT wrapper, and the
     * per-element wrapper of a {@code SEQUENCE OF <Set>}. An IMPLICIT tag
     * replaces the universal tag outright, so it is unaffected.</p>
     *
     * <p>Like {@link #choice}, the encoder cannot infer this from
     * {@link #children}: a SET and a SEQUENCE look identical once resolved to a
     * field list.</p>
     */
    private boolean set;

    private Integer tagNumber;

    /** BER tag class from the [..] annotation; CONTEXT when only a number is given. */
    private BerTagClass tagClass;

    private boolean explicit;

    private List<AsnField> children;
}