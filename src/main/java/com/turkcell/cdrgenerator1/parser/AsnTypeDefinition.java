package com.turkcell.cdrgenerator1.parser;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AsnTypeDefinition {
    private String typeName;
    private AsnTypeKind kind;
    private String rawBody;
    private String aliasTarget;

    /**
     * The tag a STRUCTURED type puts on itself, kept verbatim as written:
     * {@code "[APPLICATION 1] "} from {@code TransferBatch ::= [APPLICATION 1]
     * SEQUENCE { ... }}, or {@code "[0] IMPLICIT "} from
     * {@code Row ::= [0] IMPLICIT SEQUENCE { ... }}. Null or blank when the type
     * tags nothing.
     *
     * <p>An ALIAS keeps the same annotation inside {@link #aliasTarget}, where
     * the resolver has always read it. A structured type had nowhere to keep it,
     * so the text between {@code ::=} and {@code SEQUENCE} was dropped at parse
     * time and no later stage could recover it: TAP-0309 records came out as a
     * universal {@code 30} instead of {@code 61}, and every APPLICATION-tagged
     * element under them was flattened the same way.</p>
     */
    private String tagPrefix;

    /**
     * The tagging mode of the module this type was DECLARED in, set only when
     * that is not the module being resolved - i.e. on a definition pulled in
     * through an {@code IMPORTS} clause. Null means "the module being resolved",
     * which is every locally declared type.
     *
     * <p>An import crosses a header. {@code GSN50 DEFINITIONS ::=} names no
     * mode; {@code GPRS-Charging-Extensions DEFINITIONS IMPLICIT TAGS ::=} names
     * one, and the types it exports were written under it. The two headers agree
     * about an ordinary context tag - both make it IMPLICIT - and differ in one
     * place: a tag carrying no written keyword on a CHOICE-typed field, which
     * round 13 measured as IMPLICIT for a mode-less header while X.680 8.3 keeps
     * it EXPLICIT under a declared one. Carrying the mode with the definition is
     * what keeps the imported subtree encoded the way its own module says.</p>
     */
    private AsnTaggingMode taggingMode;
}